package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.pest.PestManager;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import dev.aether.util.CommandUtils;

import java.util.*;

/**
 * In-client pest killing state machine inspired by FarmHelper's PestsDestroyer.
 * <p>
 * Uses {@link PathfindingManager} in fly mode to navigate to pest entities,
 * then aims and fires the vacuum to kill them.
 * <p>
 * Lifecycle: {@link PestLifecycleManager} starts this automatic CLEANING stage
 * after the shared PRE stage completes, beginning the pest hunt in the garden.
 * Each tick, {@link #update(Minecraft)} drives the state machine. When all
 * pests
 * are dead (or stuck), it calls
 * {@link PestManager#handlePestCleaningFinished(Minecraft)}.
 */
public class PestDestroyer {
    private static final PestDestroyerRuntime runtime = new PestDestroyerRuntime();
    private static final PestDestroyerCoordinatorContext CONTEXT =
            new PestDestroyerCoordinatorContext(runtime);

    // -- State machine --------------------------------------------------------
    public enum State {
        IDLE,
        TELEPORT_TO_PLOT,
        EQUIP_VACUUM,
        FLY_UP,
        FLY_TO_PEST,
        APPROACH_PEST,
        KILL_PEST,
        CHECK_NEXT,
        GET_LOCATION,
        FLY_TO_WAYPOINT,
        AOTV_BETWEEN_PESTS,
        AOTV_TO_ROOF,
        AOTV_TO_ROOF_RETURN,
        AOTV_POST_LOOKDOWN,
        BALLSACK_SHREDDER,
        FINISH
    }

    private static final long STATE_TIMEOUT_MS = 30_000;
    private static final long STUCK_TIMEOUT_MS = 5 * 60 * 1000;
    private static final double TARGET_REACH_DISTANCE = 12.0;
    private static final int PATHFINDER_STUCK_RETRY_TICKS = 20;
    private static final int APPROACH_TIMEOUT_TICKS = 120;
    private static final int MAX_GET_LOCATION_ATTEMPTS = 3;
    private static final int MAX_WAYPOINT_CYCLES = 5;
    private static final long FIREWORK_CAPTURE_DURATION_MS = 1200;
    private static final double FIREWORK_EXTRAPOLATE_DISTANCE = 15.0;
    private static final long PLOT_TP_WAIT_MS = 2500;
    private static final int SKULL_MISSING_CONFIRM_TICKS = 3;
    private static final long ROOF_RESCAN_INTERVAL_MS = 1000L;

    // -- Public API -----------------------------------------------------------

    public static boolean isActive() {
        return runtime.active;
    }

    public static State getState() {
        return runtime.state;
    }

    public static void start(Minecraft client) {
        start(client, null);
    }

    public static void start(Minecraft client, String initialPlot) {
        if (runtime.active)
            return;
        runtime.beginRun(
                PestLoadoutHelper.findVacuumHotbarSlot(client),
                System.currentTimeMillis());

        // Build plot queue from tab list (always fresh read)
        runtime.navigation.plotQueue.clear();
        Set<String> infested = PestManager.getInfestedPlotsFromTab(client);
        if (infested.isEmpty()) {
            // Fallback to cached value
            infested = PestManager.getCurrentInfestedPlots();
        }
        infested = PestLeaveOneController.filterSkippedPlots(runtime, infested);

        if (PestPlotId.isUsable(initialPlot)) {
            runtime.navigation.plotQueue.add(initialPlot);
            runtime.navigation.lastTargetPlot = initialPlot;
        }

        if (infested != null && !infested.isEmpty()) {
            for (String p : infested) {
                if (!containsPlot(runtime.navigation.plotQueue, p)) {
                    runtime.navigation.plotQueue.add(p);
                }
            }
            // Ensure PestManager's cache reflects the ordered list
            PestManager.setCurrentInfestedPlots(new java.util.LinkedHashSet<>(runtime.navigation.plotQueue));
        }
        runtime.navigation.currentPlotIdx = 0;

        ClientUtils.sendDebugMessage("[PestDestroyer] Started in-client pest killer. Plots: " + runtime.navigation.plotQueue);
        ClientUtils.sendMessage("\u00A7ePest destroyer active. Hunting pests...", false);

        beginInitialPestState(client);
    }

