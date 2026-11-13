package dev.aether.modules.gear.helpers

import dev.aether.config.AetherConfig
import dev.aether.macro.farming.FarmingMacroManager
import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.macro.MacroWorkerThread
import dev.aether.modules.gear.GearManager
import dev.aether.modules.pest.PestManager
import dev.aether.modules.pest.helpers.AutoPestExchangeManager
import dev.aether.modules.session.RestartManager
import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot

object LoadoutManager {
    private const val LOADOUT_CHAT_RETRY_DELAY_MS = 500L
    private const val WARDROBE_STRAND_TIMEOUT_MS = 5000L
    @Volatile private var wardrobeIdleSinceMs = 0L
    @Volatile @JvmField var isSwappingLoadout = false
    @Volatile @JvmField var loadoutInteractionTime = 0L
    @Volatile @JvmField var loadoutInteractionStage = 0
    @Volatile @JvmField var loadoutCleanupTicks = 0
    @Volatile @JvmField var trackedLoadoutSlot = -1
    @Volatile @JvmField var targetLoadoutSlot = -1
    @Volatile @JvmField var shouldRestartFarmingAfterSwap = false
    @Volatile @JvmField var loadoutOpenPendingTime = 0L
    @Volatile @JvmField var loadoutFirstClickDelayMs = 0L
    @Volatile @JvmField var loadoutGuiDetected = false
    @Volatile @JvmField var loadoutGuiCloseComplete = true
    @Volatile @JvmField var loadoutTimelineStartTime = 0L
    @Volatile private var loadoutChatConfirmed = false
    @Volatile private var loadoutRequestId = 0L

