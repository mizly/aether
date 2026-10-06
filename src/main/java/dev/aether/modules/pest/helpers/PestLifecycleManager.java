package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.macro.farming.FarmingMacroManager;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.gear.helpers.LoadoutManager;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.pest.ManualPestManager;
import dev.aether.modules.pest.PestManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.CommandUtils;
import net.minecraft.client.Minecraft;

// keeps setup and teardown off the implementation that actually clears the pests
public final class PestLifecycleManager {

    public enum Stage {
        IDLE,
        PRE,
        CLEANING,
        POST
    }

    private static volatile Stage stage = Stage.IDLE;
    private static volatile boolean sunsetPestsRestoreNight = false;
    private static volatile boolean farmingHeld = false;
    private static volatile long detectionHoldUntilMs = 0L;
    static final long DETECTION_HOLD_MS = 15_000L;
    static final long TAB_CATCHUP_HOLD_MS = 5_000L;
    private static final long SETSPAWN_RETRY_MS = 1_000L;
    static final int SETSPAWN_MAX_ATTEMPTS = 2;
    static final long SETSPAWN_FAILURE_RETRY_MS = 3_000L;
    static final int SETSPAWN_FAILURES_BEFORE_FULL_COOLDOWN = 3;
    private static volatile int consecutiveSetSpawnFailures = 0;

    private PestLifecycleManager() {
    }

    public static Stage getStage() {
        return stage;
    }

    public static void reset() {
        stage = Stage.IDLE;
        sunsetPestsRestoreNight = false;
        farmingHeld = false;
        detectionHoldUntilMs = 0L;
        consecutiveSetSpawnFailures = 0;
    }

    // POST is excluded so the return path can restart farming itself
    public static boolean isHoldingFarming() {
        return isHoldingFarming(stage, farmingHeld) || isDetectionHoldActive();
    }

    static boolean isHoldingFarming(Stage currentStage, boolean held) {
        return currentStage == Stage.CLEANING || currentStage == Stage.PRE && held;
    }

    public static boolean blocksFarmingResume() {
        return PestManager.isCleaningInProgress() || stage != Stage.IDLE || isDetectionHoldActive();
    }

    private static boolean isDetectionHoldActive() {
        return stage == Stage.IDLE && System.currentTimeMillis() < detectionHoldUntilMs;
    }

    // the spawn message lands before the trigger delay and any loadout swap, which would otherwise resume farming
    // the backstop outlasts the trigger delay so a long ballsack wait cannot resume farming midway
    public static void holdFarmingForDetectedPests(Minecraft client, long triggerDelayMs) {
        if (!AetherConfig.PEST_STOP_FARMING_ON_DETECT.get() || stage != Stage.IDLE) {
            return;
        }
        detectionHoldUntilMs = System.currentTimeMillis() + Math.max(0L, triggerDelayMs) + DETECTION_HOLD_MS;
        Runnable stop = () -> FarmingMacroManager.disable(client, "PestLifecycleManager(stop on pest detect)");
        if (client.isSameThread()) {
            stop.run();
        } else {
            client.execute(stop);
        }
    }

    public static void releaseDetectionHold(Minecraft client, String reason) {
        if (detectionHoldUntilMs == 0L) {
            return;
        }
        detectionHoldUntilMs = 0L;
        if (stage == Stage.IDLE) {
            ClientUtils.sendDebugMessage("Pest lifecycle: releasing stop-on-detect hold (" + reason + ").");
            resumeFarmingIfIdle(client);
        }
    }

    // the tab keeps the old alive count for a few seconds after a spawn and starts the cycle once it updates
    public static void waitForTabTrigger(String reason) {
        if (detectionHoldUntilMs == 0L || stage != Stage.IDLE) {
            return;
        }
        detectionHoldUntilMs = shortenedHoldUntil(detectionHoldUntilMs, System.currentTimeMillis());
        ClientUtils.sendDebugMessage("Pest lifecycle: " + reason + "; keeping farming stopped up to "
                + TAB_CATCHUP_HOLD_MS + "ms for the tab trigger.");
    }

    static long shortenedHoldUntil(long holdUntilMs, long now) {
        return Math.min(holdUntilMs, now + TAB_CATCHUP_HOLD_MS);
    }

    public static void tickDetectionHold(Minecraft client) {
        if (detectionHoldUntilMs != 0L && stage == Stage.IDLE && !isDetectionHoldActive()) {
            releaseDetectionHold(client, "no pest cycle started before the hold expired");
        }
    }

