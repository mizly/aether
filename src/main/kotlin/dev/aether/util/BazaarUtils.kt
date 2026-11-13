package dev.aether.util

import dev.aether.config.AetherConfig
import dev.aether.config.ConfigHelpers
import dev.aether.macro.MacroWorkerThread
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.function.Predicate

class BazaarUtils private constructor() {
    companion object {
        private const val SEARCH_SLOT_START = 10
        private const val SEARCH_SLOT_END = 42
        private const val SLOT_BUY_INSTANTLY = 10
        private const val SLOT_QTY_1 = 10
        private const val SLOT_QTY_64 = 12
        private const val SLOT_QTY_CUSTOM = 16
        private const val TICK_MS: Long = 50
        private const val SELL_SETTLE_MS: Long = 750
        private const val SELL_SETTLE_TIMEOUT_MS: Long = 4000
        @Volatile private var activeBuy: BazaarBuySession? = null
        @JvmField @Volatile var isBuying: Boolean = false
        @JvmField @Volatile var isSellingBazaar: Boolean = false
        @JvmField @Volatile var detectedInstantSell: Boolean = false
        @JvmField @Volatile var detectedNoItemsToSell: Boolean = false

        @JvmStatic fun cancel() {
            activeBuy = null
            isBuying = false
            isSellingBazaar = false
        }

        @JvmStatic fun buy(client: Minecraft?, itemName: String?, count: Int, callback: Consumer<Boolean>?) {
            if (isBuying) {
                ClientUtils.sendDebugMessage("[BazaarUtils] Already buying, skipping.")
                callback?.accept(false)
                return
            }
            isBuying = true
            MacroWorkerThread.getInstance().submit("BazaarBuy-$itemName") {
                try {
                    val result = executeBuy(client, itemName, count)
                    callback?.accept(result)
                } catch (e: Exception) {
                    e.printStackTrace()
                    callback?.accept(false)
                } finally {
                    isBuying = false
                }
            }
        }

        @JvmStatic fun buyAsync(client: Minecraft?, itemName: String?, count: Int): CompletableFuture<Boolean> {
            val future = CompletableFuture<Boolean>()
            buy(client, itemName, count, Consumer { future.complete(it) })
            return future
        }

        @JvmStatic fun instantSell(client: Minecraft?, callback: Consumer<Boolean>?) {
            if (isSellingBazaar || isBuying) {
                ClientUtils.sendDebugMessage("[BazaarUtils] Busy with another Bazaar operation.")
                callback?.accept(false)
                return
            }
            isSellingBazaar = true
            detectedInstantSell = false
            MacroWorkerThread.getInstance().submit("BazaarInstantSell") {
                try {
                    val result = executeInstantSell(client)
                    callback?.accept(result)
                } catch (e: Exception) {
                    e.printStackTrace()
                    callback?.accept(false)
                } finally {
                    isSellingBazaar = false
                }
            }
        }

        @JvmStatic fun executeBuy(client: Minecraft?, itemName: String?, count: Int): Boolean {
            if (client == null || count <= 0 || itemName == null || itemName.isBlank()) return false
            val purchase = BazaarBuySession(itemName, count)
            activeBuy = purchase
            try { return executeBuy(client, itemName, count, purchase) }
            finally { if (activeBuy === purchase) activeBuy = null }
        }

        @JvmStatic fun onBuyChatMessage(text: String) { activeBuy?.onChat(text) }

        private fun executeBuy(client: Minecraft, itemName: String, count: Int, purchase: BazaarBuySession): Boolean {
            val guiDelay = Math.max(50L, ConfigHelpers.getRandomizedDelay(AetherConfig.BAZAAR_DELAY_MIN.get(), AetherConfig.BAZAAR_DELAY_MAX.get()).toLong())
            val fastDelay = Math.max(75L, guiDelay / 2L)
            val longDelay = guiDelay + 250L
            ClientUtils.sendDebugMessage("[BazaarUtils] Waiting for screen to close...")
            val screenCloseDeadline = System.currentTimeMillis() + 3000L
            while (!MacroWorkerThread.getInstance().isCancelled() && client.gui.screen() != null && System.currentTimeMillis() < screenCloseDeadline) MacroWorkerThread.sleep(100)
            if (client.gui.screen() != null) {
                ClientUtils.sendDebugMessage("[BazaarUtils] Screen still open after 3s, forcing close")
                closeScreen(client)
                MacroWorkerThread.sleep(fastDelay)
            }
            msg(client, "\u00A7eOpening Bazaar for: \u00A7e$itemName x$count")
            ClientUtils.sendDebugMessage("[BazaarUtils] Sending /bz command")
            MacroWorkerThread.runOnClient(client) { ClientUtils.sendCommand("/bz $itemName") }
            MacroWorkerThread.sleep(longDelay)
            ClientUtils.sendDebugMessage("[BazaarUtils] Waiting for Bazaar screen...")
            if (!waitForContainerTitle(client, "Bazaar", 10_000)) {
                msg(client, "\u00A7cBazaar screen did not open. Aborting.")
                ClientUtils.sendDebugMessage("[BazaarUtils] STUCK: Bazaar screen never opened")
                closeScreen(client)
                return false
            }
            ClientUtils.sendDebugMessage("[BazaarUtils] Bazaar screen opened")
            MacroWorkerThread.sleep(fastDelay)
            val target = stripColors(itemName).lowercase().trim()
            val stages: MutableList<RetryingClickSequence.Stage> = ArrayList<RetryingClickSequence.Stage>()
            stages.add(RetryingClickSequence.Stage("Bazaar search result for $target", { isBuyInstantlyStage(client) }, { clickMatchingSlot(client, target) }, 8_000L))
            stages.add(RetryingClickSequence.Stage("Buy Instantly", { isQuantityScreen(client) }, { clickNamedSlot(client, SLOT_BUY_INSTANTLY) { it.contains("buy instantly") } }, 8_000L))
            if (count == 1 || count == 64) {
                val quantitySlot = if (count == 1) SLOT_QTY_1 else SLOT_QTY_64
                stages.add(RetryingClickSequence.Stage("quantity $count", { purchase.completed() || isPurchaseDialogOpen(client) }, { clickQuantitySlot(client, quantitySlot) }, 8_000L))
            } else {
                stages.add(RetryingClickSequence.Stage("Custom Amount", { isSignScreen(client) }, { clickNamedSlot(client, SLOT_QTY_CUSTOM) { it.contains("custom") || it.contains("amount") || it.contains("sign") } }, 5_000L))
                stages.add(RetryingClickSequence.Stage("custom amount sign", { purchase.completed() || isPurchaseDialogOpen(client) }, { submitSignAmountIfOpen(client, count) }, 8_000L))
            }
            val retryDelay = Math.max(2500L, guiDelay)
            if (!RetryingClickSequence.run(stages, fastDelay, retryDelay, { MacroWorkerThread.getInstance().isCancelled() }, { MacroWorkerThread.sleep(it) }, Consumer { ClientUtils.sendDebugMessage("[BazaarUtils] $it") })) {
                msg(client, "\u00A7cBazaar buy click sequence failed. Aborting.")
                closeScreen(client)
                return false
            }
            return finishBuy(client, count, fastDelay, guiDelay, purchase)
        }

        @JvmStatic fun executeInstantSell(client: Minecraft?): Boolean {
            if (client == null) return false
            isSellingBazaar = true
            detectedInstantSell = false
            detectedNoItemsToSell = false
            try {
                val startTime = System.currentTimeMillis()
                val globalDeadline = startTime + 10000L
                val guiDelay = Math.max(50L, ConfigHelpers.getRandomizedDelay(AetherConfig.BAZAAR_DELAY_MIN.get(), AetherConfig.BAZAAR_DELAY_MAX.get()).toLong())
                val fastDelay = Math.max(75L, guiDelay / 2L)
                val longDelay = guiDelay + 250L
                ClientUtils.sendDebugMessage("[BazaarUtils] Sending /bz command for instant sell")
                MacroWorkerThread.runOnClient(client) { ClientUtils.sendCommand("/bz") }
                MacroWorkerThread.sleep(longDelay)
                if (!waitForContainerTitle(client, "Bazaar", Math.max(100L, globalDeadline - System.currentTimeMillis()))) {
                    ClientUtils.sendDebugMessage("[BazaarUtils] Failed to open Bazaar menu (timeout)")
                    closeScreen(client)
                    return false
                }
                MacroWorkerThread.sleep(fastDelay)
                if (!clickMatchingSlotInAny(client, "Sell Inventory Now")) {
                    ClientUtils.sendDebugMessage("[BazaarUtils] Could not find 'Sell Inventory Now'")
                    closeScreen(client)
                    return false
                }
                MacroWorkerThread.sleep(longDelay)
                if (detectedNoItemsToSell) {
                    ClientUtils.sendDebugMessage("[BazaarUtils] Nothing to sell detected after click. Aborting.")
                    closeScreen(client)
                    return true
                }
                if (!waitForContainerTitle(client, "Are you sure?", Math.max(100L, globalDeadline - System.currentTimeMillis()))) {
                    if (detectedNoItemsToSell) {
                        ClientUtils.sendDebugMessage("[BazaarUtils] Nothing to sell detected while waiting for menu. Aborting.")
                        closeScreen(client)
                        return true
                    }
                    ClientUtils.sendDebugMessage("[BazaarUtils] Failed to open Sell Inventory menu (timeout). Current: " + (if (client.gui.screen() != null) client.gui.screen()!!.title.string else "null"))
                    closeScreen(client)
                    return false
                }
                MacroWorkerThread.sleep(fastDelay)
                if (!clickMatchingSlotInAny(client, "Selling whole inventory")) {
                    ClientUtils.sendDebugMessage("[BazaarUtils] Could not find 'Selling whole inventory'")
                    closeScreen(client)
                    return false
                }
                ClientUtils.sendDebugMessage("[BazaarUtils] Waiting for 'Executing instant sell...' in chat...")
                var completionTitle = currentContainerTitle(client)
                while (!MacroWorkerThread.getInstance().isCancelled() && System.currentTimeMillis() < globalDeadline && !isInstantSellFinished(detectedInstantSell, detectedNoItemsToSell, completionTitle)) {
                    MacroWorkerThread.sleep(100)
                    completionTitle = currentContainerTitle(client)
                }
                if (detectedInstantSell) ClientUtils.sendDebugMessage("[BazaarUtils] Instant sell complete.")
                else if (detectedNoItemsToSell) ClientUtils.sendDebugMessage("[BazaarUtils] Nothing to sell detected during wait.")
                else if (isInstantSellFinished(false, false, completionTitle)) ClientUtils.sendDebugMessage("[BazaarUtils] Instant sell completed via Bazaar menu return.")
                else ClientUtils.sendDebugMessage("[BazaarUtils] Timeout waiting for confirm message or process completion.")
                closeScreen(client)
                awaitScreenClosed(client, SELL_SETTLE_MS, SELL_SETTLE_TIMEOUT_MS)
                MacroWorkerThread.sleep(fastDelay)
                return isInstantSellFinished(detectedInstantSell, detectedNoItemsToSell, completionTitle)
            } finally { isSellingBazaar = false }
        }

        private fun awaitScreenClosed(client: Minecraft, settleMs: Long, timeoutMs: Long) {
            if (client.isSameThread) return
            val deadline = System.currentTimeMillis() + timeoutMs
            var closedSince = -1L
            while (!MacroWorkerThread.getInstance().isCancelled() && System.currentTimeMillis() < deadline) {
                if (client.gui.screen() is AbstractContainerScreen<*>) {
                    ClientUtils.sendDebugMessage("[BazaarUtils] Container reopened after the sale, closing it again.")
                    closedSince = -1L
                    closeScreen(client)
                    continue
                }
                val now = System.currentTimeMillis()
                if (closedSince < 0L) closedSince = now else if (now - closedSince >= settleMs) return
                MacroWorkerThread.sleep(TICK_MS)
            }
            if (client.gui.screen() is AbstractContainerScreen<*>) ClientUtils.sendDebugMessage("[BazaarUtils] Container still open after the sell settle timeout.")
        }

        private fun finishBuy(client: Minecraft, count: Int, fastDelay: Long, guiDelay: Long, purchase: BazaarBuySession): Boolean {
            val confirmation = RetryingClickSequence.Stage("purchase confirmation", { purchase.completed() }, { clickPurchaseConfirmation(client, purchase, guiDelay) }, 30_000L)
            val completed = RetryingClickSequence.run(listOf(confirmation), 0L, Math.max(500L, guiDelay), { MacroWorkerThread.getInstance().isCancelled() || purchase.blocker() != null }, { MacroWorkerThread.sleep(it) }, Consumer { ClientUtils.sendDebugMessage("[BazaarUtils] $it") })
            closeScreen(client)
            if (!completed) {
                val blocker = purchase.blocker()
                if (blocker != null) {
                    ClientUtils.sendDebugMessage("[BazaarUtils] Bazaar refused the purchase of ${count}x ${purchase.item()}: $blocker")
                    msg(client, "§cBazaar refused the buy: §e$blocker")
                } else msg(client, "§cBazaar purchase was not confirmed. Aborting.")
                return false
            }
            MacroWorkerThread.sleep(fastDelay)
            msg(client, "§aBazaar buy completed (§e$count§7).")
            return true
        }

        private fun clickPurchaseConfirmation(client: Minecraft, purchase: BazaarBuySession, guiDelay: Long): Boolean {
            val result = CompletableFuture<Boolean>()
            MacroWorkerThread.runOnClient(client) {
                try { result.complete(activeBuy === purchase && pollPurchaseConfirmation(client, purchase, guiDelay)) }
                catch (error: RuntimeException) { result.completeExceptionally(error) }
            }
            try { return result.get(2, TimeUnit.SECONDS) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); return false }
            catch (error: Exception) { ClientUtils.sendDebugMessage("[BazaarUtils] Confirmation poll failed: ${error.message}"); return false }
        }

