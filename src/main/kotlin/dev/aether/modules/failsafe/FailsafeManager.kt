package dev.aether.modules.failsafe

import dev.aether.config.AetherConfig
import dev.aether.macro.MacroStateManager
import dev.aether.modules.discord.DiscordRemoteControlManager
import dev.aether.modules.discord.DiscordStatusManager
import dev.aether.modules.pest.ManualPestManager
import dev.aether.util.AetherLang
import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.state.BlockState
import java.util.concurrent.ThreadLocalRandom

class FailsafeManager private constructor() {
    enum class InventorySlotState { IDLE, WAIT, TRIGGERED }
    enum class InventoryGuiState { IDLE, WAIT, TRIGGERED }
    enum class BpsState { IDLE, WAIT, TRIGGERED }
    enum class RotationState { IDLE, WAIT, TRIGGERED }
    enum class GhostBlockState { IDLE, WAIT, TRIGGERED }
    enum class DirtCheckState { IDLE, WAIT, TRIGGERED }

    companion object {
        @JvmStatic fun reset() { InventorySlotFailsafe.reset(); UnexpectedInventoryFailsafe.reset(); BpsFailsafe.reset(); GhostBlockFailsafe.reset(); DirtFailsafe.reset(); RotationFailsafe.reset(); FailsafeTestManager.reset() }
        @JvmStatic fun resetRuntimeState() { InventorySlotFailsafe.reset(); UnexpectedInventoryFailsafe.reset(); BpsFailsafe.reset(); GhostBlockFailsafe.reset(); DirtFailsafe.reset(); RotationFailsafe.reset() }
        @JvmStatic fun syncSelectedSlotFromClient(client: Minecraft) = InventorySlotFailsafe.syncSelectedSlotFromClient(client)
        @JvmStatic fun expectSelectedHotbarSlot(slot: Int) = InventorySlotFailsafe.expectSelectedHotbarSlot(slot)
        @JvmStatic fun selectHotbarSlot(client: Minecraft, slot: Int) = InventorySlotFailsafe.selectHotbarSlot(client, slot)
        @JvmStatic fun tick(client: Minecraft) {
            if (shouldSuspendRuntimeFailsafes()) { resetRuntimeState(); return }
            FailsafeTestManager.tick(client); InventorySlotFailsafe.tick(client); UnexpectedInventoryFailsafe.tick(client)
            BpsFailsafe.tick(client); GhostBlockFailsafe.tick(client); DirtFailsafe.tick(client); RotationFailsafe.tick(client)
        }
        @JvmStatic fun onBlockBreak() { if (!shouldSuspendRuntimeFailsafes()) BpsFailsafe.onBlockBreak() }
        @JvmStatic fun onBlockBreak(pos: BlockPos) { if (shouldSuspendRuntimeFailsafes()) return; BpsFailsafe.onBlockBreak(); DirtFailsafe.onBlockBreak(pos) }
        @JvmStatic fun onBlockChanged(client: Minecraft, pos: BlockPos, oldState: BlockState, newState: BlockState) { if (!shouldSuspendRuntimeFailsafes()) DirtFailsafe.onBlockChanged(client, pos, oldState, newState) }
        @JvmStatic fun expectRotation(yaw: Float, pitch: Float) = RotationFailsafe.expectRotation(yaw, pitch)
        @JvmStatic fun syncExpectedRotationFromClient(client: Minecraft) = RotationFailsafe.syncExpectedRotationFromClient(client)
        @JvmStatic fun reportExternalRotation() = RotationFailsafe.reportExternalRotation()
        @JvmStatic fun addRotationGracePeriod(durationMs: Long) = RotationFailsafe.addGracePeriod(durationMs)
        @JvmStatic fun getExpectedSelectedSlot() = InventorySlotFailsafe.getExpectedSelectedSlot()
        @JvmStatic fun getCurrentSelectedSlot(client: Minecraft) = InventorySlotFailsafe.getCurrentSelectedSlot(client)
        @JvmStatic fun getInventorySlotState(client: Minecraft): InventorySlotState = InventorySlotState.valueOf(InventorySlotFailsafe.getState(client).name)
        @JvmStatic fun getInventorySlotTriggerRemainingMs() = InventorySlotFailsafe.getTriggerRemainingMs()
        @JvmStatic fun getInventoryGuiState(client: Minecraft): InventoryGuiState = InventoryGuiState.valueOf(UnexpectedInventoryFailsafe.getState(client).name)
        @JvmStatic fun getInventoryGuiTriggerRemainingMs() = UnexpectedInventoryFailsafe.getTriggerRemainingMs()
        @JvmStatic fun getBpsState(client: Minecraft): BpsState = BpsState.valueOf(BpsFailsafe.getState(client).name)
        @JvmStatic fun getBpsBreakCount() = BpsFailsafe.getBreakCount()
        @JvmStatic fun getBpsWindowSeconds() = BpsFailsafe.getWindowSeconds()
        @JvmStatic fun getCurrentBps() = BpsFailsafe.getCurrentBps()
        @JvmStatic fun getExpectedBps() = AetherConfig.FAILSAFE_BPS_THRESHOLD.get()
        @JvmStatic fun getBpsTriggerRemainingMs() = BpsFailsafe.getTriggerRemainingMs()
        @JvmStatic fun getGhostBlockState(client: Minecraft): GhostBlockState = GhostBlockState.valueOf(GhostBlockFailsafe.getState(client).name)
        @JvmStatic fun getGhostBlockWindowSeconds() = GhostBlockFailsafe.getMissingWindowSeconds()
        @JvmStatic fun getExpectedGhostBlockWindowSeconds() = AetherConfig.FAILSAFE_GHOST_BLOCK_WINDOW_SECONDS.get()
        @JvmStatic fun isGhostBlockFarmingTextVisible(client: Minecraft) = GhostBlockFailsafe.isFarmingTextVisible(client)
        @JvmStatic fun getGhostBlockTriggerRemainingMs() = GhostBlockFailsafe.getTriggerRemainingMs()
        @JvmStatic fun getDirtCheckState(client: Minecraft): DirtCheckState = DirtCheckState.valueOf(DirtFailsafe.getState(client).name)
        @JvmStatic fun isTouchingDirtBlock(client: Minecraft) = DirtFailsafe.isTouchingDirtBlock(client)
        @JvmStatic fun getDirtCheckTrackedBlockCount(client: Minecraft) = DirtFailsafe.getTrackedBlockCount(client)
        @JvmStatic fun getDirtCheckTriggerRemainingMs() = DirtFailsafe.getTriggerRemainingMs()
        @JvmStatic fun observeGhostBlockOverlayMessage(component: Component) = GhostBlockFailsafe.observeOverlayMessage(component)
        @JvmStatic fun getRotationState(client: Minecraft): RotationState = RotationState.valueOf(RotationFailsafe.getState(client).name)
        @JvmStatic fun getExpectedYaw() = RotationFailsafe.getExpectedYaw()
        @JvmStatic fun getExpectedPitch() = RotationFailsafe.getExpectedPitch()
        @JvmStatic fun getRotationTriggerRemainingMs() = RotationFailsafe.getTriggerRemainingMs()
        @JvmStatic fun shouldSuppressPestCleanerRotation(client: Minecraft) = RotationFailsafe.shouldSuppressPestCleanerRotation(client)
        @JvmStatic fun sampleAdditionalTriggerDelayMs(): Long {
            val maxAdditionalDelaySeconds = AetherConfig.FAILSAFE_ADDITIONAL_RANDOM_DELAY_SECONDS.get()
            if (maxAdditionalDelaySeconds <= 0.0f) return 0L
            val maxAdditionalDelayMs = Math.round(maxAdditionalDelaySeconds * 1000.0f)
            return ThreadLocalRandom.current().nextLong(maxAdditionalDelayMs + 1L)
        }
        private fun shouldSuspendRuntimeFailsafes() = ManualPestManager.isActive()
        @JvmStatic fun onFailsafeTriggered(action: FailsafeAction) = onFailsafeTriggered(action, "")
        @JvmStatic fun onFailsafeTriggered(action: FailsafeAction, details: String) { FailsafeTriggerHistory.recordTrigger(details); FailsafeColourFlashManager.trigger(); FailsafeSoundManager.playConfiguredSound(action); FailsafeWindowFocusManager.bringWindowToFront() }
        @JvmStatic fun getInventorySlotChangedAction() = FailsafeAction.fromConfig(AetherConfig.FAILSAFE_INVENTORY_SLOT_CHANGED_ACTION.get())
        @JvmStatic fun getUnexpectedInventoryGuiAction() = FailsafeAction.fromConfig(AetherConfig.FAILSAFE_UNEXPECTED_INVENTORY_GUI_ACTION.get())
        @JvmStatic fun getBpsAction() = FailsafeAction.fromConfig(AetherConfig.FAILSAFE_BPS_ACTION.get())
        @JvmStatic fun getGhostBlockAction() = FailsafeAction.fromConfig(AetherConfig.FAILSAFE_GHOST_BLOCK_ACTION.get())
        @JvmStatic fun getDirtCheckAction() = FailsafeAction.fromConfig(AetherConfig.FAILSAFE_DIRT_CHECK_ACTION.get())
        @JvmStatic fun getRotationAction(pestCleanerRotation: Boolean) = FailsafeAction.fromConfig(if (pestCleanerRotation) AetherConfig.FAILSAFE_PEST_ROTATION_ACTION.get() else AetherConfig.FAILSAFE_ROTATION_ACTION.get())
        @JvmStatic fun getWorldChangeAction() = FailsafeAction.fromConfig(AetherConfig.FAILSAFE_WORLD_CHANGE_ACTION.get())
        @JvmStatic fun shouldStopMacroOnTrigger(action: FailsafeAction) = action == FailsafeAction.STOP
        @JvmStatic fun getNotificationTitle(action: FailsafeAction) = if (shouldStopMacroOnTrigger(action)) "Failsafe Triggered" else "Failsafe Detected"
        @JvmStatic fun handleConfiguredAction(client: Minecraft, action: FailsafeAction, details: String, debugReason: String) { handleConfiguredAction(client, action, null, details, debugReason) }
        @JvmStatic fun handleConfiguredAction(client: Minecraft, action: FailsafeAction, replayType: FailsafeCustomReplayManager.FailsafeReplayType?, details: String, debugReason: String) { handleConfiguredAction(client, action, replayType, details, debugReason, getDefaultActionDone(action)) }
        @JvmStatic fun handleConfiguredAction(client: Minecraft, action: FailsafeAction, replayType: FailsafeCustomReplayManager.FailsafeReplayType?, details: String, debugReason: String, actionDone: String) {
            onFailsafeTriggered(action, details)
            if (AetherConfig.FAILSAFE_DESKTOP_NOTIFICATION_ENABLED.get()) DesktopNotificationManager.notify(getNotificationTitle(action), (details + " " + actionDone).trim(), shouldStopMacroOnTrigger(action))
            DiscordStatusManager.sendFailsafeAlert(details, actionDone); DiscordRemoteControlManager.sendFailsafeAlert(details, actionDone)
            if (shouldStopMacroOnTrigger(action)) { ClientUtils.sendMessage("\u00A7cFailsafe triggered: " + details + " Macro stopped.", false); MacroStateManager.stopMacro(client, debugReason, false); return }
            if (action == FailsafeAction.CUSTOM) { ClientUtils.sendMessage("\u00A7eFailsafe triggered: " + details + " " + AetherLang.localize("Running custom replay."), false); FailsafeCustomReplayManager.playConfiguredReplay(client, replayType); ClientUtils.sendDebugMessage(debugReason + " (custom replay)"); return }
            ClientUtils.sendMessage("\u00A7eFailsafe detected (ignored): " + details, false); ClientUtils.sendDebugMessage(debugReason + " (ignored)")
        }
        private fun getDefaultActionDone(action: FailsafeAction) = when (action) { FailsafeAction.STOP -> "Macro stopped."; FailsafeAction.CUSTOM -> "Custom replay started."; FailsafeAction.IGNORE -> "Ignored." }
        @Deprecated("Use an explicit action") @JvmStatic fun getConfiguredAction() = FailsafeAction.fromConfig(AetherConfig.FAILSAFE_ACTION.get())
        @Deprecated("Use an explicit action") @JvmStatic fun shouldStopMacroOnTrigger() = getConfiguredAction() == FailsafeAction.STOP
        @Deprecated("Use an explicit action") @JvmStatic fun handleConfiguredAction(client: Minecraft, details: String, debugReason: String) = handleConfiguredAction(client, getConfiguredAction(), details, debugReason)
    }
}
