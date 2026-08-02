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
import dev.aether.modules.gear.helpers.LoadoutManager;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.pest.PestManager;
import dev.aether.modules.visitor.VisitorManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.CommandUtils;
import dev.aether.macro.farming.FarmingMacroManager;
import dev.aether.modules.visitor.VisitorsMacro;
import net.minecraft.client.Minecraft;

public class PestReturnManager {
    private static volatile boolean isReturningFromPestVisitor = false;
    private static volatile boolean isReturnToLocationActive = false;
    private static volatile boolean isStoppingFlight = false;
    private static volatile boolean isFinishingInProgress = false;
    private static volatile long finishingStartedAtMs = 0L;
    private static volatile String finishingStage = "idle";
    private static int flightStopStage = 0;
    private static int flightStopTicks = 0;

    public static boolean isReturningFromPestVisitor() {
        return isReturningFromPestVisitor;
    }

    public static void setReturningFromPestVisitor(boolean returning) {
        isReturningFromPestVisitor = returning;
    }

    public static boolean isReturnToLocationActive() {
        return isReturnToLocationActive;
    }

    public static void setReturnToLocationActive(boolean active) {
        isReturnToLocationActive = active;
    }

    public static boolean isFinishingInProgress() {
        return isFinishingInProgress;
    }