        private fun pollPurchaseConfirmation(client: Minecraft, purchase: BazaarBuySession, guiDelay: Long): Boolean {
            val screen = client.gui.screen() as? AbstractContainerScreen<*> ?: return false
            if (purchase.completed()) return false
            val items: MutableList<BazaarBuySession.MenuItem> = ArrayList<BazaarBuySession.MenuItem>()
            for (slot in screen.menu.slots) {
                if (client.player == null || slot.container === client.player!!.inventory || !slot.hasItem()) continue
                val lore = slot.item.get(DataComponents.LORE)
                items.add(BazaarBuySession.MenuItem(slot.index, slot.item.hoverName.string, slot.item.`is`(Items.BARRIER), lore?.lines()?.stream()?.map(Component::getString)?.toList() ?: java.util.List.of()))
            }
            val confirmSlot = purchase.confirmationSlot(screen.menu.containerId, screen.title.string, items, System.currentTimeMillis(), guiDelay)
            if (confirmSlot >= 0) {
                ClientUtils.sendDebugMessage("[BazaarUtils] Confirming unlocked purchase in slot $confirmSlot")
                ClientUtils.performSlotClick(screen, confirmSlot, 0, ContainerInput.PICKUP)
                return true
            }
            return false
        }

        private fun clickMatchingSlot(client: Minecraft, target: String): Boolean {
            val screen = client.gui.screen() as? AbstractContainerScreen<*> ?: return false
            for (slotIdx in SEARCH_SLOT_START..SEARCH_SLOT_END) {
                if (slotIdx >= screen.menu.slots.size) break
                val slot = screen.menu.slots[slotIdx]
                if (!slot.hasItem()) continue
                val name = stripColors(slot.item.hoverName.string).lowercase().trim()
                if (name == target) {
                    ClientUtils.sendDebugMessage("[BazaarUtils] Exact match found: '$name' at slot $slotIdx")
                    val containerId = screen.menu.containerId
                    MacroWorkerThread.runOnClient(client) {
                        val current = client.gui.screen() as? AbstractContainerScreen<*>
                        if (current != null && current.menu.containerId == containerId && slotNameMatches(current, slotIdx) { it == target }) ClientUtils.performSlotClick(current, slotIdx, 0, ContainerInput.PICKUP)
                    }
                    return true
                }
            }
            return false
        }