    private static void beginInitialPestState(Minecraft client) {
        // Check if we need to TP to an infested plot
        if (!runtime.navigation.plotQueue.isEmpty()) {
            String currentPlot = getEffectivePlot(client);
            String firstPlot = runtime.navigation.plotQueue.get(0);
            boolean forceCurrentPlotTeleport = plotsEqual(firstPlot, currentPlot)
                            && AetherConfig.PEST_PLOT_TP_FOR_CURRENT_PLOT.get()
                            && !CommandUtils.isFreshKnownPlotChat(firstPlot, 3000L);
            if (forceCurrentPlotTeleport) {
                runtime.state = State.TELEPORT_TO_PLOT;
                return;
            }
            boolean onFirstPlot = plotsEqual(firstPlot, currentPlot);
            if (!onFirstPlot) {
                runtime.state = State.TELEPORT_TO_PLOT;
                return;
            } else {
                // Already on the selected first plot - check if we need to AOTV to roof.
                if (PestAotvManager.shouldDoAotvOnCurrentPlot(client, currentPlot, true)) {
                    ClientUtils.sendDebugMessage("[PestDestroyer] Already on plot " + currentPlot + ", but AOTV to roof needed.");
                    runtime.state = State.AOTV_TO_ROOF;
                    PestAotvManager.setSneakingForAotv(true);
                    MacroWorkerThread.getInstance().submit("PestAotv-Roof-Start-" + currentPlot, () -> {
                        try {
                            PestAotvManager.performAotvToRoof(client);
                        } catch (InterruptedException ignored) {
                        }
                    });
                    return;
                }
            }
        }
        runtime.state = State.EQUIP_VACUUM;
    }

    public static void stop(Minecraft client) {
        if (!runtime.active)
            return;
        runtime.stopRun();
        PathfindingManager.stop();
        PestAotvManager.resetState();
        if (client != null && client.options != null) {
            ClientUtils.setKeyMappingState(client.options.keyUse, false);
            ClientUtils.setKeyMappingState(client.options.keyAttack, false);
            ClientUtils.setKeyMappingState(client.options.keyShift, false);
        }
        ClientUtils.sendDebugMessage("[PestDestroyer] Stopped.");
    }

    public static void reset() {
        PestLeaveOneController.clearRememberedPlots(runtime);
        runtime.resetAll();
    }

    /**
     * Called every client tick from the main update loop.
     */
    public static void update() {
        Minecraft client = Minecraft.getInstance();
        if (!runtime.active || client.player == null || client.level == null)
            return;

        if (FailsafeManager.shouldSuppressPestCleanerRotation(client)) {
            RotationManager.cancelRotation();
        }

        if (ClientUtils.isInventoryScreenOpen()) {
            ClientUtils.forceReleaseMovementKeys();
            return;
        }

        if (PestRoofAotvController.tick(client, runtime)) {
            return;
        }

        // While AOTV sneak is held (rotation + swap phase), pause tick processing
        if (PestAotvManager.isSneakingForAotv()) {
            return;
        }

        if (tryStartPeriodicRoofAotv(client)) {
            return;
        }

        if (PestTargetController.reconcileTrackedKills(
                client, runtime, CONTEXT)) {
            return;
        }

        if (PestDestroyerProgressController.tick(
                client, runtime, STUCK_TIMEOUT_MS, CONTEXT)) {
            return;
        }

        // Turbo: process up to 3 state transitions in a single tick if they are fast
        for (int i = 0; i < 3; i++) {
            State prevState = runtime.state;
            processState(client);
            // Break if state didn't change or we entered a "long" or delicate state
            if (!runtime.active || runtime.state == prevState || runtime.state == State.IDLE || runtime.state == State.KILL_PEST
                    || runtime.state == State.FLY_TO_PEST || runtime.state == State.FLY_TO_WAYPOINT
                    || runtime.state == State.GET_LOCATION || runtime.state == State.AOTV_BETWEEN_PESTS
                    || runtime.state == State.TELEPORT_TO_PLOT
                    || runtime.state == State.AOTV_TO_ROOF
                    || runtime.state == State.AOTV_TO_ROOF_RETURN
                    || runtime.state == State.AOTV_POST_LOOKDOWN
                    || runtime.state == State.BALLSACK_SHREDDER
                    || runtime.state == State.FLY_UP) {
                break;
            }
        }

        PestDestroyerInputController.update(
                client,
                runtime,
                PestDestroyerInputController.isVacuumTemporarilyReleased(runtime));

        PestDestroyerInputController.updateVacuumRetryPulse(client, runtime);
    }

