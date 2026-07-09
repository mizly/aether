package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.config.ConfigHelpers;
import dev.aether.config.UnflyMode;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.modules.GreenhouseManager;
import dev.aether.modules.ComposterManager;
import dev.aether.modules.SupercraftManager;
import dev.aether.modules.farming.SqueakyMousematManager;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.pest.PestManager;
import dev.aether.modules.visitor.VisitorManager;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;

public class PestReturnManager {
    public static volatile boolean isReturningFromPestVisitor = false;
    public static volatile boolean isReturnToLocationActive = false;
    public static volatile boolean isStoppingFlight = false;
    public static volatile boolean isFinishingInProgress = false;
    private static volatile long finishingStartedAtMs = 0L;
    private static volatile String finishingStage = "idle";
    public static int flightStopStage = 0;
    public static int flightStopTicks = 0;

    public static void resetState() {
        isReturningFromPestVisitor = false;
        isReturnToLocationActive = false;
        isStoppingFlight = false;
        isFinishingInProgress = false;
        finishingStartedAtMs = 0L;
        finishingStage = "idle";
        flightStopStage = 0;
        flightStopTicks = 0;
    }

    private static synchronized boolean tryBeginFinishingSequence() {
        if (isFinishingInProgress) {
            return false;
        }
        isFinishingInProgress = true;
        finishingStartedAtMs = System.currentTimeMillis();
        finishingStage = "starting";
        return true;
    }

    private static void setFinishingStage(String stage) {
        finishingStage = stage;
    }

    private static void clearCleaningFlags() {
        PestPrepSwapManager.isPrepSwapping = false;
        PestPrepSwapManager.prepSwappedForCurrentPestCycle = false;
        PestManager.isCleaningInProgress = false;
        isReturningFromPestVisitor = false;
        isReturnToLocationActive = false;
    }

    private static void releaseFinishingSequence() {
        isFinishingInProgress = false;
        finishingStartedAtMs = 0L;
        finishingStage = "idle";
    }

    private static void runFinisherAsync(String threadName, Runnable task) {
        Thread thread = new Thread(task, threadName);
        thread.setDaemon(true);
        thread.start();
    }

    private static boolean abortFinisherIfNeeded(Minecraft client, String stage) {
        if (!MacroWorkerThread.shouldAbortTask(client)) {
            return false;
        }

        clearCleaningFlags();
        releaseFinishingSequence();
        if (AetherConfig.SHOW_DEBUG.get()) {
            ClientUtils.sendDebugMessage(client,
                    "Finisher aborted at " + stage + ". Cleared cleaning flags.");
        }
        return true;
    }

    private static void recoverToFarming(Minecraft client, String stage, Exception e) {
        if (e != null) {
            e.printStackTrace();
            ClientUtils.sendDebugMessage(client, "CRITICAL ERROR in " + stage + ": " + e.getMessage());
        }

        clearCleaningFlags();

        if (MacroStateManager.isMacroRunning() && client != null && client.player != null) {
            ClientUtils.sendDebugMessage(client, "Triggering failsafe: Returning to farming...");
            MacroStateManager.setCurrentState(MacroState.State.FARMING);
            ClientUtils.sendDebugMessage(client, "Failsafe: Warping to garden...");
            dev.aether.util.CommandUtils.warpGarden(client);
            MacroWorkerThread.sleep(250);
            SqueakyMousematManager.armReapplyAttempt();
            client.execute(() -> dev.aether.macro.FarmingMacroManager.enable(client,
                    dev.aether.macro.FarmingMacroManager.createMacroFromConfig()));
        }

        releaseFinishingSequence();
    }

    private static void handOffToVisitors(Minecraft client, String stage) {
        clearCleaningFlags();
        if (MacroStateManager.isMacroRunning()) {
            MacroStateManager.setCurrentState(MacroState.State.VISITING);
        }
        releaseFinishingSequence();
        ClientUtils.sendDebugMessage(client, stage + ": Handing off from pest cleaning to visitors.");
        client.execute(() -> dev.aether.modules.visitor.VisitorsMacro.start(client));
    }