        private fun clickMatchingSlotInAny(client: Minecraft, targetName: String): Boolean {
            val screen = client.gui.screen() as? AbstractContainerScreen<*> ?: return false
            val target = stripColors(targetName).lowercase()
            for (slot in screen.menu.slots) {
                if (!slot.hasItem()) continue
                val name = stripColors(slot.item.hoverName.string).lowercase()
                if (name.contains(target)) {
                    val slotIdx = slot.index
                    ClientUtils.sendDebugMessage("[BazaarUtils] Found '$targetName' at slot $slotIdx")
                    MacroWorkerThread.runOnClient(client) {
                        val current = client.gui.screen() as? AbstractContainerScreen<*>
                        if (current != null) ClientUtils.performSlotClick(current, slotIdx, 0, ContainerInput.PICKUP)
                    }
                    return true
                }
            }
            return false
        }

        private fun clickNamedSlot(client: Minecraft, slotIdx: Int, expectedName: Predicate<String>): Boolean {
            val screen = client.gui.screen() as? AbstractContainerScreen<*> ?: return false
            if (!slotNameMatches(screen, slotIdx, expectedName)) return false
            val containerId = screen.menu.containerId
            MacroWorkerThread.runOnClient(client) {
                val current = client.gui.screen() as? AbstractContainerScreen<*>
                if (current != null && current.menu.containerId == containerId && slotNameMatches(current, slotIdx, expectedName)) ClientUtils.performSlotClick(current, slotIdx, 0, ContainerInput.PICKUP)
            }
            return true
        }

