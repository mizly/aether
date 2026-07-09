package dev.aether.modules.session;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.macro.ReconnectScheduler;
import dev.aether.ui.DynamicRestScreen;
import dev.aether.ui.FunnyDynamicRestScreen;
import dev.aether.util.AetherLang;
import dev.aether.util.ClientUtils;
import dev.aether.util.CommandUtils;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Implements the Dynamic Rest feature.
 *
 * Flow:
 * 1. Timer counts down whenever the macro is enabled.
 * 2. When the timer expires, the rest sequence is queued immediately.
 * 3. The staged shutdown begins once the macro is back in FARMING.
 * 4. After the break, the existing reconnect recovery path warps back to the
 * Garden and restarts the script.
 */
public class DynamicRestManager {

    private record RestWindow(long durationMs, long triggerMs) {
    }

    private static boolean isEnabled() {
        return AetherConfig.DYNAMIC_REST_ENABLED.get();
    }

    /** Epoch-ms when the next rest should be triggered. 0 = not scheduled yet. */
    private static long nextRestTriggerMs = 0;

    /**
     * Total duration of the current scripting period (ms). Used for the progress
     * bar.
     */
    private static long scheduledDurationMs = 0;

    private static boolean restSequencePending = false;
    private static int restSequenceStage = 0;
    private static long nextStageActionTime = 0;
    private static long lastTimerUpdateMs = 0;
    private static CommandUtils.ChatWindow restSetSpawnWindow = null;
    private static boolean dailyThresholdTriggered = false;

    /**
     * Called when the macro starts (or after a recovery reconnect).
     * Schedules the next rest timer using the configured scripting time +/- offset.
     */
    public static void scheduleNextRest() {
        if (!isEnabled()) {
            reset();
            return;
        }

        if (AetherConfig.PERSIST_SESSION_TIMER.get() && nextRestTriggerMs != 0) {
            return;
        }

        RestWindow restWindow = createRestWindow(System.currentTimeMillis(), 0L);
        scheduledDurationMs = restWindow.durationMs();
        nextRestTriggerMs = restWindow.triggerMs();
        restSequencePending = false;
        restSequenceStage = 0;
        nextStageActionTime = 0;
        dailyThresholdTriggered = false;
        lastTimerUpdateMs = System.currentTimeMillis();
    }

    /**
     * Reapplies the current scripting settings to the active countdown while
     * preserving elapsed session time.
     */
    public static void refreshCurrentSession() {
        if (!isEnabled()) {
            reset();
            return;
        }

        if (nextRestTriggerMs == 0 || scheduledDurationMs <= 0 || restSequencePending) {
            return;
        }

        long now = System.currentTimeMillis();
        long elapsedMs = Math.max(0L, scheduledDurationMs - Math.max(0L, nextRestTriggerMs - now));
        RestWindow restWindow = createRestWindow(now, elapsedMs);
        scheduledDurationMs = restWindow.durationMs();
        nextRestTriggerMs = restWindow.triggerMs();
        lastTimerUpdateMs = now;
    }

    /** Clears the rest timer entirely (called when the macro is stopped manually). */
    public static void reset() {
        nextRestTriggerMs = 0;
        scheduledDurationMs = 0;
        restSequencePending = false;
        restSequenceStage = 0;
        nextStageActionTime = 0;
        lastTimerUpdateMs = 0;
        restSetSpawnWindow = null;
        dailyThresholdTriggered = false;
    }

    /** Returns true while a rest sequence is actively in progress. */
    public static boolean isRestPending() {
        return isEnabled() && restSequencePending;
    }

    /** Returns the scheduled rest trigger time (epoch ms), or 0 if not set. */
    public static long getNextRestTriggerMs() {
        return isEnabled() ? nextRestTriggerMs : 0;
    }

    /**
     * Returns the total scripting duration that was scheduled (ms), or 0 if not
     * set.
     */
    public static long getScheduledDurationMs() {
        return isEnabled() ? scheduledDurationMs : 0;
    }

    private static RestWindow createRestWindow(long nowMs, long elapsedMs) {
        long baseMs = AetherConfig.REST_SCRIPTING_TIME.get() * 60L * 1000L;
        long offsetMs = AetherConfig.REST_SCRIPTING_TIME_OFFSET.get() * 60L * 1000L;
        long randomOffsetMs = offsetMs > 0 ? (long) (new Random().nextDouble() * offsetMs) : 0;
        long durationMs = baseMs + randomOffsetMs;
        long remainingMs = Math.max(0L, durationMs - Math.max(0L, elapsedMs));
        return new RestWindow(durationMs, nowMs + remainingMs);
    }