    public static void handlePestCleaningFinished(Minecraft client) {
        if (!tryBeginFinishingSequence()) {
            long ageMs = finishingStartedAtMs <= 0L ? 0L : System.currentTimeMillis() - finishingStartedAtMs;
            ClientUtils.sendDebugMessage(client,
                    "Pest cleaning finish already in progress at stage " + finishingStage
                            + " (" + ageMs + "ms), ignoring duplicate trigger.");
            return;
        }
        setFinishingStage("starting");
        ClientUtils.sendDebugMessage(client, "Pest cleaning finished sequence started.");
        ClientUtils.sendMessage(client, "Pest cleaning finished detected.", true);
        runFinisherAsync("aether-pest-finish", () -> {
            try {
                setFinishingStage("initial checks");
                if (abortFinisherIfNeeded(client, "initial finish")) {
                    return;
                }
                if (PestTrapManager.isBlockedByPestExchange()) {
                    ClientUtils.sendDebugMessage(client,
                            "Finisher: skipping trap clear/refill while pest exchange is active.");
                } else if (PestManager.arePestTrapsEnabled()
                        && AetherConfig.AUTO_CLEAR_PEST_TRAPS.get()
                        && !PestTrapManager.getFullTrapsFromTab(client).isEmpty()) {
                    setFinishingStage("clear traps");
                    ClientUtils.sendDebugMessage(client, "Finisher: Clearing full pest traps...");
                    PestTrapManager.start(client);
                    try {
                        while (PestTrapManager.isRunning && !MacroWorkerThread.shouldAbortTask(client)) {
                            MacroWorkerThread.sleep(100);
                        }
                    } finally {
                        PathfindingManager.stop();
                    }
                    if (abortFinisherIfNeeded(client, "trap clear")) {
                        return;
                    }
                }

                if (!PestTrapManager.isBlockedByPestExchange()
                        && PestManager.arePestTrapsEnabled()
                        && AetherConfig.AUTO_REFILL_PEST_TRAPS.get()
                        && !PestTrapManager.getNoBaitTrapsFromTab(client).isEmpty()) {
                    setFinishingStage("refill traps");
                    ClientUtils.sendDebugMessage(client, "Finisher: Refilling empty pest traps...");
                    PestTrapManager.startRefill(client);
                    try {
                        while (PestTrapManager.isRunning && !MacroWorkerThread.shouldAbortTask(client)) {
                            MacroWorkerThread.sleep(100);
                        }
                    } finally {
                        PathfindingManager.stop();
                    }
                    if (abortFinisherIfNeeded(client, "trap refill")) {
                        return;
                    }
                }

                MacroWorkerThread.sleep(200);
                if (abortFinisherIfNeeded(client, "pre-greenhouse")) {
                    return;
                }

                setFinishingStage("greenhouse handoff");
                GreenhouseManager.runAutoGreenhouseIfDue(client, () -> {
                    setFinishingStage("composter handoff");
                    ComposterManager.runAutoComposterIfDue(client, () -> {
                        setFinishingStage("supercraft handoff");
                        SupercraftManager.runAutoSupercraftIfDue(client,
                                () -> continueAfterCleaningIntermediaries(client));
                    });
                });
            } catch (Exception e) {
                recoverToFarming(client, "handlePestCleaningFinished", e);
            }
        });
    }