        private fun clickQuantitySlot(client: Minecraft, slotIdx: Int): Boolean = isQuantityScreen(client) && clickNamedSlot(client, slotIdx, Predicate { true })

        private fun slotNameMatches(screen: AbstractContainerScreen<*>, slotIdx: Int, expectedName: Predicate<String>): Boolean {
            if (slotIdx < 0 || slotIdx >= screen.menu.slots.size) return false
            val slot = screen.menu.slots[slotIdx]
            if (!slot.hasItem()) return false
            val name = stripColors(slot.item.hoverName.string).lowercase().trim()
            return expectedName.test(name)
        }

        private fun waitForContainerTitle(client: Minecraft, substring: String, timeoutMs: Long): Boolean {
            val target = stripColors(substring).lowercase()
            val deadline = System.currentTimeMillis() + timeoutMs
            var lastTitle = ""
            var iterations = 0
            while (!MacroWorkerThread.getInstance().isCancelled() && System.currentTimeMillis() < deadline) {
                val screen = client.gui.screen() as? AbstractContainerScreen<*>
                if (screen != null) {
                    val title = stripColors(screen.title.string).lowercase()
                    if (title != lastTitle) {
                        ClientUtils.sendDebugMessage("[BazaarUtils] Current screen: '$title' (waiting for '$target')")
                        lastTitle = title
                    }
                    if (title.contains(target)) return true
                } else if (iterations % 20 == 0) ClientUtils.sendDebugMessage("[BazaarUtils] No container screen open (waiting for '$target')")
                iterations++
                MacroWorkerThread.sleep(TICK_MS)
            }
            ClientUtils.sendDebugMessage("[BazaarUtils] Timeout waiting for '$target'. Last screen: '$lastTitle'")
            return false
        }

