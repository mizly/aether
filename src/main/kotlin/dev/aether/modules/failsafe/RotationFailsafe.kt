package dev.aether.modules.failsafe

import dev.aether.config.AetherConfig
import dev.aether.macro.MacroState
import dev.aether.macro.MacroStateManager
import dev.aether.modules.pest.helpers.PestDestroyer
import dev.aether.modules.rotation.RotationManager
import dev.aether.modules.session.RestartManager
import dev.aether.notification.NotificationManager
import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import java.util.Locale

internal object RotationFailsafe {
    enum class State { IDLE, WAIT, TRIGGERED }

    @Volatile private var expectedRotationSet = false
    @Volatile private var expectedYaw = 0.0f
    @Volatile private var expectedPitch = 0.0f
    @Volatile private var graceUntilMs = 0L
    @Volatile private var mismatchSince = 0L
    @Volatile private var mismatchRandomDelayMs = 0L
    @Volatile private var triggered = false

    fun reset() {
        expectedRotationSet = false; expectedYaw = 0.0f; expectedPitch = 0.0f
        graceUntilMs = 0L; mismatchSince = 0L; mismatchRandomDelayMs = 0L; triggered = false
    }
    fun expectRotation(yaw: Float, pitch: Float) {
        expectedRotationSet = true
        expectedYaw = Mth.wrapDegrees(yaw)
        expectedPitch = Mth.clamp(pitch, -90.0f, 90.0f)
        mismatchSince = 0L; mismatchRandomDelayMs = 0L
    }
    fun syncExpectedRotationFromClient(client: Minecraft?) {
        val player = client?.player ?: return
        expectRotation(player.getYRot(), player.getXRot())
        triggered = false
    }
    fun reportExternalRotation() {
        if (mismatchSince != 0L) return
        mismatchSince = System.currentTimeMillis()
        mismatchRandomDelayMs = FailsafeManager.sampleAdditionalTriggerDelayMs()
    }
    fun addGracePeriod(durationMs: Long) {
        if (durationMs <= 0L) return
        graceUntilMs = maxOf(graceUntilMs, System.currentTimeMillis() + durationMs)
        mismatchSince = 0L; mismatchRandomDelayMs = 0L
    }
    fun tick(client: Minecraft?) {
        val player = client?.player ?: run { reset(); return }
        val state = MacroStateManager.getCurrentState()
        if (state == MacroState.State.OFF || state == MacroState.State.RECOVERING) {
            syncExpectedRotationFromClient(client); mismatchSince = 0L; mismatchRandomDelayMs = 0L; return
        }
        if (RestartManager.isRestartSequenceActive()) {
            syncExpectedRotationFromClient(client); mismatchSince = 0L; mismatchRandomDelayMs = 0L; return
        }
        if (!AetherConfig.FAILSAFE_ROTATION.get()) {
            mismatchSince = 0L; mismatchRandomDelayMs = 0L
            if (!expectedRotationSet) syncExpectedRotationFromClient(client)
            return
        }
        if (System.currentTimeMillis() < graceUntilMs) {
            syncExpectedRotationFromClient(client); mismatchSince = 0L; mismatchRandomDelayMs = 0L; return
        }
        if (isPestCleanerActive() && !AetherConfig.FAILSAFE_ROTATION_TRIGGER_DURING_PEST_CLEANER.get()) {
            syncExpectedRotationFromClient(client); mismatchSince = 0L; mismatchRandomDelayMs = 0L; return
        }
        if (!expectedRotationSet) { syncExpectedRotationFromClient(client); return }
        val currentYaw = Mth.wrapDegrees(player.getYRot())
        val currentPitch = Mth.clamp(player.getXRot(), -90.0f, 90.0f)
        val yawDiff = kotlin.math.abs(Mth.wrapDegrees(currentYaw - expectedYaw))
        val pitchDiff = kotlin.math.abs(currentPitch - expectedPitch)
        if (yawDiff <= AetherConfig.FAILSAFE_ROTATION_YAW_THRESHOLD.get() && pitchDiff <= AetherConfig.FAILSAFE_ROTATION_PITCH_THRESHOLD.get()) {
            mismatchSince = 0L; mismatchRandomDelayMs = 0L; return
        }
        if (mismatchSince == 0L) {
            mismatchSince = System.currentTimeMillis(); mismatchRandomDelayMs = FailsafeManager.sampleAdditionalTriggerDelayMs()
            if (shouldSuppressPestCleanerRotation(client)) RotationManager.cancelRotation()
            return
        }
        if (shouldSuppressPestCleanerRotation(client)) RotationManager.cancelRotation()
        if (System.currentTimeMillis() - mismatchSince >= getConfiguredTriggerDelayMs() + mismatchRandomDelayMs) trigger(client, currentYaw, currentPitch, yawDiff, pitchDiff)
    }
    fun getState(client: Minecraft?): State {
        if (triggered) return State.TRIGGERED
        val state = MacroStateManager.getCurrentState()
        val player = client?.player
        if (player == null || state == MacroState.State.OFF || state == MacroState.State.RECOVERING || RestartManager.isRestartSequenceActive() || !AetherConfig.FAILSAFE_ROTATION.get() || !expectedRotationSet || System.currentTimeMillis() < graceUntilMs) return State.IDLE
        if (isPestCleanerActive() && !AetherConfig.FAILSAFE_ROTATION_TRIGGER_DURING_PEST_CLEANER.get()) return State.IDLE
        val yawDiff = kotlin.math.abs(Mth.wrapDegrees(Mth.wrapDegrees(player.getYRot()) - expectedYaw))
        val pitchDiff = kotlin.math.abs(Mth.clamp(player.getXRot(), -90.0f, 90.0f) - expectedPitch)
        return if (yawDiff > AetherConfig.FAILSAFE_ROTATION_YAW_THRESHOLD.get() || pitchDiff > AetherConfig.FAILSAFE_ROTATION_PITCH_THRESHOLD.get()) State.WAIT else State.IDLE
    }
    fun getExpectedYaw() = expectedYaw
    fun getExpectedPitch() = expectedPitch
    fun getTriggerRemainingMs(): Long {
        if (mismatchSince == 0L) return 0L
        return maxOf(0L, getConfiguredTriggerDelayMs() + mismatchRandomDelayMs - (System.currentTimeMillis() - mismatchSince))
    }
    fun shouldSuppressPestCleanerRotation(client: Minecraft?) = client != null && mismatchSince != 0L && getState(client) == State.WAIT && isPestCleanerActive() && AetherConfig.FAILSAFE_ROTATION_TRIGGER_DURING_PEST_CLEANER.get()
    private fun getConfiguredTriggerDelayMs(): Long = if (isPestCleanerActive() && AetherConfig.FAILSAFE_ROTATION_TRIGGER_DURING_PEST_CLEANER.get()) AetherConfig.FAILSAFE_ROTATION_PEST_CLEANER_DELAY_MS.get().toLong() else Math.round(AetherConfig.FAILSAFE_ROTATION_TRIGGER_DELAY_SECONDS.get() * 1000.0f).toLong()
    private fun isPestCleanerActive() = PestDestroyer.isActive()
    private fun trigger(client: Minecraft, currentYaw: Float, currentPitch: Float, yawDiff: Float, pitchDiff: Float) {
        if (triggered) return
        val pestCleanerRotation = isPestCleanerActive()
        val action = FailsafeManager.getRotationAction(pestCleanerRotation)
        triggered = true
        NotificationManager.error(FailsafeManager.getNotificationTitle(action), "Player rotation changed unexpectedly.")
        val replayType = if (pestCleanerRotation) FailsafeCustomReplayManager.FailsafeReplayType.PEST_ROTATION else FailsafeCustomReplayManager.FailsafeReplayType.ROTATION
        FailsafeManager.handleConfiguredAction(client, action, replayType,
            String.format(Locale.US, "unexpected rotation. Current yaw/pitch %.1f / %.1f, expected %.1f / %.1f, diff %.1f / %.1f.", currentYaw, currentPitch, expectedYaw, expectedPitch, yawDiff, pitchDiff),
            String.format(Locale.US, "RotationFailsafe: unexpected rotation (current %.1f / %.1f, expected %.1f / %.1f, diff %.1f / %.1f)", currentYaw, currentPitch, expectedYaw, expectedPitch, yawDiff, pitchDiff))
        reset()
    }
}