    public static boolean restorePendingSunsetPestsNight(Minecraft client) {
        if (!sunsetPestsRestoreNight) {
            return true;
        }

        sunsetPestsRestoreNight = false;
        boolean switched = GardenTimeManager.switchToNightTime(client);
        if (!switched) {
            ClientUtils.sendDebugMessage("Sunset Pests: failed to switch garden time to night.");
        }
        return switched;
    }

    static boolean prepareSunsetPestsDaytime(Minecraft client) {
        if (!AetherConfig.SUNSET_PESTS.get()) {
            return true;
        }

        sunsetPestsRestoreNight = true;
        boolean switched = GardenTimeManager.switchToDaytime(client);
        if (!switched) {
            ClientUtils.sendDebugMessage("Sunset Pests: failed to switch garden time to day.");
        }
        return switched;
    }

    public static boolean start(Minecraft client, String plot, int pestCount, int sessionId) {
        String rejection = startRejectionReason(stage, PestManager.isCleaningInProgress(),
                LoadoutManager.isSwappingLoadout);
        if (rejection != null) {
            ClientUtils.sendDebugMessage("Pest lifecycle: start rejected for plot " + plot + " (" + rejection + ").");
            PestManager.clearCleaningTriggerPending();
            return false;
        }

        boolean manualMode = AetherConfig.MANUAL_PEST_MODE.get();
        boolean stopFarming = AetherConfig.PEST_STOP_FARMING_ON_DETECT.get();
        stage = Stage.PRE;
        detectionHoldUntilMs = 0L;
        if (stopFarming) {
            ClientUtils.sendDebugMessage("Pest lifecycle: start accepted for plot " + plot + " (session "
                    + sessionId + "); farming stopped, sending /setspawn without waiting.");
            stopFarmingForPendingStart(client, sessionId);
            // the player is already standing still, and the server runs /setspawn before the next /loadout or /plottp
            CommandUtils.initiateSetSpawn();
            client.execute(() -> beginPreStage(client, plot, pestCount, sessionId, manualMode));
            return true;
        }
        ClientUtils.sendDebugMessage("Pest lifecycle: start accepted for plot " + plot + " (session " + sessionId
                + "); waiting for /setspawn while farming continues.");
        MacroWorkerThread.getInstance().submit("PestSetSpawn-" + plot, () -> {
            for (int attempt = 0; attempt < SETSPAWN_MAX_ATTEMPTS && isSetSpawnRetryPending(client, sessionId);
                    attempt++) {
                boolean confirmed = CommandUtils.setSpawn(SETSPAWN_RETRY_MS);
                ClientUtils.sendDebugMessage("Pest lifecycle: /setspawn attempt " + (attempt + 1) + "/"
                        + SETSPAWN_MAX_ATTEMPTS + (confirmed ? " confirmed." : " not confirmed."));
                if (confirmed) {
                    consecutiveSetSpawnFailures = 0;
                    client.execute(() -> beginPreStage(client, plot, pestCount, sessionId, manualMode));
                    return;
                }
                if (attempt + 1 < SETSPAWN_MAX_ATTEMPTS) {
                    ClientUtils.sendDebugMessage("Pest lifecycle: /setspawn not confirmed; farming and retrying once.");
                }
            }
            if (isSetSpawnRetryPending(client, sessionId)) {
                int failures = ++consecutiveSetSpawnFailures;
                long cooldownMs = setSpawnFailureCooldownMs(failures, PestManager.PEST_REENTRY_COOLDOWN_MS);
                ClientUtils.sendDebugMessage("Pest lifecycle: /setspawn failed twice (" + failures
                        + " in a row); continuing farming and retrying the trigger in " + cooldownMs + "ms.");
                PestManager.startPestReentryCooldown(cooldownMs);
            }
            cancelPendingStart(sessionId);
            resumeFarmingIfIdle(client);
        });
        return true;
    }

    static String startRejectionReason(Stage currentStage, boolean cleaning, boolean swappingLoadout) {
        if (currentStage != Stage.IDLE) {
            return "stage=" + currentStage;
        }
        if (cleaning) {
            return "cleaning in progress";
        }
        if (swappingLoadout) {
            return "loadout swap active";
        }
        return null;
    }