    @JvmStatic fun resetState() {
        isSwappingLoadout = false; shouldRestartFarmingAfterSwap = false; loadoutCleanupTicks = 0
        trackedLoadoutSlot = -1; targetLoadoutSlot = -1; loadoutInteractionTime = 0; loadoutInteractionStage = 0
        loadoutGuiDetected = false; loadoutGuiCloseComplete = true; loadoutRequestId++
        loadoutOpenPendingTime = 0; loadoutFirstClickDelayMs = 0; loadoutTimelineStartTime = 0
        loadoutChatConfirmed = false; wardrobeIdleSinceMs = 0
    }
    @JvmStatic fun reset() = resetState()
    @JvmStatic fun tickWardrobeWatchdog() {
        if (isSwappingLoadout || !loadoutGuiCloseComplete || loadoutCleanupTicks > 0 || !MacroStateManager.isMacroRunning() || MacroStateManager.getCurrentState() != MacroState.State.WARDROBE) { wardrobeIdleSinceMs = 0; return }
        val now = System.currentTimeMillis()
        if (wardrobeIdleSinceMs == 0L) { wardrobeIdleSinceMs = now; return }
        if (now - wardrobeIdleSinceMs < WARDROBE_STRAND_TIMEOUT_MS) return
        wardrobeIdleSinceMs = 0
        ClientUtils.sendDebugMessage("Loadout watchdog: WARDROBE state stranded with no swap in flight for ${WARDROBE_STRAND_TIMEOUT_MS}ms; restoring FARMING.")
        MacroStateManager.setCurrentState(MacroState.State.FARMING)
    }
    @JvmStatic fun triggerLoadoutSwap(client: Minecraft, slot: Int) {
        if (!AetherConfig.AUTO_LOADOUT_ENABLED.get()) return
        if (isSwappingLoadout && targetLoadoutSlot == slot) { ClientUtils.sendDebugMessage("Loadout swap to slot $slot is already pending."); return }
        if (trackedLoadoutSlot == slot) {
            ClientUtils.sendDebugMessage("Loadout already on target slot, restarting farming")
            client.execute { FarmingMacroManager.disable(client) }
            MacroWorkerThread.getInstance().submit("Wardrobe-AlreadyOnSlot-FastResume") {
                if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.FARMING)) return@submit
                MacroWorkerThread.sleep(400)
                if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.FARMING)) return@submit
                if (AutoPestExchangeManager.shouldBlockFarmingResume()) { ClientUtils.sendDebugMessage("Loadout resume deferred because pest exchange has priority."); AutoPestExchangeManager.tryTriggerPending(client); return@submit }
                client.execute { GearManager.swapToFarmingTool(client) }; MacroWorkerThread.sleep(250)
                if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.FARMING)) return@submit
                if (AutoPestExchangeManager.shouldBlockFarmingResume()) { ClientUtils.sendDebugMessage("Loadout resume deferred because pest exchange has priority."); AutoPestExchangeManager.tryTriggerPending(client); return@submit }
                ClientUtils.sendDebugMessage("Restarting farming macro after loadout swap")
                client.execute { FarmingMacroManager.enable(client, FarmingMacroManager.createMacroFromConfig()) }
            }; return
        }
        targetLoadoutSlot = slot; val requestId = ++loadoutRequestId; isSwappingLoadout = true
        loadoutGuiDetected = false; loadoutGuiCloseComplete = false; loadoutInteractionTime = 0; loadoutInteractionStage = 0
        loadoutTimelineStartTime = 0; loadoutOpenPendingTime = 0; loadoutFirstClickDelayMs = 0; loadoutChatConfirmed = false
        shouldRestartFarmingAfterSwap = true; MacroStateManager.setCurrentState(MacroState.State.WARDROBE)
        ClientUtils.sendDebugMessage("Triggering loadout swap to slot $slot"); client.execute { FarmingMacroManager.disable(client) }
        ClientUtils.scheduleClientTask(client, 400L) { if (isSwappingLoadout && targetLoadoutSlot == slot && loadoutRequestId == requestId) ClientUtils.sendCommand("/loadout") }
    }
    @JvmStatic fun ensureLoadoutSlot(client: Minecraft, slot: Int) {
        if (!AetherConfig.AUTO_LOADOUT_ENABLED.get() || slot <= 0 || trackedLoadoutSlot == slot || isSwappingLoadout && targetLoadoutSlot == slot) return
        targetLoadoutSlot = slot; loadoutRequestId++; isSwappingLoadout = true; loadoutGuiDetected = false; loadoutGuiCloseComplete = false
        loadoutInteractionTime = 0; loadoutInteractionStage = 0; loadoutTimelineStartTime = 0; loadoutOpenPendingTime = 0; loadoutFirstClickDelayMs = 0; loadoutChatConfirmed = false
        ClientUtils.sendCommand("/loadout")
    }
    @JvmStatic fun cancelIfDisabled(client: Minecraft) { if (!AetherConfig.AUTO_LOADOUT_ENABLED.get() && isSwappingLoadout) { abortSwapForPriorityTask(client, "auto loadout being disabled"); targetLoadoutSlot = -1; loadoutRequestId++ } }
    @JvmStatic fun abortSwapForPriorityTask(client: Minecraft, taskName: String) {
        if (!isSwappingLoadout) return
        isSwappingLoadout = false; shouldRestartFarmingAfterSwap = false; loadoutGuiDetected = false; loadoutGuiCloseComplete = false
        loadoutInteractionTime = 0; loadoutInteractionStage = 0; loadoutTimelineStartTime = 0; loadoutOpenPendingTime = 0; loadoutFirstClickDelayMs = 0; loadoutChatConfirmed = false
        if (MacroStateManager.getCurrentState() == MacroState.State.WARDROBE) MacroStateManager.setCurrentState(MacroState.State.FARMING)
        ClientUtils.sendDebugMessage("Aborted loadout swap because $taskName has priority."); ClientUtils.closeGui(client); loadoutGuiCloseComplete = true
    }
    @JvmStatic fun handleLoadoutMenu(client: Minecraft, screen: AbstractContainerScreen<*>) {
        if (!AetherConfig.AUTO_LOADOUT_ENABLED.get()) { cancelIfDisabled(client); return }
        if (!isSwappingLoadout || targetLoadoutSlot == -1) return
        if (!screen.title.string.lowercase().contains("loadouts")) return
        val now = System.currentTimeMillis()
        if (!loadoutGuiDetected) { loadoutGuiDetected = true; loadoutTimelineStartTime = now; loadoutOpenPendingTime = now; loadoutFirstClickDelayMs = ClientUtils.getGuiClickDelayMs(true); sendTimedDebug(client, "Loadout GUI opened", now) }
        val slotIdx = getLoadoutGuiSlot(targetLoadoutSlot); if (slotIdx < 0 || slotIdx >= screen.menu.slots.size) return
        val slot: Slot = screen.menu.slots[slotIdx]
        if (loadoutInteractionStage == 1) {
            if (!loadoutChatConfirmed && now - loadoutInteractionTime >= LOADOUT_CHAT_RETRY_DELAY_MS) { sendTimedDebug(client, "Retrying loadout slot $targetLoadoutSlot", now); ClientUtils.performSlotClick(screen, slot.index, 0, ContainerInput.PICKUP); loadoutInteractionTime = now }
            if (!loadoutChatConfirmed) return
            finishLoadoutAfterClick(client, targetLoadoutSlot); return
        }
        if (loadoutInteractionStage != 0 || now - loadoutOpenPendingTime < loadoutFirstClickDelayMs) return
        sendTimedDebug(client, "Clicked loadout slot $targetLoadoutSlot", now); loadoutChatConfirmed = false
        ClientUtils.performSlotClick(screen, slot.index, 0, ContainerInput.PICKUP); loadoutInteractionTime = now; loadoutInteractionStage = 1
    }
    @JvmStatic fun onChatMessage(plainText: String?) { if (!isSwappingLoadout || loadoutInteractionStage != 1 || loadoutChatConfirmed || plainText == null || !plainText.contains("equipped")) return; loadoutChatConfirmed = true; ClientUtils.sendDebugMessage("Loadout chat confirmation received.") }
    private fun finishLoadoutAfterClick(client: Minecraft, clickedLoadoutSlot: Int) {
        if (!isSwappingLoadout || targetLoadoutSlot != clickedLoadoutSlot) return
        val now = System.currentTimeMillis(); trackedLoadoutSlot = clickedLoadoutSlot; isSwappingLoadout = false; loadoutGuiDetected = false; loadoutInteractionStage = 0; loadoutChatConfirmed = false; loadoutOpenPendingTime = 0; loadoutFirstClickDelayMs = 0
        sendTimedDebug(client, "Loadout GUI close requested", now)
        ClientUtils.closeGuiAsync(client).thenRun { loadoutGuiCloseComplete = true; sendTimedDebug(client, "Loadout swap complete. Active slot is now $trackedLoadoutSlot", System.currentTimeMillis()); handleLoadoutCompletion(client) }
    }
    private fun handleLoadoutCompletion(client: Minecraft) {
        RestartManager.onWardrobeSwapCompleted(client)
        if (MacroStateManager.getCurrentState() == MacroState.State.WARDROBE) MacroStateManager.setCurrentState(MacroState.State.FARMING)
        if (!shouldRestartFarmingAfterSwap) return
        shouldRestartFarmingAfterSwap = false
        if (PestManager.isCleaningInProgress()) { ClientUtils.sendMessage("§aLoadout swap finished. Cleaning in progress, skipping restart.", true); return }
        if (AutoPestExchangeManager.shouldBlockFarmingResume()) { ClientUtils.sendDebugMessage("Loadout completion deferred because pest exchange has priority."); AutoPestExchangeManager.tryTriggerPending(client); return }
        ClientUtils.sendMessage("§aLoadout swap finished. Restarting farming...", true); client.execute { GearManager.swapToFarmingTool(client) }
        MacroWorkerThread.getInstance().submit("LoadoutCompletion-Resume") {
            if (MacroWorkerThread.shouldAbortTask(client, MacroState.State.FARMING) || PestManager.isCleaningInProgress() || AutoPestExchangeManager.shouldBlockFarmingResume()) return@submit
            GearManager.finalResume(client)
        }
    }
    @JvmStatic fun forceLoadoutCompletionFailsafe(client: Minecraft) {
        if (!isSwappingLoadout || !shouldRestartFarmingAfterSwap) return
        ClientUtils.sendDebugMessage("Loadout swap failsafe triggered. Forcing completion."); trackedLoadoutSlot = targetLoadoutSlot; isSwappingLoadout = false; loadoutGuiDetected = false; loadoutInteractionStage = 0; loadoutOpenPendingTime = 0; loadoutFirstClickDelayMs = 0
        ClientUtils.closeGuiAsync(client).thenRun { loadoutGuiCloseComplete = true; handleLoadoutCompletion(client) }
    }
    private fun getLoadoutGuiSlot(slot: Int): Int { if (slot !in 1..12) return -1; return 14 + (slot - 1) / 3 * 9 + (slot - 1) % 3 }
    private fun sendTimedDebug(client: Minecraft, action: String, now: Long) { ClientUtils.sendDebugMessage("$action at ${ClientUtils.formatElapsedMs(loadoutTimelineStartTime, now)}.") }
}
