package dev.aether.modules.failsafe

import dev.aether.config.AetherConfig
import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.macro.farming.FarmingMacroManager
import dev.aether.modules.pest.helpers.PestOnTheTrackManager
import dev.aether.notification.NotificationManager
import net.minecraft.client.Minecraft
import java.util.ArrayDeque
import java.util.Locale

internal object BpsFailsafe {
    enum class State { IDLE, WAIT, TRIGGERED }
    private val breakTimes = ArrayDeque<Long>()
    private var farmingClockMs = 0L
    private var lowBpsSince = 0L
    private var lowBpsRandomDelayMs = 0L
    private var triggered = false

    fun reset() {
        synchronized(breakTimes) { breakTimes.clear(); farmingClockMs = 0L }
        lowBpsSince = 0L; lowBpsRandomDelayMs = 0L; triggered = false
    }
    fun onBlockBreak() {
        if (!isTrackingActive()) return
        synchronized(breakTimes) { breakTimes.addLast(farmingClockMs); cleanup() }
    }
    fun tick(client: Minecraft?) {
        if (client?.player == null) { reset(); return }
        if (!AetherConfig.FAILSAFE_BPS.get() || !isTrackingActive()) { resetLowBpsWait(); return }
        synchronized(breakTimes) { farmingClockMs += 50L; cleanup() }
        if (getWindowSeconds() < getConfiguredWindowSeconds()) { resetLowBpsWait(); return }
        val currentBps = getCurrentBps()
        val expectedBps = AetherConfig.FAILSAFE_BPS_THRESHOLD.get().toDouble()
        if (currentBps >= expectedBps) { resetLowBpsWait(); return }
        if (lowBpsSince == 0L) {
            lowBpsSince = System.currentTimeMillis(); lowBpsRandomDelayMs = FailsafeManager.sampleAdditionalTriggerDelayMs(); return
        }
        val triggerDelayMs = Math.round(AetherConfig.FAILSAFE_BPS_TRIGGER_DELAY_SECONDS.get() * 1000.0f).toLong() + lowBpsRandomDelayMs
        if (System.currentTimeMillis() - lowBpsSince >= triggerDelayMs) trigger(client, currentBps, expectedBps)
    }
    fun getState(client: Minecraft?): State {
        if (triggered) return State.TRIGGERED
        if (client?.player == null || !AetherConfig.FAILSAFE_BPS.get() || !isTrackingActive()) return State.IDLE
        return if (getWindowSeconds() < getConfiguredWindowSeconds() || getCurrentBps() < AetherConfig.FAILSAFE_BPS_THRESHOLD.get().toDouble()) State.WAIT else State.IDLE
    }
    fun getBreakCount(): Int = synchronized(breakTimes) { cleanup(); breakTimes.size }
    fun getWindowSeconds(): Double = synchronized(breakTimes) { maxOf(0.1, minOf(getConfiguredWindowSeconds(), farmingClockMs / 1000.0)) }
    fun getCurrentBps(): Double = synchronized(breakTimes) { cleanup(); val seconds = getWindowSeconds(); if (seconds <= 0.0) 0.0 else breakTimes.size / seconds }
    fun getTriggerRemainingMs(): Long {
        if (lowBpsSince == 0L) return 0L
        val elapsed = System.currentTimeMillis() - lowBpsSince
        val delay = Math.round(AetherConfig.FAILSAFE_BPS_TRIGGER_DELAY_SECONDS.get() * 1000.0f).toLong() + lowBpsRandomDelayMs
        return maxOf(0L, delay - elapsed)
    }
    private fun cleanup() {
        val windowMs = getConfiguredWindowMs()
        while (breakTimes.isNotEmpty() && farmingClockMs - breakTimes.peekFirst() > windowMs) breakTimes.pollFirst()
    }
    private fun getConfiguredWindowMs() = AetherConfig.FAILSAFE_BPS_WINDOW_SECONDS.get() * 1000L
    private fun getConfiguredWindowSeconds() = AetherConfig.FAILSAFE_BPS_WINDOW_SECONDS.get().toDouble()
    private fun isTrackingActive(): Boolean {
        if (!MacroStateManager.isMacroRunning() || MacroStateManager.getCurrentState() != MacroState.State.FARMING || !FarmingMacroManager.isActive()) return false
        if (PestOnTheTrackManager.getInstance().isBlockingFarming()) return false
        val activeMacro = FarmingMacroManager.getActiveMacro()
        return activeMacro != null && activeMacro.isFarmingState()
    }
    private fun resetLowBpsWait() { lowBpsSince = 0L; lowBpsRandomDelayMs = 0L }
    private fun trigger(client: Minecraft, currentBps: Double, expectedBps: Double) {
        if (triggered) return
        val action = FailsafeManager.getBpsAction(); triggered = true
        NotificationManager.error(FailsafeManager.getNotificationTitle(action), "BPS dropped below the configured threshold.")
        FailsafeManager.handleConfiguredAction(client, action, FailsafeCustomReplayManager.FailsafeReplayType.BPS,
            String.format(Locale.US, "BPS too low. Current %.2f, expected >= %.2f.", currentBps, expectedBps),
            String.format(Locale.US, "BpsFailsafe: BPS too low (current %.2f, expected >= %.2f)", currentBps, expectedBps))
        reset()
    }
}
