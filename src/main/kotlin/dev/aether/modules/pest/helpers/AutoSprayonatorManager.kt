package dev.aether.modules.pest.helpers

import dev.aether.config.AetherConfig
import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.macro.MacroWorkerThread
import dev.aether.macro.farming.FarmingMacroManager
import dev.aether.modules.failsafe.FailsafeManager
import dev.aether.modules.pest.PestManager
import dev.aether.util.BazaarUtils
import dev.aether.util.ClientUtils
import dev.aether.util.ProgrammaticAttackTracker
import dev.aether.util.TablistUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import java.util.regex.Pattern

class AutoSprayonatorManager private constructor() {
    private enum class SprayResult { NONE, SUCCESS, NO_MATERIAL, INVALID_PLOT }

    companion object {
        private val NO_MATERIAL_PATTERN = Pattern.compile("(?i)^you don't have enough\\s+(.+?)(?:!|\\.)?$")
        private val MATERIAL_CHANGE_PATTERN = Pattern.compile("(?i)^sprayonator! your selected material is now (.+?)!?\\s*$")

        @Volatile private var running = false
        @Volatile private var cancelRequested = false
        @Volatile private var awaitingSprayResult = false
        @Volatile private var pendingResult = SprayResult.NONE
        @Volatile private var missingMaterial: String? = null
        @Volatile private var pendingMaterialChange: String? = null
        @Volatile private var lastRunMs = 0L
        @Volatile private var sprayNeededSinceMs = 0L

        private const val RUN_COOLDOWN_MS = 10_000L
        private const val TAB_CHECK_INTERVAL_MS = 750L
        private var lastTabCheckMs = 0L

        @JvmStatic fun reset() {
            cancelRequested = false
            running = false
            awaitingSprayResult = false
            pendingResult = SprayResult.NONE
            missingMaterial = null
            pendingMaterialChange = null
            lastRunMs = 0L
            sprayNeededSinceMs = 0L
            lastTabCheckMs = 0L
        }

        @JvmStatic fun cancel() {
            cancelRequested = true
            running = false
            awaitingSprayResult = false
            pendingResult = SprayResult.NONE
            missingMaterial = null
            pendingMaterialChange = null
        }

        @JvmStatic fun isRunning(): Boolean = running

        @JvmStatic fun isSprayNeededNow(client: Minecraft?): Boolean {
            if (client?.player == null || client.connection == null) return false
            return tabListNeedsSpray(client)
        }

        @JvmStatic fun getSprayNeededElapsedMs(client: Minecraft): Long {
            if (!isSprayNeededNow(client) || sprayNeededSinceMs == 0L) return 0L
            return maxOf(0L, System.currentTimeMillis() - sprayNeededSinceMs)
        }

        @JvmStatic fun update() {
            val client = Minecraft.getInstance()
            if (client.player == null || client.connection == null) return
            if (!AetherConfig.AUTO_SPRAYONATOR.get() || running) return
            if (MacroStateManager.getCurrentState() != MacroState.State.FARMING) return

            val now = System.currentTimeMillis()
            if (now - lastRunMs < RUN_COOLDOWN_MS || now - lastTabCheckMs < TAB_CHECK_INTERVAL_MS) return
            lastTabCheckMs = now

            if (!tabListNeedsSpray(client)) {
                sprayNeededSinceMs = 0L
                return
            }
            if (sprayNeededSinceMs == 0L) {
                sprayNeededSinceMs = now
                return
            }
            if (now - sprayNeededSinceMs < AetherConfig.AUTO_SPRAYONATOR_DETECT_TIME.get() * 1000L) return
            // Sprayonator sprays the plot we're standing on, and the Barn refuses sprays.
            if ("Barn".equals(ClientUtils.getCurrentPlot(), ignoreCase = true)) return

            running = true
            cancelRequested = false
            lastRunMs = now
            sprayNeededSinceMs = 0L
            MacroWorkerThread.getInstance().submit("AutoSprayonator") { runSequence(client) }
        }

        @JvmStatic fun onChatMessage(plainText: String?) {
            if (plainText == null) return
            val msg = plainText.replace(Regex("(?i)[\\u00A7&][0-9a-fk-or]"), "").trim()
            val lower = msg.lowercase()
            val matChange = MATERIAL_CHANGE_PATTERN.matcher(msg)
            if (matChange.find()) {
                pendingMaterialChange = matChange.group(1).trim()
                return
            }
            if (!awaitingSprayResult) return
            if (lower.startsWith("sprayonator! you sprayed plot") || lower == "this plot was sprayed with that item recently! try again soon!") {
                pendingResult = SprayResult.SUCCESS
                return
            }
            if (lower.startsWith("you cannot spray")) {
                pendingResult = SprayResult.INVALID_PLOT
                return
            }
            val noMat = NO_MATERIAL_PATTERN.matcher(msg)
            if (noMat.find()) {
                missingMaterial = noMat.group(1).trim()
                pendingResult = SprayResult.NO_MATERIAL
            }
        }

        private fun runSequence(client: Minecraft) {
            val guiDelay = maxOf(50L, ClientUtils.getGuiClickDelayMs(false))
            try {
                cancelRequested = false
                if (PestClientThread.call(client, { findSprayonatorSlot(client) }, -1)!! < 0) {
                    msg(client, "\u00A7cSprayonator not found in hotbar. Skipping auto spray.")
                    return
                }
                MacroStateManager.setCurrentState(MacroState.State.SPRAYING)
                PestManager.setCleaningInProgress(true)
                msg(client, "\u00A7eUnsprayed plot detected. Pausing farming to spray...")
                PestClientThread.run(client) { FarmingMacroManager.disable(client) }
                MacroWorkerThread.sleep(guiDelay)
                if (!holdSprayonator(client) || shouldAbort()) {
                    msg(client, "\u00A7cCould not safely equip the sprayonator. Skipping auto spray.")
                    return
                }
                if (!attemptSprayAndHandleMaterial(client, guiDelay) && !shouldAbort()) msg(client, "\u00A7cAutoSprayonator failed to spray this plot.")
            } catch (e: Exception) {
                e.printStackTrace()
                if (!shouldAbort()) msg(client, "\u00A7cAutoSprayonator error: ${e.message}")
            } finally {
                val aborted = shouldAbort()
                MacroWorkerThread.sleep(guiDelay)
                if (!aborted && MacroStateManager.isMacroRunning()) {
                    client.execute { FarmingMacroManager.enable(client, FarmingMacroManager.createMacroFromConfig()) }
                    MacroStateManager.setCurrentState(MacroState.State.FARMING)
                }
                PestManager.setCleaningInProgress(false)
                running = false
                awaitingSprayResult = false
                pendingResult = SprayResult.NONE
                missingMaterial = null
                if (!aborted && MacroStateManager.isMacroRunning()) msg(client, "\u00A7aAutoSprayonator finished. Resuming farming.")
            }
        }

        private fun attemptSprayAndHandleMaterial(client: Minecraft, guiDelay: Long): Boolean {
            if (!ensureCorrectMaterial(client, guiDelay) || shouldAbort()) {
                if (!shouldAbort()) msg(client, "\u00A7cFailed to set correct spray material.")
                return false
            }
            return sprayHeldMaterialAndHandleMissing(client, guiDelay)
        }

        @JvmStatic fun sprayHeldMaterialAndHandleMissing(client: Minecraft, guiDelay: Long): Boolean {
            if (!trySprayAndWaitResult(client) || shouldAbort()) return false
            if (pendingResult == SprayResult.SUCCESS) return true
            if (pendingResult == SprayResult.INVALID_PLOT) {
                msg(client, "§eThis plot can't be sprayed. Will retry from a farm plot.")
                return true
            }
            if (pendingResult != SprayResult.NO_MATERIAL) return false
            if (!AetherConfig.AUTO_SPRAYONATOR_AUTO_BUY.get()) {
                msg(client, "\u00A7cMissing spray material and auto-buy is disabled.")
                return false
            }
            val materialToBuy = missingMaterial?.takeIf { it.isNotBlank() } ?: run {
                val configured = configuredMaterial()
                if (configured.equals("use selected", true)) getSprayonatorMaterialFromHotbar(client) ?: getCurrentMaterial(client) else configured
            }
            if (materialToBuy.isNullOrBlank()) {
                msg(client, "\u00A7cCould not determine which material to buy for the sprayonator.")
                return false
            }
            val amount = maxOf(1, AetherConfig.AUTO_SPRAYONATOR_AUTO_BUY_AMOUNT.get())
            msg(client, "\u00A7eBuying spray material: \u00A7e${amount}x $materialToBuy")
            val bought = BazaarUtils.executeBuy(client, materialToBuy, amount)
            if (!bought || shouldAbort()) {
                if (!shouldAbort()) msg(client, "\u00A7cFailed to buy spray material from Bazaar.")
                return false
            }
            MacroWorkerThread.sleep(guiDelay)
            if (!holdSprayonator(client) || shouldAbort()) {
                if (!shouldAbort()) msg(client, "\u00A7cSprayonator not found in hotbar after buy.")
                return false
            }
            pendingResult = SprayResult.NONE
            missingMaterial = null
            return trySprayAndWaitResult(client) && pendingResult == SprayResult.SUCCESS && !shouldAbort()
        }

        private fun trySprayAndWaitResult(client: Minecraft): Boolean {
            pendingResult = SprayResult.NONE
            missingMaterial = null
            awaitingSprayResult = true
            try {
                PestClientThread.run(client, ClientUtils::performUseClick)
                val deadline = System.currentTimeMillis() + 5000L
                while (System.currentTimeMillis() < deadline) {
                    if (shouldAbort()) return false
                    if (pendingResult != SprayResult.NONE) return true
                    MacroWorkerThread.sleep(100)
                }
                return false
            } finally {
                awaitingSprayResult = false
            }
        }

        private fun ensureCorrectMaterial(client: Minecraft, guiDelay: Long): Boolean {
            if (client.player == null) return false
            val configured = configuredMaterial()
            val currentMaterial = getCurrentMaterial(client)
            if (configured.equals("use selected", true)) {
                if (currentMaterial != null) {
                    ClientUtils.sendDebugMessage("Sprayonator material (using selected): $currentMaterial")
                    return true
                }
                msg(client, "\u00A7cNo sprayonator material detected in hand. Please select a material.")
                return false
            }
            if (currentMaterial != null && currentMaterial.equals(configured, true)) {
                ClientUtils.sendDebugMessage("Sprayonator material correct: $currentMaterial")
                return true
            }
            msg(client, "\u00A7eWrong sprayonator material (${currentMaterial ?: "unknown"}), selecting \u00A7a$configured\u00A7e...")
            if (cycleToMaterial(client, configured, guiDelay)) {
                msg(client, "\u00A7aSprayonator material set to $configured.")
                return true
            }
            msg(client, "\u00A7cFailed to select sprayonator material $configured.")
            return false
        }

        @JvmStatic fun cycleToMaterial(client: Minecraft?, target: String?, guiDelay: Long): Boolean {
            if (client?.player == null || target.isNullOrBlank()) return false
            pendingMaterialChange = null
            PestClientThread.run(client, ClientUtils::performAttackClickDirect)
            if (!waitForSprayonatorGui(client, 3000L) || shouldAbort()) {
                closeGui(client, guiDelay)
                return false
            }
            MacroWorkerThread.sleep(maxOf(50L, ClientUtils.getGuiClickDelayMs(true)))
            if (shouldAbort()) {
                closeGui(client, guiDelay)
                return false
            }
            val clicked = clickMaterialSlot(client, target)
            MacroWorkerThread.sleep(guiDelay)
            closeGui(client, guiDelay)
            return clicked && !shouldAbort()
        }

        private fun waitForSprayonatorGui(client: Minecraft, timeoutMs: Long): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (shouldAbort()) return false
                val open = PestClientThread.call(client, {
                    val screen = client.gui.screen()
                    screen is AbstractContainerScreen<*> && TablistUtils.stripColors(screen.title.string).lowercase().contains("sprayonator")
                }, false)!!
                if (open == true) return true
                MacroWorkerThread.sleep(50)
            }
            return false
        }

        private fun clickMaterialSlot(client: Minecraft, target: String): Boolean {
            if (!client.isSameThread()) return PestClientThread.call(client, { clickMaterialSlot(client, target) }, false)!!
            val screen = client.gui.screen() as? AbstractContainerScreen<*> ?: return false
            val normalizedTarget = normalizeText(target)
            var fallbackSlot = -1
            for (i in screen.menu.slots.indices) {
                val slot = screen.menu.slots[i]
                if (!slot.hasItem()) continue
                val stack = slot.item
                val itemName = normalizeText(stack.hoverName.string)
                if (itemName == normalizedTarget) {
                    clickSlot(client, i)
                    return true
                }
                if (fallbackSlot < 0 && stackTextMatches(client, stack, normalizedTarget)) fallbackSlot = i
            }
            if (fallbackSlot >= 0) {
                clickSlot(client, fallbackSlot)
                return true
            }
            return false
        }

        private fun stackTextMatches(client: Minecraft, stack: ItemStack, normalizedTarget: String): Boolean {
            if (normalizeText(stack.hoverName.string).contains(normalizedTarget)) return true
            return try {
                stack.getTooltipLines(Item.TooltipContext.EMPTY, client.player, TooltipFlag.NORMAL)
                    .any { normalizeText(it.string).contains(normalizedTarget) }
            } catch (_: Exception) { false }
        }

        private fun clickSlot(client: Minecraft, slotIndex: Int) {
            PestClientThread.run(client) {
                val screen = client.gui.screen() as? AbstractContainerScreen<*> ?: return@run
                ClientUtils.performSlotClick(screen, slotIndex, 0, ContainerInput.PICKUP)
            }
        }

        private fun closeGui(client: Minecraft?, guiDelay: Long) {
            if (client == null) return
            PestClientThread.run(client) {
                if (client.gui.screen() != null) client.player?.closeContainer()
            }
            MacroWorkerThread.sleep(guiDelay)
        }

        private fun normalizeText(text: String?): String = TablistUtils.stripColors(text ?: "").trim().lowercase()

        @JvmStatic fun ensureMaterial(client: Minecraft, target: String?, guiDelay: Long): Boolean {
            if (client.player == null || target.isNullOrBlank()) return false
            val currentMaterial = getCurrentMaterial(client)
            if (currentMaterial != null && currentMaterial.equals(target, true)) {
                ClientUtils.sendDebugMessage("Sprayonator material already correct: $currentMaterial")
                return true
            }
            ClientUtils.sendDebugMessage("Sprayonator material target: $target, current: ${currentMaterial ?: "unknown"}")
            return cycleToMaterial(client, target, guiDelay)
        }

        @JvmStatic fun getHeldMaterial(client: Minecraft): String? = getCurrentMaterial(client)
        @JvmStatic fun getHotbarMaterial(client: Minecraft): String? = getSprayonatorMaterialFromHotbar(client)

        private fun getCurrentMaterial(client: Minecraft?): String? {
            if (client != null && !client.isSameThread()) return PestClientThread.call(client, { getCurrentMaterial(client) }, null)
            val player = client?.player ?: return null
            val held = player.mainHandItem
            if (held.isEmpty) return null
            return SprayonatorItem.selectedMaterial(held)
        }

        private fun getSprayonatorMaterialFromHotbar(client: Minecraft?): String? {
            if (client != null && !client.isSameThread()) return PestClientThread.call(client, { getSprayonatorMaterialFromHotbar(client) }, null)
            val player = client?.player ?: return null
            val slot = findSprayonatorSlot(client)
            return if (slot < 0) null else SprayonatorItem.selectedMaterial(player.inventory.getItem(slot))
        }

        @JvmStatic fun holdSprayonator(client: Minecraft?): Boolean {
            if (client == null || client.isSameThread() || shouldAbort()) return false
            val slot: Int = PestClientThread.call(client, { findSprayonatorSlot(client) }, -1)!!
            if (slot < 0) return false
            val guard = SprayonatorSwapGuard()
            val deadline = System.currentTimeMillis() + 2_000L
            while (!shouldAbort() && System.currentTimeMillis() < deadline) {
                val selected = PestClientThread.call(client, {
                    if (shouldAbort() || System.currentTimeMillis() >= deadline) return@call false
                    val player = client.player ?: return@call false
                    val gameMode = client.gameMode ?: return@call false
                    if (findSprayonatorSlot(client) != slot) return@call false
                    val attacking = client.options.keyAttack.isDown || ProgrammaticAttackTracker.isHeld()
                    val queuedAttack = client.options.keyAttack.consumeClick()
                    FarmingMacroManager.releaseInputs(client)
                    ProgrammaticAttackTracker.setHeld(client.options.keyAttack, false)
                    ClientUtils.setKeyMappingState(client.options.keyAttack, false)
                    ClientUtils.discardQueuedClicks(client.options.keyAttack)
                    gameMode.stopDestroyBlock()
                    if (!guard.readyToSwap(player.tickCount, attacking || queuedAttack)) return@call false
                    FailsafeManager.selectHotbarSlot(client, slot)
                    true
                }, false)!!
                if (selected) return MacroWorkerThread.sleep(150) && !shouldAbort()
                if (!MacroWorkerThread.sleep(25)) return false
            }
            return false
        }

        private fun findSprayonatorSlot(client: Minecraft): Int {
            val player = client.player ?: return -1
            for (i in 0 until 9) if (SprayonatorItem.matches(player.inventory.getItem(i))) return i
            return -1
        }

        private fun tabListNeedsSpray(client: Minecraft): Boolean {
            if (!client.isSameThread()) return PestClientThread.call(client, { tabListNeedsSpray(client) }, false)!!
            return TablistUtils.getRawTabLines(client).any { it.lowercase().let { line -> line.contains("spray:") && line.contains("none") } }
        }

        private fun configuredMaterial(): String {
            val configured = AetherConfig.AUTO_SPRAYONATOR_MATERIAL.get()
            return if (configured.isNullOrBlank()) "Use Selected" else configured.trim()
        }

        private fun shouldAbort(): Boolean = cancelRequested || MacroWorkerThread.getInstance().isCancelled()
        private fun msg(client: Minecraft, text: String) { ClientUtils.sendMessage(text) }
    }
}
