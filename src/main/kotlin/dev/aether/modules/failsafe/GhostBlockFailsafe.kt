package dev.aether.modules.failsafe

import dev.aether.config.AetherConfig
import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.macro.farming.FarmingMacroManager
import dev.aether.notification.NotificationManager
import dev.aether.util.NickHiderUtils
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

internal object GhostBlockFailsafe {
    private const val FARMING_TEXT_FRESHNESS_MS = 1500L
    enum class State { IDLE, WAIT, TRIGGERED }
    private var lastFarmingTextSeenMs = 0L
    private var missingSinceMs = 0L
    private var missingRandomDelayMs = 0L
    private var triggered = false

    fun reset() { lastFarmingTextSeenMs = 0L; missingSinceMs = 0L; missingRandomDelayMs = 0L; triggered = false }
    fun observeOverlayMessage(component: Component?) {
        if (component != null && NickHiderUtils.containsFarmingExpText(component.string)) lastFarmingTextSeenMs = System.currentTimeMillis()
    }
    fun tick(client: Minecraft?) {
        if (client?.player == null) { reset(); return }
        if (!AetherConfig.FAILSAFE_GHOST_BLOCK.get() || !isTrackingActive() || isFarmingTextVisible(client)) { resetWait(); return }
        if (missingSinceMs == 0L) { missingSinceMs = System.currentTimeMillis(); missingRandomDelayMs = FailsafeManager.sampleAdditionalTriggerDelayMs(); return }
        val elapsed = System.currentTimeMillis() - missingSinceMs
        val delay = getConfiguredWindowMs() + Math.round(AetherConfig.FAILSAFE_GHOST_BLOCK_TRIGGER_DELAY_SECONDS.get() * 1000.0f).toLong() + missingRandomDelayMs
        if (elapsed >= delay) trigger(client)
    }
    fun getState(client: Minecraft?): State {
        if (triggered) return State.TRIGGERED
        if (client?.player == null || !AetherConfig.FAILSAFE_GHOST_BLOCK.get() || !isTrackingActive()) return State.IDLE
        return if (isFarmingTextVisible(client)) State.IDLE else State.WAIT
    }
    fun getMissingWindowSeconds(): Double = if (missingSinceMs == 0L) 0.0 else maxOf(0.0, (System.currentTimeMillis() - missingSinceMs) / 1000.0)
    fun getTriggerRemainingMs(): Long {
        if (missingSinceMs == 0L) return 0L
        val delay = getConfiguredWindowMs() + Math.round(AetherConfig.FAILSAFE_GHOST_BLOCK_TRIGGER_DELAY_SECONDS.get() * 1000.0f).toLong() + missingRandomDelayMs
        return maxOf(0L, delay - (System.currentTimeMillis() - missingSinceMs))
    }
    fun isFarmingTextVisible(client: Minecraft?) = System.currentTimeMillis() - lastFarmingTextSeenMs <= FARMING_TEXT_FRESHNESS_MS
    private fun getConfiguredWindowMs() = AetherConfig.FAILSAFE_GHOST_BLOCK_WINDOW_SECONDS.get() * 1000L
    private fun isTrackingActive(): Boolean {
        if (!MacroStateManager.isMacroRunning() || MacroStateManager.getCurrentState() != MacroState.State.FARMING || !FarmingMacroManager.isActive()) return false
        val activeMacro = FarmingMacroManager.getActiveMacro()
        return activeMacro != null && activeMacro.isFarmingState()
    }
    private fun resetWait() { missingSinceMs = 0L; missingRandomDelayMs = 0L }
    private fun trigger(client: Minecraft) {
        if (triggered) return
        val action = FailsafeManager.getGhostBlockAction(); triggered = true
        NotificationManager.error(FailsafeManager.getNotificationTitle(action), "Farming EXP text disappeared during farming.")
        FailsafeManager.handleConfiguredAction(client, action, FailsafeCustomReplayManager.FailsafeReplayType.GHOST_BLOCK,
            "Farming EXP text disappeared during farming. Disable SBA Custom Skill Display if you think this is an error.",
            "GhostBlockFailsafe: farming EXP text disappeared during farming")
        reset()
    }
}