    public static void updateFlightStop(Minecraft client) {
        if (!isStoppingFlight || client == null || client.options == null) {
            return;
        }

        flightStopTicks++;
        boolean jumpDown = flightStopStage == 0 || flightStopStage == 2;
        if (client.options.keyJump != null) {
            ClientUtils.setKeyMappingState(client.options.keyJump, jumpDown);
        }

        int stageTicks = switch (flightStopStage) {
            case 0, 2 -> 2;
            case 1 -> 3;
            default -> 0;
        };
        if (flightStopStage >= 3) {
            isStoppingFlight = false;
        } else if (flightStopTicks >= stageTicks) {
            flightStopStage++;
            flightStopTicks = 0;
        }
    }

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
        PestPrepSwapManager.clearCycleState();
        PestManager.setCleaningInProgress(false);
        isReturningFromPestVisitor = false;
        isReturnToLocationActive = false;
    }

    private static void releaseFinishingSequence() {
        isFinishingInProgress = false;
        finishingStartedAtMs = 0L;
        finishingStage = "idle";
        PestLifecycleManager.completePostStage();
    }

    private static boolean abortFinisherIfNeeded(Minecraft client, String stage) {
        if (!MacroWorkerThread.shouldAbortTask(client)) {
            return false;
        }

        clearCleaningFlags();
        releaseFinishingSequence();
        ClientUtils.sendDebugMessage("Finisher aborted at " + stage + ". Cleared cleaning flags.");
        return true;
    }

    private static void recoverToFarming(Minecraft client, String stage, Exception e) {
        if (e != null) {
            e.printStackTrace();
            ClientUtils.sendDebugMessage("CRITICAL ERROR in " + stage + ": " + e.getMessage());
        }

        clearCleaningFlags();

        if (MacroStateManager.isMacroRunning() && client != null && client.player != null) {
            ClientUtils.sendDebugMessage("Triggering failsafe: Returning to farming...");
            MacroStateManager.setCurrentState(MacroState.State.FARMING);
            ClientUtils.sendDebugMessage("Failsafe: Warping to garden...");
            CommandUtils.warpGarden();
            MacroWorkerThread.sleep(250);
            SqueakyMousematManager.armReapplyAttempt();
            client.execute(() -> FarmingMacroManager.enable(client,
                    FarmingMacroManager.createMacroFromConfig()));
        }

        releaseFinishingSequence();
    }

    private static void handOffToVisitors(Minecraft client, String stage) {
        clearCleaningFlags();
        if (MacroStateManager.isMacroRunning()) {
            MacroStateManager.setCurrentState(MacroState.State.VISITING);
        }
        releaseFinishingSequence();
        ClientUtils.sendDebugMessage(stage + ": Handing off from pest cleaning to visitors.");
        client.execute(() -> VisitorsMacro.start(client));
    }

    public static void handlePestCleaningFinished(Minecraft client) {
        if (!tryBeginFinishingSequence()) {
            long ageMs = finishingStartedAtMs <= 0L ? 0L : System.currentTimeMillis() - finishingStartedAtMs;
            ClientUtils.sendDebugMessage("Pest cleaning finish already in progress at stage " + finishingStage
                            + " (" + ageMs + "ms), ignoring duplicate trigger.");
            return;
        }
        setFinishingStage("starting");
        ClientUtils.sendDebugMessage("Pest cleaning finished sequence started.");
        ClientUtils.sendMessage("Pest cleaning finished detected.", true);
        MacroWorkerThread.getInstance().submit("PestFinish-Initial", () -> {
            try {
                setFinishingStage("initial checks");
                if (abortFinisherIfNeeded(client, "initial finish")) {
                    return;
                }
                setFinishingStage("unfly");
                ClientUtils.sendDebugMessage("Finisher: Stopping flight before post-actions...");
                performUnfly(client);
                if (abortFinisherIfNeeded(client, "unfly")) {
                    return;
                }

                if (PestTrapManager.isBlockedByPestExchange()) {
                    ClientUtils.sendDebugMessage("Finisher: skipping trap clear/refill while pest exchange is active.");
                } else if (PestManager.arePestTrapsEnabled()
                        && AetherConfig.AUTO_CLEAR_PEST_TRAPS.get()
                        && !PestTrapManager.getFullTrapsFromTab(client).isEmpty()) {
                    setFinishingStage("clear traps");
                    ClientUtils.sendDebugMessage("Finisher: Clearing full pest traps...");
                    PestTrapManager.runBlocking(client, AetherConfig.PEST_TRAPS_PLOT.get(),
                            PestTrapManager.Operation.CLEAR);
                    PathfindingManager.stop();
                    if (abortFinisherIfNeeded(client, "trap clear")) {
                        return;
                    }
                }

                if (!PestTrapManager.isBlockedByPestExchange()
                        && PestManager.arePestTrapsEnabled()
                        && AetherConfig.AUTO_REFILL_PEST_TRAPS.get()
                        && !PestTrapManager.getNoBaitTrapsFromTab(client).isEmpty()) {
                    setFinishingStage("refill traps");
                    ClientUtils.sendDebugMessage("Finisher: Refilling empty pest traps...");
                    PestTrapManager.runBlocking(client, AetherConfig.PEST_TRAPS_PLOT.get(),
                            PestTrapManager.Operation.REFILL);
                    PathfindingManager.stop();
                    if (abortFinisherIfNeeded(client, "trap refill")) {
                        return;
                    }
                }

                MacroWorkerThread.sleep(200);
                if (abortFinisherIfNeeded(client, "pre-greenhouse")) {
                    return;
                }

                setFinishingStage("greenhouse handoff");
                GreenhouseManager.runAutoGreenhouseIfDue(() -> {
                    setFinishingStage("composter handoff");
                    ComposterManager.runAutoComposterIfDue(() -> {
                        setFinishingStage("supercraft handoff");
                        SupercraftManager.runAutoSupercraftIfDue(
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
        MacroWorkerThread.getInstance().submit("PestFinish-Post", () -> {
            try {
                setFinishingStage("post intermediaries");
                if (abortFinisherIfNeeded(client, "post-intermediaries start")) {
                    return;
                }

                int visitors = VisitorManager.getVisitorCount(client);
                ClientUtils.sendDebugMessage("Finisher: Visitor count check: " + visitors + " (Threshold: "
                        + AetherConfig.VISITOR_THRESHOLD.get() + ")");
                if (visitors >= AetherConfig.VISITOR_THRESHOLD.get()
                        && VisitorManager.shouldSkipVisitorsDuringJacobsContest(client, true)) {
                    ClientUtils.sendDebugMessage("Finisher: Visitor threshold met, but Jacob's Contest window is active. Returning to farm.");
                } else if (visitors >= AetherConfig.VISITOR_THRESHOLD.get()
                        && !VisitorManager.isVisitorReentryCooldownActive(client, true)) {
                    handOffToVisitors(client, "Finisher");
                    return;
                }

                if (visitors >= AetherConfig.VISITOR_THRESHOLD.get()) {
                    ClientUtils.sendDebugMessage("Finisher: Visitor threshold met, but cooldown is active. Returning to farm.");
                }

                MacroWorkerThread.sleep(150);
                if (abortFinisherIfNeeded(client, "pre-return warp")) {
                    return;
                }

                setFinishingStage("warp garden");
                ClientUtils.sendDebugMessage("Finisher: Warping to garden (Return to Farm)...");
                dev.aether.util.CommandUtils.warpGarden();
                MacroWorkerThread.sleep(250);
                if (abortFinisherIfNeeded(client, "post-return warp")) {
                    return;
                }

                setFinishingStage("restore sunset pests night");
                PestLifecycleManager.restorePendingSunsetPestsNight(client);
                if (abortFinisherIfNeeded(client, "restore sunset pests night")) {
                    return;
                }

                isReturningFromPestVisitor = true;
                setFinishingStage("finalize return");
                ClientUtils.sendDebugMessage("Finisher: Calling finalizeReturnToFarm...");
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

        boolean releaseFinishingOnExit = true;
        try {
            setFinishingStage("finalize");
            ClientUtils.sendDebugMessage("Finalize: Starting return sequence.");
            int visitors = VisitorManager.getVisitorCount(client);
            ClientUtils.sendDebugMessage("Finalize: Visitor count check: " + visitors);
            if (visitors >= AetherConfig.VISITOR_THRESHOLD.get()
                    && VisitorManager.shouldSkipVisitorsDuringJacobsContest(client, true)) {
                ClientUtils.sendDebugMessage("Finalize: Visitor threshold met, but Jacob's Contest window is active. Continuing farming.");
            } else if (visitors >= AetherConfig.VISITOR_THRESHOLD.get()
                    && !VisitorManager.isVisitorReentryCooldownActive(client, true)) {
                handOffToVisitors(client, "Finalize");
                return;
            }

            if (visitors >= AetherConfig.VISITOR_THRESHOLD.get()) {
                ClientUtils.sendDebugMessage("Finalize: Visitor threshold met, but cooldown is active. Continuing farming.");
            }

            setFinishingStage("swap farming tool");
            if (!restoreFarmingLoadout(client)) {
                return;
            }
            setFinishingStage("wait for menu close");
            ClientUtils.sendDebugMessage("Finalize: Waiting for menus to close before farming resume...");
            if (!FarmingMacroManager.waitForFarmingResumeReady(client, 10_000L)) {
                ClientUtils.sendDebugMessage("Finalize: Farming resume delayed because a GUI/container is still open. Retrying...");
                releaseFinishingOnExit = false;
                MacroWorkerThread.getInstance().submit("PestFinalize-RetryAfterMenuClose", () -> {
                    MacroWorkerThread.sleep(500);
                    finalizeReturnToFarm(client);
                });
                return;
            }
            ClientUtils.sendDebugMessage("Finalize: Swapping to farming tool...");
            GearManager.swapToFarmingToolSync(client);
            ClientUtils.sendDebugMessage("Finalize: Tool swap done.");


            setFinishingStage("resume farming");
            ClientUtils.sendDebugMessage("Pest cleaning sequence completed. Next state: FARMING");
            MacroStateManager.setCurrentState(MacroState.State.FARMING);
            clearCleaningFlags();
            PestManager.startPestReentryCooldown();
            if (client.player != null) {
                ClientUtils.sendDebugMessage("Pest cleaner finished.");
            }

            if (AutoPestExchangeManager.tryTriggerPending(client)) {
                ClientUtils.sendDebugMessage("Pest cleaning handoff: starting queued pest exchange before farming resume.");
                return;
            }

            ClientUtils.sendDebugMessage("Pest cleaning sequence finished. Restarting farming...");
            ClientUtils.sendDebugMessage("Starting farming macro");
            SqueakyMousematManager.armReapplyAttempt();
            client.execute(() -> dev.aether.macro.farming.FarmingMacroManager.enable(client,
                    dev.aether.macro.farming.FarmingMacroManager.createMacroFromConfig()));
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            recoverToFarming(client, "finalizeReturnToFarm", e);
            return;
        } finally {
            if (releaseFinishingOnExit) {
                releaseFinishingSequence();
            }
        }
    }

    private static boolean restoreFarmingLoadout(Minecraft client) throws InterruptedException {
        int targetSlot = AetherConfig.LOADOUT_SLOT_FARMING.get();
        if (targetSlot <= 0 || LoadoutManager.trackedLoadoutSlot == targetSlot) {
            return true;
        }

        ClientUtils.sendMessage("§eRestoring farming loadout (slot " + targetSlot + ")...", true);
        GearManager.ensureLoadoutSlot(client, targetSlot);
        if (LoadoutManager.isSwappingLoadout) {
            ClientUtils.sendDebugMessage("Pest finalize: Waiting for loadout GUI...");
            ClientUtils.waitForWardrobeGui();
            ClientUtils.sendDebugMessage("Pest finalize: Loadout GUI detected, waiting for swap to complete...");
            while (LoadoutManager.isSwappingLoadout) {
                MacroWorkerThread.sleep(50);
            }
            while (LoadoutManager.loadoutCleanupTicks > 0) {
                MacroWorkerThread.sleep(50);
            }
            MacroWorkerThread.sleep(350);
            ClientUtils.sendDebugMessage("Pest finalize: Farming loadout restore complete.");
        }

        return !abortFinisherIfNeeded(client, "restore farming loadout");
    }
}