    public static void performUnfly(Minecraft client) throws InterruptedException {
        if (client.player == null)
            return;

        if (ConfigHelpers.getUnflyMode() == UnflyMode.DOUBLE_TAP_SPACE) {
            isStoppingFlight = true;
            flightStopStage = 0;
            flightStopTicks = 0;

            long deadline = System.currentTimeMillis() + 3000;
            while (isStoppingFlight && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
        } else {
            client.execute(() -> {
                if (client.options != null)
                    ClientUtils.setKeyMappingState(client.options.keyShift, true);
            });
            Thread.sleep(150);
            client.execute(() -> {
                if (client.options != null)
                    ClientUtils.setKeyMappingState(client.options.keyShift, false);
            });
        }
    }

    private static void continueAfterCleaningIntermediaries(Minecraft client) {
        runFinisherAsync("aether-pest-finish-post", () -> {
            try {
                setFinishingStage("post intermediaries");
                if (abortFinisherIfNeeded(client, "post-intermediaries start")) {
                    return;
                }

                int visitors = VisitorManager.getVisitorCount(client);
                ClientUtils.sendDebugMessage(client, "Finisher: Visitor count check: " + visitors + " (Threshold: "
                        + AetherConfig.VISITOR_THRESHOLD.get() + ")");
                if (visitors >= AetherConfig.VISITOR_THRESHOLD.get()
                        && VisitorManager.shouldSkipVisitorsDuringJacobsContest(client, true)) {
                    ClientUtils.sendDebugMessage(client,
                            "Finisher: Visitor threshold met, but Jacob's Contest window is active. Returning to farm.");
                } else if (visitors >= AetherConfig.VISITOR_THRESHOLD.get()
                        && !VisitorManager.isVisitorReentryCooldownActive(client, true)) {
                    handOffToVisitors(client, "Finisher");
                    return;
                }

                if (visitors >= AetherConfig.VISITOR_THRESHOLD.get()) {
                    ClientUtils.sendDebugMessage(client,
                            "Finisher: Visitor threshold met, but cooldown is active. Returning to farm.");
                }

                MacroWorkerThread.sleep(150);
                if (abortFinisherIfNeeded(client, "pre-return warp")) {
                    return;
                }

                setFinishingStage("warp garden");
                ClientUtils.sendDebugMessage(client, "Finisher: Warping to garden (Return to Farm)...");
                dev.aether.util.CommandUtils.warpGarden(client);
                MacroWorkerThread.sleep(250);
                if (abortFinisherIfNeeded(client, "post-return warp")) {
                    return;
                }
                isReturningFromPestVisitor = true;
                setFinishingStage("finalize return");
                ClientUtils.sendDebugMessage(client, "Finisher: Calling finalizeReturnToFarm...");
                finalizeReturnToFarm(client);
            } catch (Exception e) {
                recoverToFarming(client, "continueAfterCleaningIntermediaries", e);
            }
        });
    }

    private static void finalizeReturnToFarm(Minecraft client) {
        if (!MacroStateManager.isMacroRunning()) {
            clearCleaningFlags();
            releaseFinishingSequence();
            return;
        }

        try {
            setFinishingStage("finalize");
            ClientUtils.sendDebugMessage(client, "Finalize: Starting return sequence.");
            int visitors = VisitorManager.getVisitorCount(client);
            ClientUtils.sendDebugMessage(client, "Finalize: Visitor count check: " + visitors);
            if (visitors >= AetherConfig.VISITOR_THRESHOLD.get()
                    && VisitorManager.shouldSkipVisitorsDuringJacobsContest(client, true)) {
                ClientUtils.sendDebugMessage(client,
                        "Finalize: Visitor threshold met, but Jacob's Contest window is active. Continuing farming.");
            } else if (visitors >= AetherConfig.VISITOR_THRESHOLD.get()
                    && !VisitorManager.isVisitorReentryCooldownActive(client, true)) {
                handOffToVisitors(client, "Finalize");
                return;
            }

            if (visitors >= AetherConfig.VISITOR_THRESHOLD.get()) {
                ClientUtils.sendDebugMessage(client,
                        "Finalize: Visitor threshold met, but cooldown is active. Continuing farming.");
            }

            setFinishingStage("swap farming tool");
            ClientUtils.sendDebugMessage(client, "Finalize: Swapping to farming tool...");
            GearManager.swapToFarmingToolSync(client);
            ClientUtils.sendDebugMessage(client, "Finalize: Tool swap done.");


            setFinishingStage("resume farming");
            ClientUtils.sendDebugMessage(client, "Pest cleaning sequence completed. Next state: FARMING");
            MacroStateManager.setCurrentState(MacroState.State.FARMING);
            clearCleaningFlags();
            PestManager.startPestReentryCooldown();
            if (client.player != null) {
                ClientUtils.sendDebugMessage(client, "Pest cleaner finished.");
            }

            if (AutoPestExchangeManager.tryTriggerPending(client)) {
                ClientUtils.sendDebugMessage(client,
                        "Pest cleaning handoff: starting queued pest exchange before farming resume.");
                return;
            }

            ClientUtils.sendDebugMessage(client,
                    "Pest cleaning sequence finished. Restarting farming...");
            ClientUtils.sendDebugMessage(client, "Starting farming macro");
            SqueakyMousematManager.armReapplyAttempt();
            client.execute(() -> dev.aether.macro.FarmingMacroManager.enable(client,
                    dev.aether.macro.FarmingMacroManager.createMacroFromConfig()));
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            recoverToFarming(client, "finalizeReturnToFarm", e);
            return;
        } finally {
            releaseFinishingSequence();
        }
    }
}
