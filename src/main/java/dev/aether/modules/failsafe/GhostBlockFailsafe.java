package dev.aether.modules.failsafe;

import dev.aether.config.AetherConfig;
import dev.aether.macro.FarmingMacroManager;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.notification.NotificationManager;
import dev.aether.util.NickHiderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

final class GhostBlockFailsafe {
    private static final long FARMING_TEXT_FRESHNESS_MS = 1500L;

    enum State {
        IDLE,
        WAIT,
        TRIGGERED
    }

    private static long lastFarmingTextSeenMs = 0L;
    private static long missingSinceMs = 0L;
    private static long missingRandomDelayMs = 0L;
    private static boolean triggered = false;

    private GhostBlockFailsafe() {}

    static void reset() {
        lastFarmingTextSeenMs = 0L;
        missingSinceMs = 0L;
        missingRandomDelayMs = 0L;
        triggered = false;
    }

    static void observeOverlayMessage(Component component) {
        if (component == null) {
            return;
        }

        if (NickHiderUtils.containsFarmingExpText(component.getString())) {
            lastFarmingTextSeenMs = System.currentTimeMillis();
        }
    }

    static void tick(Minecraft client) {
        if (client == null || client.player == null) {
            reset();
            return;
        }

        if (!AetherConfig.FAILSAFE_GHOST_BLOCK.get()) {
            resetWait();
            return;
        }

        if (!isTrackingActive()) {
            resetWait();
            return;
        }

        if (isFarmingTextVisible(client)) {
            resetWait();
            return;
        }

        if (missingSinceMs == 0L) {
            missingSinceMs = System.currentTimeMillis();
            missingRandomDelayMs = FailsafeManager.sampleAdditionalTriggerDelayMs();
            return;
        }

        long elapsedMs = System.currentTimeMillis() - missingSinceMs;
        long totalDelayMs = getConfiguredWindowMs()
                + Math.round(AetherConfig.FAILSAFE_GHOST_BLOCK_TRIGGER_DELAY_SECONDS.get() * 1000.0f)
                + missingRandomDelayMs;
        if (elapsedMs < totalDelayMs) {
            return;
        }

        trigger(client);
    }

    static State getState(Minecraft client) {
        if (triggered) {
            return State.TRIGGERED;
        }
        if (client == null || client.player == null || !AetherConfig.FAILSAFE_GHOST_BLOCK.get() || !isTrackingActive()) {
            return State.IDLE;
        }
        return isFarmingTextVisible(client) ? State.IDLE : State.WAIT;
    }

    static double getMissingWindowSeconds() {
        if (missingSinceMs == 0L) {
            return 0.0;
        }
        return Math.max(0.0, (System.currentTimeMillis() - missingSinceMs) / 1000.0);
    }

    static long getTriggerRemainingMs() {
        if (missingSinceMs == 0L) {
            return 0L;
        }
        long totalDelayMs = getConfiguredWindowMs()
                + Math.round(AetherConfig.FAILSAFE_GHOST_BLOCK_TRIGGER_DELAY_SECONDS.get() * 1000.0f)
                + missingRandomDelayMs;
        long elapsedMs = System.currentTimeMillis() - missingSinceMs;
        return Math.max(0L, totalDelayMs - elapsedMs);
    }

    static boolean isFarmingTextVisible(Minecraft client) {
        return System.currentTimeMillis() - lastFarmingTextSeenMs <= FARMING_TEXT_FRESHNESS_MS;
    }

    private static long getConfiguredWindowMs() {
        return AetherConfig.FAILSAFE_GHOST_BLOCK_WINDOW_SECONDS.get() * 1000L;
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

    private static void resetWait() {
        missingSinceMs = 0L;
        missingRandomDelayMs = 0L;
    }

    private static void trigger(Minecraft client) {
        if (triggered) {
            return;
        }

        FailsafeAction action = FailsafeManager.getGhostBlockAction();
        triggered = true;
        NotificationManager.error(
                FailsafeManager.getNotificationTitle(action),
                "Farming EXP text disappeared during farming.");
        FailsafeManager.handleConfiguredAction(
                client,
                action,
                FailsafeCustomReplayManager.FailsafeReplayType.GHOST_BLOCK,
                "Farming EXP text disappeared during farming. Disable SBA Custom Skill Display if you think this is an error.",
                "GhostBlockFailsafe: farming EXP text disappeared during farming");
        reset();
    }
}