    private static void beginPreStage(Minecraft client, String plot, int pestCount, int sessionId,
            boolean manualMode) {
        if (!isSetSpawnRetryPending(client, sessionId)) {
            ClientUtils.sendDebugMessage("Pest lifecycle: start no longer pending after /setspawn (state="
                    + MacroStateManager.getCurrentState() + "); dropping cycle.");
            cancelPendingStart(sessionId);
            resumeFarmingIfIdle(client);
            return;
        }

        ClientUtils.sendDebugMessage("Pest lifecycle: /setspawn confirmed; entering PRE stage for plot " + plot + ".");
        farmingHeld = true;
        FarmingMacroManager.disable(client, "PestLifecycleManager.beginPreStage");
        PestManager.setCleaningInProgress(true);
        PestManager.clearCleaningTriggerPending();
        LoadoutManager.shouldRestartFarmingAfterSwap = false;
        MacroStateManager.setCurrentState(MacroState.State.CLEANING);

        MacroWorkerThread.getInstance().submit("PestPre-" + plot, () -> {
            try {
                PestPreStage.Result preResult =
                        PestPreStage.run(client, plot, pestCount, sessionId);
                if (!preResult.successful()) {
                    abortPreStage(client, sessionId, preResult.failureReason());
                    return;
                }
                client.execute(() -> startCleaningStage(
                        client, plot, pestCount, sessionId, manualMode, preResult));
            } catch (Exception e) {
                e.printStackTrace();
                abortPreStage(client, sessionId, "exception: " + e);
            }
        });
    }

    static boolean isSetSpawnRetryPending(Minecraft client, int sessionId) {
        return shouldRetrySetSpawn(stage, sessionId, PestManager.getCurrentPestSessionId(),
                PestManager.isCleaningInProgress(),
                MacroWorkerThread.shouldAbortTask(client, MacroState.State.FARMING));
    }

    static boolean shouldRetrySetSpawn(Stage currentStage, int sessionId, int currentSessionId,
            boolean cleaning, boolean abort) {
        return currentStage == Stage.PRE && sessionId == currentSessionId && !cleaning && !abort;
    }

    private static void stopFarmingForPendingStart(Minecraft client, int sessionId) {
        farmingHeld = true;
        detectionHoldUntilMs = 0L;
        Runnable stop = () -> {
            if (stage == Stage.PRE && sessionId == PestManager.getCurrentPestSessionId()) {
                FarmingMacroManager.disable(client, "PestLifecycleManager.start(stop on detect)");
            }
        };
        if (client.isSameThread()) {
            stop.run();
        } else {
            client.execute(stop);
        }
    }

    // a short retry keeps the cycle alive under lag; a plot that never accepts /setspawn falls back to the full cooldown
    static long setSpawnFailureCooldownMs(int consecutiveFailures, long fullCooldownMs) {
        return consecutiveFailures >= SETSPAWN_FAILURES_BEFORE_FULL_COOLDOWN
                ? fullCooldownMs
                : SETSPAWN_FAILURE_RETRY_MS;
    }

    // resumes skipped while the start was pending would otherwise leave the bot standing still
    private static void resumeFarmingIfIdle(Minecraft client) {
        MacroWorkerThread.getInstance().submit("PestSetSpawn-Resume", () -> {
            if (FarmingMacroManager.isActive()
                    || blocksFarmingResume()
                    || MacroWorkerThread.shouldAbortTask(client, MacroState.State.FARMING)) {
                return;
            }
            ClientUtils.sendDebugMessage("Pest lifecycle: farming idle after dropped start; resuming.");
            GearManager.finalResume(client);
        });
    }

    private static void cancelPendingStart(int sessionId) {
        if (stage == Stage.PRE
                && sessionId == PestManager.getCurrentPestSessionId()
                && !PestManager.isCleaningInProgress()) {
            stage = Stage.IDLE;
            farmingHeld = false;
            PestManager.clearCleaningTriggerPending();
        }
    }

    public static void startPostStage(Minecraft client) {
        if (stage == Stage.POST) {
            return;
        }

        stage = Stage.POST;
        farmingHeld = false;
        ClientUtils.sendDebugMessage("Pest lifecycle: entering POST stage.");
        PestReturnManager.handlePestCleaningFinished(client);
    }

    public static void completePostStage() {
        stage = Stage.IDLE;
        farmingHeld = false;
    }

