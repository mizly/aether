package dev.aether.modules

import dev.aether.config.AetherConfig
import dev.aether.macro.farming.FarmingMacroManager
import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.macro.MacroWorkerThread
import dev.aether.mixin.MixinMinecraft
import dev.aether.modules.pathfinding.PathfindingManager
import dev.aether.modules.rotation.RotationManager
import dev.aether.util.BazaarUtils
import dev.aether.util.ClientUtils
import dev.aether.util.TablistUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import java.util.ArrayList
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

class ComposterManager private constructor() {
    companion object {
        private val RESOURCE_PATTERN = Pattern.compile("^(\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?)/(\\d{1,3})k$")
        private const val SOURCE_BAZAAR = "BAZAAR"
        private const val SLOT_INSERT_CROPS_FROM_SACKS = 39
        private const val SLOT_INSERT_FUEL_FROM_SACKS = 41
        private const val SLOT_CONFIRM_SACKS = 11
        private const val ACTION_DELAY_MS = 1_000L
        private const val PATH_TIMEOUT_MS = 25_000L
        private const val MENU_TIMEOUT_MS = 5_000L
        private const val INTERACT_ROTATION_MS = 200L
        @Volatile private var running = false
        @Volatile private var autoSequence = false
        @Volatile private var lastAutoRunCompletedMs = System.currentTimeMillis()
        @Volatile private var completionCallback: Runnable? = null
        private fun client(): Minecraft = Minecraft.getInstance()
        @JvmStatic fun isRunning(): Boolean = running
        @JvmStatic fun reset() { running = false; autoSequence = false; completionCallback = null; PathfindingManager.stop(false) }
        @JvmStatic fun getAutoComposterElapsedMs(): Long = Math.max(0L, System.currentTimeMillis() - lastAutoRunCompletedMs)
        @JvmStatic fun shouldRunAutoComposter(): Boolean {
            if (!AetherConfig.AUTO_COMPOSTER.get() || running) return false
            val intervalMs = Math.max(1L, AetherConfig.AUTO_COMPOSTER_INTERVAL_MINUTES.get().toLong()) * 60_000L
            return getAutoComposterElapsedMs() >= intervalMs
        }
        @JvmStatic fun runAutoComposterIfDue(onComplete: Runnable) {
            if (!shouldRunAutoComposter()) { runCompletion(onComplete); return }
            autoSequence = true; completionCallback = onComplete; start(false)
        }
        @JvmStatic fun manualTrigger() { start(true) }
        @Synchronized private fun start(manual: Boolean) {
            val client = Minecraft.getInstance()
            if (client.player == null || client.level == null) { runCompletionIfAuto(); return }
            if (running) { ClientUtils.sendMessage("\u00A7cAuto Composter is already running.", false); runCompletionIfAuto(); return }
            if (!manual && isBazaarMode() && ClientUtils.getPurse() >= 0 && ClientUtils.getPurse() < AetherConfig.AUTO_COMPOSTER_MIN_PURSE.get()) {
                ClientUtils.sendDebugMessage("Composter: skipping because purse is below configured minimum."); markAutoComplete(); runCompletionIfAuto(); return
            }
            running = true
            val shouldResumeFarming = manual && MacroStateManager.getCurrentState() == MacroState.State.FARMING
            if (shouldResumeFarming) client.execute { FarmingMacroManager.disable(client) }
            ClientUtils.sendMessage("\u00A7eStarting Auto Composter...", false)
            MacroWorkerThread.getInstance().submit("AutoComposter") {
                try { runSequence(); if (autoSequence) markAutoComplete() }
                catch (e: Exception) { ClientUtils.sendDebugMessage("Composter error: " + e.message); e.printStackTrace() }
                finally { finish(shouldResumeFarming) }
            }
        }
        @Throws(InterruptedException::class)
        private fun runSequence() {
            val client = client(); if (shouldAbort(client)) return
            ensureScreenClosed(client); ClientUtils.sendCommand("/tptoplot barn"); MacroWorkerThread.sleep(2_000)
            var composter = findComposter(client)
            val target = if (composter != null) composter.blockPosition() else configuredComposterPos()
            val openedAfterPath = walkNearAndOpen(target)
            if (shouldAbort(client)) return
            composter = findComposter(client)
            if (composter == null) { ClientUtils.sendMessage("\u00A7cCould not find the Composter.", false); return }
            if (!openedAfterPath && !openComposter(composter)) { ClientUtils.sendMessage("\u00A7cCould not open the Composter.", false); return }
            if (isSacksMode()) { insertFromSacks(); return }
            val purchases = getConfiguredBazaarPurchases(); ensureScreenClosed(client)
            for (purchase in purchases) {
                if (shouldAbort(client)) return
                ClientUtils.sendDebugMessage("Composter: buying ${purchase.amount()} ${purchase.itemName()}")
                val bought = BazaarUtils.executeBuy(client, purchase.itemName(), purchase.amount())
                if (!bought) { ClientUtils.sendMessage("\u00A7cFailed to buy ${purchase.itemName()}.", false); return }
                waitActionDelay()
            }
            composter = findComposter(client)
            if (composter == null) { ClientUtils.sendMessage("\u00A7cCould not find the Composter after Bazaar buys.", false); return }
            if (!openComposter(composter)) { ClientUtils.sendMessage("\u00A7cCould not reopen the Composter.", false); return }
            fillComposter(purchases); ClientUtils.sendMessage("\u00A7aSupplied composter with resources.", false)
        }
        @JvmStatic fun getSourceModeIndex(): Int = if (isBazaarMode()) 1 else 0
        @JvmStatic fun isBazaarMode(): Boolean = SOURCE_BAZAAR.equals(AetherConfig.AUTO_COMPOSTER_SOURCE_MODE.get(), ignoreCase = true)
        @JvmStatic fun isSacksMode(): Boolean = !isBazaarMode()
        @Throws(InterruptedException::class)
        private fun walkNearAndOpen(target: BlockPos): Boolean {
            val client = client() ?: return false
            val player = client.player ?: return false
            if (player.blockPosition().distSqr(target) <= 9.0) return false
            val pathFinished = AtomicBoolean(false); val pathFailed = AtomicBoolean(false); val rotationStarted = AtomicBoolean(false)
            client.execute { PathfindingManager.startConfiguredWalk(client, target.x, target.y, target.z, {
                rotationStarted.set(true); RotationManager.initiateRotation(client, Vec3.atCenterOf(configuredComposterPos()), INTERACT_ROTATION_MS); pathFinished.set(true)
            }, { pathFailed.set(true) }, true, 0.5) }
            val deadline = System.currentTimeMillis() + PATH_TIMEOUT_MS
            while (!pathFinished.get() && !pathFailed.get() && System.currentTimeMillis() < deadline && !shouldAbort(client)) MacroWorkerThread.sleep(20)
            if (!pathFinished.get()) { PathfindingManager.stop(false); return false }
            if (!rotationStarted.get()) rotateToComposterInteractTarget() else waitForComposterRotation()
            directAttackClick(); return waitForComposterScreen(MENU_TIMEOUT_MS)
        }
        @Throws(InterruptedException::class)
        private fun openComposter(initialComposter: Entity): Boolean {
            val client = client(); if (client == null) return false
            ensureScreenClosed(client)
            for (attempt in 0 until 3) {
                if (shouldAbort(client)) break
                val refreshed = findComposter(client); val composter = refreshed ?: initialComposter
                attemptComposterInteract()
                if (waitForComposterScreen(MENU_TIMEOUT_MS)) return true
                ensureScreenClosed(client); MacroWorkerThread.sleep(250)
            }
            return false
        }
        @Throws(InterruptedException::class)
        private fun attemptComposterInteract() { val client = client(); if (client == null || shouldAbort(client)) return; rotateToComposterInteractTarget(); directAttackClick() }
        @Throws(InterruptedException::class)
        private fun rotateToComposterInteractTarget() {
            val client = client(); if (client == null) return
            val lookTarget = Vec3.atCenterOf(configuredComposterPos()); client.execute { RotationManager.initiateRotation(client, lookTarget, INTERACT_ROTATION_MS) }; waitForComposterRotation()
        }
        @Throws(InterruptedException::class)
        private fun waitForComposterRotation() {
            val client = client(); if (client == null) return
            val deadline = System.currentTimeMillis() + Math.max(600L, INTERACT_ROTATION_MS + 250L)
            while (RotationManager.isRotating() && System.currentTimeMillis() < deadline && !shouldAbort(client)) MacroWorkerThread.sleep(20)
            MacroWorkerThread.sleep(50)
        }
        @Throws(InterruptedException::class)
        private fun directAttackClick() {
            val client = client(); if (client == null) return
            client.execute { if (client.player != null) { val mc = client as MixinMinecraft; mc.`aether$startAttack`(); mc.`aether$continueAttack`(false) } }
            MacroWorkerThread.sleep(120)
        }
        private fun getConfiguredBazaarPurchases(): List<Purchase> {
            val purchases = ArrayList<Purchase>(); val cropMaterial = AetherConfig.AUTO_COMPOSTER_CROP_MATERIAL.get().trim(); val fuelMaterial = AetherConfig.AUTO_COMPOSTER_FUEL_MATERIAL.get().trim()
            if (cropMaterial.isNotEmpty()) purchases.add(Purchase(cropMaterial, Math.max(1, AetherConfig.AUTO_COMPOSTER_CROP_AMOUNT.get())))
            if (fuelMaterial.isNotEmpty()) purchases.add(Purchase(fuelMaterial, Math.max(1, AetherConfig.AUTO_COMPOSTER_FUEL_AMOUNT.get())))
            return purchases
        }
        @Throws(InterruptedException::class)
        private fun insertFromSacks() {
            val client = client(); if (client == null || client.gui.screen() !is AbstractContainerScreen<*>) return
            clickSlot(SLOT_INSERT_CROPS_FROM_SACKS); waitActionDelay(); clickSlot(SLOT_CONFIRM_SACKS); waitActionDelay()
            if (!waitForComposterScreen(MENU_TIMEOUT_MS)) ClientUtils.sendDebugMessage("Composter: composter menu did not return after crop sacks insert.")
            clickSlot(SLOT_INSERT_FUEL_FROM_SACKS); waitActionDelay(); clickSlot(SLOT_CONFIRM_SACKS); waitActionDelay()
            ensureScreenClosed(client); ClientUtils.sendMessage("\u00A7aInserted composter resources from sacks.", false)
        }
        private fun readResource(screen: AbstractContainerScreen<*>, preferredSlot: Int, namePart: String): Resource {
            val slots = screen.menu.slots; val slot = if (preferredSlot < slots.size) slots[preferredSlot] else null
            var resource = readResource(slot); if (resource.known()) return resource
            val containerSize = Math.max(0, slots.size - 36)
            for (i in 0 until containerSize) {
                val candidate = slots[i]; if (!candidate.hasItem()) continue
                val itemName = TablistUtils.stripColors(candidate.item.hoverName.string).lowercase()
                if (itemName.contains(namePart)) { resource = readResource(candidate); if (resource.known()) return resource }
            }
            return Resource.unknown()
        }
        private fun readResource(slot: Slot?): Resource {
            val client = client() ?: return Resource.unknown(); if (slot == null || !slot.hasItem()) return Resource.unknown()
            val player = client.player ?: return Resource.unknown()
            val lore = slot.item.getTooltipLines(Item.TooltipContext.EMPTY, player, net.minecraft.world.item.TooltipFlag.NORMAL)
            for (component in lore) {
                val line = TablistUtils.stripColors(component.string).replace(" ", "").trim(); val matcher = RESOURCE_PATTERN.matcher(line)
                if (!matcher.matches()) continue
                val current = java.lang.Double.parseDouble(matcher.group(1).replace(",", "")).toInt(); val max = Integer.parseInt(matcher.group(2)) * 1_000
                return Resource(current, max)
            }
            return Resource.unknown()
        }
        @Throws(InterruptedException::class)
        private fun fillComposter(purchases: List<Purchase>) {
            val client = client(); if (client == null) return
            val screen = client.gui.screen() as? AbstractContainerScreen<*> ?: return
            for (purchase in purchases) {
                if (shouldAbort(client)) return
                val slotId = findItemSlot(screen, purchase.itemName()); if (slotId == -1) continue
                client.execute { val currentScreen = client.gui.screen() as? AbstractContainerScreen<*>; if (currentScreen != null) ClientUtils.performSlotClick(currentScreen, slotId, 0, ContainerInput.PICKUP) }
                waitActionDelay()
            }
            ensureScreenClosed(client)
        }
        private fun waitActionDelay() { MacroWorkerThread.sleep(ACTION_DELAY_MS) }
        private fun clickSlot(slotId: Int) {
            val client = client() ?: return
            client.execute { val screen = client.gui.screen() as? AbstractContainerScreen<*>; if (screen != null && slotId >= 0 && slotId < screen.menu.slots.size) ClientUtils.performSlotClick(screen, slotId, 0, ContainerInput.PICKUP) }
        }
        private fun findItemSlot(screen: AbstractContainerScreen<*>, itemName: String): Int {
            val target = itemName.lowercase()
            for (i in screen.menu.slots.indices) { val slot = screen.menu.slots[i]; if (slot.hasItem() && TablistUtils.stripColors(slot.item.hoverName.string).lowercase().contains(target)) return i }
            return -1
        }
        private fun waitForComposterScreen(timeoutMs: Long): Boolean {
            val client = client() ?: return false; val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val screen = client.gui.screen() as? AbstractContainerScreen<*>
                if (screen != null && TablistUtils.stripColors(screen.title.string).lowercase().contains("composter")) return true
                MacroWorkerThread.sleep(50)
            }
            return false
        }
        private fun findComposter(client: Minecraft): Entity? {
            val level = client.level ?: return null
            val player = client.player ?: return null
            var closest: Entity? = null; var closestDistance = Double.MAX_VALUE
            for (entity in level.entitiesForRendering()) {
                if (entity !is ArmorStand) continue
                val name = TablistUtils.stripColors(entity.name.string).uppercase(); val displayName = TablistUtils.stripColors(entity.displayName.string).uppercase()
                if (!name.contains("COMPOSTER") && !displayName.contains("COMPOSTER")) continue
                val distance = entity.distanceToSqr(player)
                if (distance < closestDistance) { closestDistance = distance; closest = entity }
            }
            return closest
        }
        private fun configuredComposterPos() = BlockPos(AetherConfig.AUTO_COMPOSTER_X.get(), AetherConfig.AUTO_COMPOSTER_Y.get(), AetherConfig.AUTO_COMPOSTER_Z.get())
        private fun ensureScreenClosed(client: Minecraft?) {
            if (client == null || client.gui.screen() == null) return
            client.execute { client.player?.closeContainer() }; MacroWorkerThread.sleep(ThreadLocalRandom.current().nextInt(150, 301).toLong())
        }
        private fun shouldAbort(client: Minecraft?): Boolean = !running || MacroWorkerThread.getInstance().isCancelled() || client == null || client.player == null || client.level == null
        private fun finish(shouldResumeFarming: Boolean) {
            val client = client(); ensureScreenClosed(client); PathfindingManager.stop(false); RotationManager.cancelRotation(); running = false
            val callback = completionCallback; completionCallback = null; val wasAuto = autoSequence; autoSequence = false
            if (callback != null) { runCompletion(callback); return }
            if (client != null && shouldResumeFarming && MacroStateManager.isMacroRunning()) {
                MacroStateManager.setCurrentState(MacroState.State.FARMING); client.execute { FarmingMacroManager.enable(client, FarmingMacroManager.createMacroFromConfig()) }
            } else if (!wasAuto) ClientUtils.sendMessage("\u00A7aAuto Composter finished.", false)
        }
        private fun markAutoComplete() { lastAutoRunCompletedMs = System.currentTimeMillis() }
        private fun runCompletionIfAuto() { val callback = completionCallback; completionCallback = null; autoSequence = false; runCompletion(callback) }
        private fun runCompletion(callback: Runnable?) { callback?.run() }
    }
    private class Purchase(private val itemNameValue: String, private val amountValue: Int) {
        fun itemName() = itemNameValue
        fun amount() = amountValue
    }
    private class Resource(private val current: Int, private val max: Int) {
        fun known() = current >= 0 && max > 0
        companion object { fun unknown() = Resource(-1, -1) }
    }
}