    private static void processState(Minecraft client) {
        switch (runtime.state) {
            case TELEPORT_TO_PLOT -> handleTeleportToPlot(client);
            case EQUIP_VACUUM -> handleEquipVacuum(client);
            case FLY_UP -> handleFlyUp(client);
            case FLY_TO_PEST -> handleFlyToPest(client);
            case APPROACH_PEST -> handleApproachPest(client);
            case KILL_PEST -> handleKillPest(client);
            case CHECK_NEXT -> handleCheckNext(client);
            case GET_LOCATION -> handleGetLocation(client);
            case FLY_TO_WAYPOINT -> handleFlyToWaypoint(client);
            case AOTV_BETWEEN_PESTS -> handleAotvBetweenPests(client);
            case AOTV_TO_ROOF -> {
            } // Handled by worker thread
            case AOTV_TO_ROOF_RETURN -> {
            } // Handled early in update()
            case AOTV_POST_LOOKDOWN -> handlePostAotvLookdown(client);
            case BALLSACK_SHREDDER -> {
            } // Handled by its dedicated worker task.
            case FINISH -> finish(client);
            default -> {
            }
        }
    }

    private static boolean tryStartPeriodicRoofAotv(Minecraft client) {
        if (!AetherConfig.AOTV_TO_ROOF.get() || !isPeriodicRoofRescanState(runtime.state)) {
            return false;
        }

        long now = System.currentTimeMillis();
        if (now - runtime.lastRoofRescanAt < ROOF_RESCAN_INTERVAL_MS) {
            return false;
        }
        runtime.lastRoofRescanAt = now;

        String currentPlot = getEffectivePlot(client);
        if (!shouldAotvToRoofOnPlot(currentPlot) || !PestAotvManager.hasRoofAbove(client)) {
            return false;
        }

        State returnState = runtime.state;
        PathfindingManager.stop(false);
        ClientUtils.setKeyMappingState(client.options.keyUse, false);
        ClientUtils.setKeyMappingState(client.options.keyAttack, false);
        ClientUtils.setKeyMappingState(client.options.keyUp, false);
        ClientUtils.setKeyMappingState(client.options.keyDown, false);
        ClientUtils.sendDebugMessage("PestDestroyer: roof detected during cleaning. Pausing navigation for roof AOTV.");
        startRoofAotv(client, currentPlot, returnState, "PestAotv-Roof-Periodic-" + currentPlot);
        return true;
    }

    private static boolean shouldAotvToRoofOnPlot(String plot) {
        if (AetherConfig.AOTV_ROOF_PLOTS.get().isEmpty()) {
            return true;
        }
        return plot != null && AetherConfig.AOTV_ROOF_PLOTS.get().contains(plot);
    }

    private static boolean isPeriodicRoofRescanState(State state) {
        return state == State.CHECK_NEXT
                || state == State.GET_LOCATION
                || state == State.FLY_TO_WAYPOINT
                || state == State.FLY_TO_PEST
                || state == State.APPROACH_PEST;
    }

    private static void startRoofAotv(Minecraft client, String plot, State returnState, String taskName) {
        runtime.roofAotvReturnState = returnState;
        runtime.aotvStartY = Double.NaN;
        setState(State.AOTV_TO_ROOF);
        PestAotvManager.setSneakingForAotv(true);
        MacroWorkerThread.getInstance().submit(taskName, () -> {
            try {
                PestAotvManager.performAotvToRoof(client);
            } catch (InterruptedException ignored) {
            }
        });
    }

    public static void completeRoofAotv() {
        State returnState = runtime.roofAotvReturnState;
        runtime.roofAotvReturnState = null;
        if (!runtime.active) {
            return;
        }
        if (runtime.state == State.AOTV_POST_LOOKDOWN) {
            runtime.roofAotvReturnState = null;
            setState(State.FLY_UP);
            return;
        }
        if (returnState == null || returnState == State.IDLE || returnState == State.FINISH
                || returnState == State.AOTV_TO_ROOF) {
            setState(runtime.vacuumSlot < 0 ? State.EQUIP_VACUUM : State.CHECK_NEXT);
            return;
        }

        setState(returnState);
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) {
            return;
        }