    /** Companion to PestManager's trigger-claim watchdog: a PRE stage whose worker never reported back. */
    public static void releaseStuckPreStage() {
        if (stage == Stage.PRE) {
            ClientUtils.sendDebugMessage("Pest lifecycle: releasing stuck PRE stage.");
            stage = Stage.IDLE;
            farmingHeld = false;
            resumeFarmingIfIdle(Minecraft.getInstance());
        }
    }

    private static void startCleaningStage(
            Minecraft client,
            String plot,
            int pestCount,
            int sessionId,
            boolean manualMode,
            PestPreStage.Result preResult) {
        if (stage != Stage.PRE
                || sessionId != PestManager.getCurrentPestSessionId()
                || !PestManager.isCleaningInProgress()) {
            return;
        }

        PestBallsackShredder.Result ballsackResult = preResult.ballsackResult();
        if (ballsackResult != null && ballsackResult.measured()) {
            PestManager.decrementPredictedAliveCount(client, ballsackResult.estimatedKilled());
        }
        if (ballsackResult != null
                && ballsackResult.measured()
                && shouldSkipCleaningAfterBallsack(client, plot, ballsackResult.estimatedRemaining())) {
            ClientUtils.sendDebugMessage("Ballsack Shredder: "
                    + ballsackResult.estimatedRemaining()
                    + " pest(s) estimated remaining; skipping CLEANING and entering POST.");
            startPostStage(client);
            return;
        }

        stage = Stage.CLEANING;
        ClientUtils.sendDebugMessage("Pest lifecycle: entering CLEANING stage ("
                + (manualMode ? "manual" : "automatic") + ").");

        if (manualMode) {
            switchToVacuumForManualStart(client);
            if (!ManualPestManager.startCleaningStage(client, pestCount)) {
                PestManager.handlePestCleaningFinished(client);
            }
            return;
        }

        ClientUtils.sendMessage("\u00A76Starting Pest Cleaner script (" + plot + ")...", true);
        if (PestBonusManager.isBonusInactive()) {
            ClientUtils.sendMessage("\u00A7dBonus is INACTIVE! Triggering Phillip reactivation...", true);
            PestBonusManager.beginReactivation();
        }
        client.execute(() -> PestDestroyer.start(client, plot));
    }

    private static void switchToVacuumForManualStart(Minecraft client) {
        if (!AetherConfig.VACCUM_WHEN_START.get()) {
            return;
        }

        int vacuumSlot = PestLoadoutHelper.findVacuumHotbarSlot(client);
        if (vacuumSlot < 0) {
            ClientUtils.sendMessage("\u00A7cManual Pest Mode: no vacuum found in hotbar.", false);
            ClientUtils.sendDebugMessage("Manual Pest Mode: Switch to Vacuum When Start enabled, but no vacuum was found.");
            return;
        }

        FailsafeManager.selectHotbarSlot(client, vacuumSlot);
        ClientUtils.sendDebugMessage("Manual Pest Mode: switched to vacuum slot " + (vacuumSlot + 1) + ".");
    }

    static boolean shouldSkipCleaningAfterBallsack(
            Minecraft client, String targetPlot, int estimatedRemaining) {
        boolean targetLeavesOnePest = AetherConfig.LEAVE_ONE_PEST_ALIVE.get()
                && AetherConfig.LEAVE_ONE_PEST_PLOTS.get().stream()
                        .map(PestPlotId::normalize)
                        .anyMatch(PestPlotId.normalize(targetPlot)::equals);
        return shouldSkipCleaningAfterBallsack(
                estimatedRemaining,
                targetLeavesOnePest,
                PestDestroyer.shouldFinishForAliveCount(client, estimatedRemaining));
    }

    static boolean shouldSkipCleaningAfterBallsack(
            int estimatedRemaining, boolean targetLeavesOnePest, boolean rememberedLeavesCanFinish) {
        return estimatedRemaining == 0
                || estimatedRemaining == 1 && (targetLeavesOnePest || rememberedLeavesCanFinish);
    }

    private static void abortPreStage(Minecraft client, int sessionId, String reason) {
        if (sessionId != PestManager.getCurrentPestSessionId() || stage != Stage.PRE) {
            ClientUtils.sendDebugMessage("Pest lifecycle: stale PRE abort ignored (" + reason + ").");
            return;
        }

        ClientUtils.sendDebugMessage("Pest lifecycle: PRE stage aborted at " + reason + "; returning through POST stage.");
        startPostStage(client);
    }
}
