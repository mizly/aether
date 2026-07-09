package dev.aether.modules.failsafe;

import dev.aether.config.AetherConfig;
import dev.aether.macro.FarmingMacroManager;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.notification.NotificationManager;
import net.minecraft.client.Minecraft;

import java.util.ArrayDeque;
import java.util.Deque;

final class BpsFailsafe {
    enum State {
        IDLE,
        WAIT,
        TRIGGERED
    }

    private static final Deque<Long> breakTimes = new ArrayDeque<>();
    private static long farmingClockMs = 0L;
    private static long lowBpsSince = 0L;
    private static long lowBpsRandomDelayMs = 0L;
    private static boolean triggered = false;

    private BpsFailsafe() {}

    static void reset() {
        synchronized (breakTimes) {
            breakTimes.clear();
            farmingClockMs = 0L;
        }
        lowBpsSince = 0L;
        lowBpsRandomDelayMs = 0L;
        triggered = false;
    }

    static void onBlockBreak() {
        if (!isTrackingActive()) return;

        synchronized (breakTimes) {
            breakTimes.addLast(farmingClockMs);
            cleanup();
        }
    }

    static void tick(Minecraft client) {
        if (client == null || client.player == null) {
            reset();
            return;
        }

        if (!AetherConfig.FAILSAFE_BPS.get()) {
            resetLowBpsWait();
            return;
        }

        if (!isTrackingActive()) {
            resetLowBpsWait();
            return;
        }

        synchronized (breakTimes) {
            farmingClockMs += 50L;
            cleanup();
        }

        if (getWindowSeconds() < getConfiguredWindowSeconds()) {
            resetLowBpsWait();
            return;
        }

        double currentBps = getCurrentBps();
        double expectedBps = AetherConfig.FAILSAFE_BPS_THRESHOLD.get();
        if (currentBps >= expectedBps) {
            resetLowBpsWait();
            return;
        }

        if (lowBpsSince == 0L) {
            lowBpsSince = System.currentTimeMillis();
            lowBpsRandomDelayMs = FailsafeManager.sampleAdditionalTriggerDelayMs();
            return;
        }

        long triggerDelayMs = Math.round(AetherConfig.FAILSAFE_BPS_TRIGGER_DELAY_SECONDS.get() * 1000.0f)
                + lowBpsRandomDelayMs;
        if (System.currentTimeMillis() - lowBpsSince < triggerDelayMs) {
            return;
        }

        trigger(client, currentBps, expectedBps);
    }

    static State getState(Minecraft client) {
        if (triggered) {
            return State.TRIGGERED;
        }
        if (client == null || client.player == null || !AetherConfig.FAILSAFE_BPS.get() || !isTrackingActive()) {
            return State.IDLE;
        }
        if (getWindowSeconds() < getConfiguredWindowSeconds()) {
            return State.WAIT;
        }
        if (getCurrentBps() < AetherConfig.FAILSAFE_BPS_THRESHOLD.get()) {
            return State.WAIT;
        }
        return State.IDLE;
    }

    static int getBreakCount() {
        synchronized (breakTimes) {
            cleanup();
            return breakTimes.size();
        }
    }

    static double getWindowSeconds() {
        synchronized (breakTimes) {
            return Math.max(0.1, Math.min(getConfiguredWindowSeconds(), farmingClockMs / 1000.0));
        }
    }

    static double getCurrentBps() {
        synchronized (breakTimes) {
            cleanup();
            double seconds = getWindowSeconds();
            return seconds <= 0.0 ? 0.0 : breakTimes.size() / seconds;
        }
    }

    static long getTriggerRemainingMs() {
        if (lowBpsSince == 0L) {
            return 0L;
        }
        long elapsedMs = System.currentTimeMillis() - lowBpsSince;
        long triggerDelayMs = Math.round(AetherConfig.FAILSAFE_BPS_TRIGGER_DELAY_SECONDS.get() * 1000.0f)
                + lowBpsRandomDelayMs;
        return Math.max(0L, triggerDelayMs - elapsedMs);
    }

    private static void cleanup() {
        long windowMs = getConfiguredWindowMs();
        while (!breakTimes.isEmpty() && farmingClockMs - breakTimes.peekFirst() > windowMs) {
            breakTimes.pollFirst();
        }
    }

    private static long getConfiguredWindowMs() {
        return AetherConfig.FAILSAFE_BPS_WINDOW_SECONDS.get() * 1000L;
    }

    private static double getConfiguredWindowSeconds() {
        return AetherConfig.FAILSAFE_BPS_WINDOW_SECONDS.get();
    }

    private static boolean isTrackingActive() {
        if (!MacroStateManager.isMacroRunning() || MacroStateManager.getCurrentState() != MacroState.State.FARMING) {
            return false;
        }

        if (!FarmingMacroManager.isActive()) {
            return false;
        }

        var activeMacro = FarmingMacroManager.getActiveMacro();
        return activeMacro != null && activeMacro.isFarmingState();
    }

    private static void resetLowBpsWait() {
        lowBpsSince = 0L;
        lowBpsRandomDelayMs = 0L;
    }

    private static void trigger(Minecraft client, double currentBps, double expectedBps) {
        if (triggered) {
            return;
        }

        FailsafeAction action = FailsafeManager.getBpsAction();
        triggered = true;
        NotificationManager.error(
                FailsafeManager.getNotificationTitle(action),
                "BPS dropped below the configured threshold.");
        FailsafeManager.handleConfiguredAction(
                client,
                action,
                FailsafeCustomReplayManager.FailsafeReplayType.BPS,
                String.format(java.util.Locale.US,
                        "BPS too low. Current %.2f, expected >= %.2f.",
                        currentBps, expectedBps),
                String.format(java.util.Locale.US,
                        "BpsFailsafe: BPS too low (current %.2f, expected >= %.2f)",
                        currentBps, expectedBps));
        reset();
    }
}