        @JvmStatic fun isInstantSellFinished(sold: Boolean, nothingToSell: Boolean, screenTitle: String?): Boolean = sold || nothingToSell || screenTitle != null && stripColors(screenTitle).lowercase().contains("bazaar")
        private fun currentContainerTitle(client: Minecraft): String? = (client.gui.screen() as? AbstractContainerScreen<*>)?.title?.string
        private fun isSignScreen(client: Minecraft): Boolean = client.gui.screen() is AbstractSignEditScreen
        private fun isQuantityScreen(client: Minecraft): Boolean {
            val screen = client.gui.screen() as? AbstractContainerScreen<*> ?: return false
            return slotNameMatches(screen, SLOT_QTY_CUSTOM) { it.contains("custom") || it.contains("amount") || it.contains("sign") }
        }
        private fun isBuyInstantlyStage(client: Minecraft): Boolean {
            val screen = client.gui.screen() as? AbstractContainerScreen<*> ?: return false
            return slotNameMatches(screen, SLOT_BUY_INSTANTLY) { it.contains("buy instantly") }
        }
        private fun isPurchaseDialogOpen(client: Minecraft): Boolean = (client.gui.screen() as? AbstractContainerScreen<*>)?.let { BazaarBuySession.isPurchaseDialog(it.title.string) } ?: false
        private fun submitSignAmountIfOpen(client: Minecraft, amount: Int): Boolean { if (!isSignScreen(client)) return false; submitSignAmount(client, amount); return true }

        private fun submitSignAmount(client: Minecraft, amount: Int) {
            val text = amount.toString()
            MacroWorkerThread.runOnClient(client) {
                val signScreen = client.gui.screen() as? AbstractSignEditScreen
                if (signScreen == null) { msg(client, "\u00A7cSign screen not found for amount input!"); return@runOnClient }
                signScreen.keyPressed(net.minecraft.client.input.KeyEvent(InputConstants.KEY_A, 0, InputConstants.MOD_CONTROL))
                signScreen.keyPressed(net.minecraft.client.input.KeyEvent(InputConstants.KEY_DELETE, 0, 0))
                for (c in text.toCharArray()) signScreen.charTyped(net.minecraft.client.input.CharacterEvent(c.code))
            }
            MacroWorkerThread.sleep(Math.max(50L, (ConfigHelpers.getRandomizedDelay(AetherConfig.BAZAAR_DELAY_MIN.get(), AetherConfig.BAZAAR_DELAY_MAX.get()) / 2).toLong()))
            MacroWorkerThread.runOnClient(client) { (client.gui.screen() as? AbstractSignEditScreen)?.onClose() }
        }

        private fun closeScreen(client: Minecraft) { if (client.isSameThread) ClientUtils.closeGuiAsync(client) else ClientUtils.closeGui(client) }
        private fun stripColors(s: String): String = TablistUtils.stripColors(s)
        private fun msg(client: Minecraft, text: String) { if (!MacroWorkerThread.getInstance().isCancelled()) ClientUtils.sendMessage(text) }
    }
}