    /**
     * Must be called every client END_CLIENT_TICK while player != null.
     * Handles both the countdown HUD and the shutdown sequence.
     */
    public static void update() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }

        if (!isEnabled()) {
            if (nextRestTriggerMs != 0 || scheduledDurationMs != 0 || restSequencePending) {
                reset();
            }
            return;
        }

        MacroState.State currentState = MacroStateManager.getCurrentState();
        boolean isFarming = currentState == MacroState.State.FARMING;
        boolean isMacroEnabled = currentState != MacroState.State.OFF;
        long now = System.currentTimeMillis();

        checkDailyThreshold(client);

        if (nextRestTriggerMs > 0 && !restSequencePending) {
            if (!isMacroEnabled) {
                if (lastTimerUpdateMs == 0) {
                    lastTimerUpdateMs = now;
                } else {
                    long pausedForMs = now - lastTimerUpdateMs;
                    if (pausedForMs > 0) {
                        nextRestTriggerMs += pausedForMs;
                    }
                    lastTimerUpdateMs = now;
                }
            } else {
                lastTimerUpdateMs = now;
                if (now >= nextRestTriggerMs) {
                    restSequencePending = true;
                    restSequenceStage = 0;
                    nextStageActionTime = now;
                    ClientUtils.sendMessage("\u00A7eDynamic Rest queued. Waiting for farming to resume before resting...",
                            false);
                }
            }
        }

        if (!restSequencePending) {
            return;
        }

        if (now < nextStageActionTime) {
            return;
        }

        switch (restSequenceStage) {
            case 0: {
                if (!isFarming) {
                    return;
                }

                ClientUtils.sendDebugMessage("Disabling farming macro: Initiating dynamic rest sequence");
                client.execute(() -> dev.aether.macro.FarmingMacroManager.disable(client));
                ClientUtils.forceReleaseKeys(client);
                ClientUtils.sendMessage(CommandUtils.shouldSkipSetSpawn()
                                ? "\u00A7eDynamic Rest: preparing disconnect..."
                                : "\u00A7eDynamic Rest: running /setspawn...",
                        false);
                restSetSpawnWindow = CommandUtils.beginChatWindow();
                dev.aether.util.CommandUtils.initiateSetSpawn(client);
                MacroStateManager.setCurrentState(MacroState.State.OFF);

                restSequenceStage = 1;
                nextStageActionTime = System.currentTimeMillis() + 3000;
                break;
            }
            case 1: {
                if (!dev.aether.util.CommandUtils.hasSpawnBeenSet(restSetSpawnWindow)
                        && System.currentTimeMillis() < nextStageActionTime) {
                    return;
                }

                long baseSecs = AetherConfig.REST_BREAK_TIME.get() * 60L;
                long offsetSecs = AetherConfig.REST_BREAK_TIME_OFFSET.get() * 60L;
                long randomOffsetSecs = offsetSecs > 0 ? (long) (new Random().nextDouble() * offsetSecs) : 0;
                long breakSeconds = baseSecs + randomOffsetSecs;

                ClientUtils.sendMessage(String.format(
                        "\u00A7eDynamic Rest: disconnecting. Reconnecting in %.1f minutes...",
                        (double) breakSeconds / 60.0), false);

                MacroStateManager.setIntentionalDisconnect(true);
                ReconnectScheduler.scheduleReconnect(breakSeconds, true);

                long durationMs = breakSeconds * 1000;
                long restEndTimeMs = System.currentTimeMillis() + durationMs;

                nextRestTriggerMs = 0;

                Screen restScreen = AetherConfig.FUNNY_DYNAMIC_REST.get()
                        ? new FunnyDynamicRestScreen(restEndTimeMs)
                        : new DynamicRestScreen(restEndTimeMs, durationMs);
                ClientUtils.disconnectWithScreen(
                        client,
                        restScreen,
                        net.minecraft.network.chat.Component.literal("Dynamic rest"));

                restSequenceStage = 2;
                nextStageActionTime = Long.MAX_VALUE;
                break;
            }
            default:
                break;
        }
    }

    private static void checkDailyThreshold(Minecraft client) {
        double thresholdHours = AetherConfig.DAILY_FARM_THRESHOLD_HOURS.get();
        if (thresholdHours <= 0.0) {
            dailyThresholdTriggered = false;
            return;
        }

        long thresholdMs = (long) (thresholdHours * 3_600_000.0);
        long todayMs = DailyFarmTimeTracker.getTodayMs();
        if (dailyThresholdTriggered) {
            if (todayMs < thresholdMs) {
                dailyThresholdTriggered = false;
            }
            return;
        }

        if (!MacroStateManager.isMacroRunning()) {
            return;
        }

        if (todayMs < thresholdMs) {
            return;
        }

        dailyThresholdTriggered = true;
        ClientUtils.sendMessage("\u00A7e" + String.format(
                AetherLang.localize("Daily farming threshold reached (%.2f hours). Stopping macro..."),
                thresholdHours), false);
        MacroStateManager.stopMacro(client, "Dynamic Rest: daily farming threshold reached");
        ReconnectScheduler.cancel();

        if (AetherConfig.CLOSE_GAME_ON_DAILY_THRESHOLD.get()) {
            MacroWorkerThread.getInstance().submit("DailyThreshold-CloseGame", () -> {
                MacroWorkerThread.sleep(1000L);
                client.execute(client::stop);
            });
        }
    }
}