        if (returnState == State.FLY_TO_PEST && runtime.currentTarget != null) {
            PestTargetController.startPathToPest(client, runtime.currentTarget);
        } else if (returnState == State.APPROACH_PEST && runtime.currentTarget != null) {
            PestTargetController.startPathToPest(client, runtime.currentTarget);
        } else if (returnState == State.FLY_TO_WAYPOINT && runtime.navigation.calculatedWaypoint != null) {
            Vec3 waypoint = runtime.navigation.calculatedWaypoint;
            PathfindingManager.startPathfind(client, (int) waypoint.x, (int) waypoint.y, (int) waypoint.z, true);
        }
    }

    // -- State handlers -------------------------------------------------------

    private static void handleEquipVacuum(Minecraft client) {
        PestEquipmentController.equipVacuum(
                client, runtime, CONTEXT);
    }

    private static void handleFlyUp(Minecraft client) {
        PestEquipmentController.enterFlight(
                client, runtime, CONTEXT);
    }

    private static void handleFlyToPest(Minecraft client) {
        PestCombatCoordinator.handleFlyToPest(
                client,
                CONTEXT,
                TARGET_REACH_DISTANCE,
                PATHFINDER_STUCK_RETRY_TICKS,
                STATE_TIMEOUT_MS);
    }

    private static void handleApproachPest(Minecraft client) {
        PestCombatCoordinator.handleApproachPest(
                client,
                CONTEXT,
                TARGET_REACH_DISTANCE,
                APPROACH_TIMEOUT_TICKS);
    }

    private static void handleKillPest(Minecraft client) {
        PestCombatCoordinator.handleKillPest(
                client,
                CONTEXT,
                SKULL_MISSING_CONFIRM_TICKS,
                STATE_TIMEOUT_MS);
    }

    private static void handleCheckNext(Minecraft client) {
        PestHuntController.checkNext(
                client, runtime, CONTEXT, CONTEXT);
    }

    // -- New state handlers: plot TP, firework tracking, waypoint flight ------

    private static void handleTeleportToPlot(Minecraft client) {
        PestNavigationCoordinator.handleTeleportToPlot(
                client,
                runtime.navigation,
                CONTEXT,
                PLOT_TP_WAIT_MS);
    }

    private static void handleGetLocation(Minecraft client) {
        PestNavigationCoordinator.handleGetLocation(
                client,
                runtime.navigation,
                CONTEXT,
                MAX_GET_LOCATION_ATTEMPTS,
                MAX_WAYPOINT_CYCLES,
                FIREWORK_CAPTURE_DURATION_MS,
                FIREWORK_EXTRAPOLATE_DISTANCE);
    }

    private static void handleFlyToWaypoint(Minecraft client) {
        PestNavigationCoordinator.handleFlyToWaypoint(
                client,
                runtime.navigation,
                CONTEXT,
                PATHFINDER_STUCK_RETRY_TICKS,
                STATE_TIMEOUT_MS);
    }

    private static void handlePostAotvLookdown(Minecraft client) {
        if (RotationManager.isRotating()) {
            return;
        }
        if (runtime.vacuumSlot < 0) {
            setState(State.EQUIP_VACUUM);
            return;
        }
        int selected = ((dev.aether.mixin.AccessorInventory) client.player.getInventory()).getSelected();
        if (selected != runtime.vacuumSlot) {
            client.execute(() -> FailsafeManager.selectHotbarSlot(client, runtime.vacuumSlot));
            return;
        }
        ClientUtils.setKeyMappingState(client.options.keyUse, true);
        if (System.currentTimeMillis() - runtime.stateEnteredAt >= AetherConfig.BALLSACK_LOOK_DOWN_TIME_MS.get()) {
            completeRoofAotv();
        }
    }

    static void startBallsackShredder(Minecraft client, String plot) {
        if (!PestManager.isBallsackShredderActiveForCurrentCycle()) {
            return;
        }

        setState(State.BALLSACK_SHREDDER);
        int sessionId = PestManager.getCurrentPestSessionId();
        int startingPests = Math.max(0, PestManager.getTabAliveCountNow(client));
        ClientUtils.sendDebugMessage("[PestDestroyer] Running Ballsack Shredder after arrival on plot " + plot + ".");
        MacroWorkerThread.getInstance().submit("PestBallsack-Arrival-" + plot, () -> {
            try {
                PestBallsackShredder.run(client, sessionId, startingPests);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                client.execute(() -> {
                    if (!runtime.active || runtime.state != State.BALLSACK_SHREDDER) {
                        return;
                    }
                    if (!client.player.getAbilities().flying && client.player.getAbilities().mayfly) {
                        setState(State.FLY_UP);
                    } else {
                        setState(State.CHECK_NEXT);
                    }
                });
            }
        });
    }

    private static void handleAotvBetweenPests(Minecraft client) {
        PestCombatCoordinator.handleAotvBetweenPests(
                client,
                CONTEXT,
                PestTargetController.AOTV_RANGE,
                PestTargetController.AOTV_GAP_MULTIPLIER,
                STATE_TIMEOUT_MS);
    }

    // -- Firework particle tracking -------------------------------------------

    /**
     * Called from the particle packet mixin when an ANGRY_VILLAGER particle
     * is received. These trace the firework trail fired by the vacuum.
     */
    public static void onFireworkParticle(double x, double y, double z) {
        PestPlotNavigator.onFireworkParticle(runtime.navigation, x, y, z);
    }

    static boolean tryNextPlot(Minecraft client) {
        boolean shouldTeleport = PestPlotNavigator.tryNextPlot(client, runtime.navigation);
        if (shouldTeleport) {
            setState(State.TELEPORT_TO_PLOT);
            return true;
        }
        return false;
    }

    // Predictive finish logic removed in favor of chat detection

    public static void finish(Minecraft client) {
        ClientUtils.setKeyMappingState(client.options.keyUse, false);
        ClientUtils.setKeyMappingState(client.options.keyDown, false);
        ClientUtils.setKeyMappingState(client.options.keyAttack, false);
        ClientUtils.setKeyMappingState(client.options.keyUp, false);
        runtime.navigation.isCapturingFirework = false;
        runtime.navigation.fireworkCaptureStartedAt = 0L;
        int killed = runtime.killedEntities.size();
        ClientUtils.sendMessage("\u00A7aPest destroyer finished. Tracked " + killed + " pest(s).", false);
        runtime.resetAll();
        PathfindingManager.stop();

        PestManager.handlePestCleaningFinished(client);
    }

    // -- Helpers --------------------------------------------------------------

    public static boolean shouldFinishForAliveCount(Minecraft client, int aliveCount) {
        return PestLeaveOneController.shouldFinishForAliveCount(client, runtime, aliveCount);
    }

    static String getAliveFinishReason(int aliveCount) {
        if (aliveCount == 0) {
            return "0 pests alive";
        }
        return "only whitelisted leave-one plot(s) remaining";
    }

    static boolean tryLeaveOneOnCurrentWhitelistedPlot(Minecraft client) {
        return PestLeaveOneController.tryLeaveCurrentPlot(
                client, runtime, CONTEXT);
    }

    static Set<String> filterSkippedInfestedPlots(Set<String> infested) {
        return PestLeaveOneController.filterSkippedPlots(runtime, infested);
    }

    public static Set<String> filterRememberedLeaveOnePlots(Set<String> infested) {
        return PestLeaveOneController.filterSkippedPlots(runtime, infested);
    }

    public static void onPestsSpawnedInPlot(String plot) {
        PestLeaveOneController.forgetPlot(runtime, plot);
    }

    public static void clearRememberedLeaveOnePlots() {
        PestLeaveOneController.clearRememberedPlots(runtime);
    }

    private static boolean plotsEqual(String first, String second) {
        return PestPlotId.equals(first, second);
    }

    private static boolean containsPlot(Collection<String> plots, String plot) {
        for (String candidate : plots) {
            if (plotsEqual(candidate, plot)) {
                return true;
            }
        }
        return false;
    }

    public static int getVacuumSlot() {
        return runtime.vacuumSlot;
    }

    public static void setAotvStartY(double startY) {
        runtime.aotvStartY = startY;
    }

    public static double getAotvStartY() {
        return runtime.aotvStartY;
    }

    public static void setState(State newState) {
        runtime.transitionTo(newState, System.currentTimeMillis());
    }

    /**
     * Notify the destroyer that an entity died - used to clear current target
     * or remove from killed list tracking.
     */
    public static void onEntityDeath(Entity entity) {
        PestTargetController.onEntityDeath(
                Minecraft.getInstance(), runtime, CONTEXT, entity);
    }

    static String getEffectivePlot(Minecraft client) {
        return PestPlotNavigator.getEffectivePlot(client, runtime.navigation);
    }
}


