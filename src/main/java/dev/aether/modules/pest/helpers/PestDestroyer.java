package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.mixin.AccessorInventory;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.pest.PestManager;
import dev.aether.macro.MacroWorkerThread;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import dev.aether.util.CommandUtils;

import java.util.*;

/**
 * In-client pest killing state machine inspired by FarmHelper's PestsDestroyer.
 * <p>
 * Uses {@link PathfindingManager} in fly mode to navigate to pest entities,
 * then aims and fires the vacuum to kill them.
 * <p>
 * Lifecycle: {@link PestCleaningSequencer} calls {@link #start(Minecraft)}
 * to begin hunting pests in the garden.
 * Each tick, {@link #update(Minecraft)} drives the state machine. When all
 * pests
 * are dead (or stuck), it calls
 * {@link PestManager#handlePestCleaningFinished(Minecraft)}.
 */
public class PestDestroyer {
    private static final PestDestroyerRuntime runtime = new PestDestroyerRuntime();
    private static final PestNavigationCoordinator.Context NAVIGATION_CONTEXT = new PestNavigationCoordinator.Context() {
        @Override
        public long getStateEnteredAt() {
            return runtime.stateEnteredAt;
        }

        @Override
        public void setStateEnteredAt(long enteredAt) {
            runtime.stateEnteredAt = enteredAt;
        }

        @Override
        public int getVacuumSlot() {
            return runtime.vacuumSlot;
        }

        @Override
        public void setVacuumSlot(int slot) {
            runtime.vacuumSlot = slot;
        }

        @Override
        public double getVacuumRange() {
            return runtime.vacuumRange;
        }

        @Override
        public int getStuckTicks() {
            return runtime.stuckTicks;
        }

        @Override
        public void setStuckTicks(int ticks) {
            runtime.stuckTicks = ticks;
        }

        @Override
        public int findVacuumHotbarSlot(Minecraft client) {
            return PestDestroyer.findVacuumHotbarSlot(client);
        }

        @Override
        public void setState(State state) {
            PestDestroyer.setState(state);
        }

        @Override
        public Entity findClosestPest(Minecraft client) {
            return PestDestroyer.findClosestPest(client);
        }

        @Override
        public void engagePestTarget(Minecraft client, Entity pest) {
            PestDestroyer.engagePestTarget(client, pest);
        }

        @Override
        public int countVisiblePestSkulls(Minecraft client) {
            return PestDestroyer.countVisiblePestSkulls(client);
        }

        @Override
        public boolean tryNextPlot(Minecraft client) {
            return PestDestroyer.tryNextPlot(client);
        }

        @Override
        public boolean tryLeaveOneOnCurrentWhitelistedPlot(Minecraft client) {
            return PestDestroyer.tryLeaveOneOnCurrentWhitelistedPlot(client);
        }

        @Override
        public void startRoofAotv(Minecraft client, String plot) {
            setState(State.AOTV_TO_ROOF);
            PestAotvManager.isSneakingForAotv = true;
            MacroWorkerThread.getInstance().submit("PestAotv-Roof-Arrival-" + plot, () -> {
                try {
                    PestAotvManager.performAotvToRoof(client);
                } catch (InterruptedException ignored) {
                }
            });
        }
    };
    private static final PestCombatCoordinator.Context COMBAT_CONTEXT = new PestCombatCoordinator.Context() {
        @Override
        public Entity getCurrentTarget() {
            return runtime.currentTarget;
        }

        @Override
        public int getVacuumSlot() {
            return runtime.vacuumSlot;
        }

        @Override
        public void setVacuumSlot(int slot) {
            runtime.vacuumSlot = slot;
        }

        @Override
        public double getVacuumRange() {
            return runtime.vacuumRange;
        }

        @Override
        public int getAotvSlot() {
            return runtime.aotvSlot;
        }

        @Override
        public void setAotvSlot(int slot) {
            runtime.aotvSlot = slot;
        }

        @Override
        public int getAotvUseCount() {
            return runtime.aotvUseCount;
        }

        @Override
        public void setAotvUseCount(int useCount) {
            runtime.aotvUseCount = useCount;
        }

        @Override
        public long getAotvLastUseAt() {
            return runtime.aotvLastUseAt;
        }

        @Override
        public void setAotvLastUseAt(long lastUseAt) {
            runtime.aotvLastUseAt = lastUseAt;
        }

        @Override
        public long getAotvNextUseAt() {
            return runtime.aotvNextUseAt;
        }

        @Override
        public void setAotvNextUseAt(long nextUseAt) {
            runtime.aotvNextUseAt = nextUseAt;
        }

        @Override
        public long getAotvPostClickGraceUntil() {
            return runtime.aotvPostClickGraceUntil;
        }

        @Override
        public void setAotvPostClickGraceUntil(long graceUntil) {
            runtime.aotvPostClickGraceUntil = graceUntil;
        }

        @Override
        public long getAotvPendingUseAt() {
            return runtime.aotvPendingUseAt;
        }

        @Override
        public void setAotvPendingUseAt(long pendingUseAt) {
            runtime.aotvPendingUseAt = pendingUseAt;
        }

        @Override
        public double getAotvLastUsePlayerX() {
            return runtime.aotvLastUsePlayerX;
        }

        @Override
        public void setAotvLastUsePlayerX(double x) {
            runtime.aotvLastUsePlayerX = x;
        }

        @Override
        public double getAotvLastUsePlayerY() {
            return runtime.aotvLastUsePlayerY;
        }

        @Override
        public void setAotvLastUsePlayerY(double y) {
            runtime.aotvLastUsePlayerY = y;
        }

        @Override
        public double getAotvLastUsePlayerZ() {
            return runtime.aotvLastUsePlayerZ;
        }

        @Override
        public void setAotvLastUsePlayerZ(double z) {
            runtime.aotvLastUsePlayerZ = z;
        }

        @Override
        public boolean didArriveAtCurrentTargetViaAotv() {
            return runtime.arrivedAtCurrentTargetViaAotv;
        }

        @Override
        public void setArrivedAtCurrentTargetViaAotv(boolean arrived) {
            runtime.arrivedAtCurrentTargetViaAotv = arrived;
        }

        @Override
        public long getStateEnteredAt() {
            return runtime.stateEnteredAt;
        }

        @Override
        public void setStateEnteredAt(long enteredAt) {
            runtime.stateEnteredAt = enteredAt;
        }

        @Override
        public int getStuckTicks() {
            return runtime.stuckTicks;
        }

        @Override
        public void setStuckTicks(int ticks) {
            runtime.stuckTicks = ticks;
        }

        @Override
        public long getFlyRetryAfterUnflyAt() {
            return runtime.flyRetryAfterUnflyAt;
        }

        @Override
        public void setFlyRetryAfterUnflyAt(long retryAt) {
            runtime.flyRetryAfterUnflyAt = retryAt;
        }

        @Override
        public int getApproachTicks() {
            return runtime.approachTicks;
        }

        @Override
        public void setApproachTicks(int ticks) {
            runtime.approachTicks = ticks;
        }

        @Override
        public int getTargetWithoutSkullTicks() {
            return runtime.targetWithoutSkullTicks;
        }

        @Override
        public void setTargetWithoutSkullTicks(int ticks) {
            runtime.targetWithoutSkullTicks = ticks;
        }

        @Override
        public boolean isLookingAt(Minecraft client, Vec3 targetPos, float tolerance) {
            return PestDestroyer.isLookingAt(client, targetPos, tolerance);
        }

        @Override
        public void setState(State state) {
            PestDestroyer.setState(state);
        }

        @Override
        public void startPathToPest(Minecraft client, Entity pest) {
            PestDestroyer.startPathToPest(client, pest);
        }

        @Override
        public boolean switchToNextQueuedTarget(Minecraft client) {
            return PestDestroyer.switchToNextQueuedTarget(client);
        }

        @Override
        public Entity peekNextQueuedPest(Minecraft client) {
            return PestDestroyer.peekNextQueuedPest(client);
        }

        @Override
        public void maybePreMoveToNextTarget(Minecraft client, Entity nextTarget, double currentDist) {
            PestDestroyer.maybePreMoveToNextTarget(client, nextTarget, currentDist);
        }

        @Override
        public boolean hasPestSkullMarkerForTarget(Minecraft client, Entity target) {
            return PestDestroyer.hasPestSkullMarkerForTarget(client, target);
        }

        @Override
        public void markKilled(Entity entity) {
            runtime.killedEntities.add(entity);
        }

        @Override
        public int findVacuumHotbarSlot(Minecraft client) {
            return PestDestroyer.findVacuumHotbarSlot(client);
        }

        @Override
        public int findAotvHotbarSlot(Minecraft client) {
            return PestDestroyer.findAOTVHotbarSlot(client);
        }
    };

    // -- State machine --------------------------------------------------------
    public enum State {
        IDLE,
        TELEPORT_TO_PLOT,
        DISCO_SPIN,
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
        FINISH
    }

    private static final double AOTV_RANGE = 12.0; // Instant Transmission teleports 12 blocks
    private static final double AOTV_GAP_MULTIPLIER = 1.6; // Use AOTV when gap > AOTV_RANGE * this

    private static final long STATE_TIMEOUT_MS = 30_000;
    private static final long STUCK_TIMEOUT_MS = 5 * 60 * 1000;
    private static final int TARGET_SWITCH_ROTATION_MS = 90;
    private static final double TARGET_REACH_DISTANCE = 12.0;
    private static final double PRE_TRIGGER_RATIO = 0.67;
    private static final double PRE_TRIGGER_DISTANCE = TARGET_REACH_DISTANCE * PRE_TRIGGER_RATIO;
    private static final double PRE_MOVE_MIN_NEXT_DIST = 2.5;
    private static final int PATHFINDER_STUCK_RETRY_TICKS = 20;
    private static final int APPROACH_TIMEOUT_TICKS = 120;
    private static final long KILL_USE_RETRY_HOLD_MS = 2000L;
    private static final long KILL_USE_RETRY_RELEASE_MS = 150L;
    private static final long KILL_USE_RETRY_CLICK_HOLD_MS = 100L;

    private static final int MAX_GET_LOCATION_ATTEMPTS = 3;
    private static final int MAX_WAYPOINT_CYCLES = 5;
    private static final long FIREWORK_CAPTURE_DURATION_MS = 1200;
    private static final double FIREWORK_EXTRAPOLATE_DISTANCE = 15.0;
    private static final long PLOT_TP_WAIT_MS = 2500;
    private static final long STARTUP_FINISH_GRACE_MS = 5000;
    private static final int ZERO_PEST_TAB_CONFIRM_TICKS = 10;
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
        runtime.active = true;
        // Premature trustedPlot setting removed here. 
        // We now rely on getEffectivePlot() to check the actual current location,
        // and handleTeleportToPlot will set the trustedPlot upon arrival.
        runtime.stateEnteredAt = System.currentTimeMillis();
        runtime.activatedAt = System.currentTimeMillis();
        runtime.currentTarget = null;
        runtime.killedEntities.clear();
        runtime.pestTargetQueue.clear();
        runtime.stuckTicks = 0;
        runtime.approachTicks = 0;
        runtime.flyRetryAfterUnflyAt = 0L;
        // Keep vacuumSlot if already known and valid
        if (runtime.vacuumSlot == -1 || findVacuumHotbarSlot(client) != runtime.vacuumSlot) {
            runtime.vacuumSlot = findVacuumHotbarSlot(client);
        }
        runtime.vacuumRange = 7.5f;
        runtime.aotvSlot = -1;
        runtime.aotvUseCount = 0;
        runtime.aotvLastUseAt = 0L;
        runtime.aotvNextUseAt = 0L;
        runtime.aotvPostClickGraceUntil = 0L;
        runtime.aotvPendingUseAt = 0L;
        runtime.aotvLastUsePlayerX = Double.NaN;
        runtime.aotvLastUsePlayerY = Double.NaN;
        runtime.aotvLastUsePlayerZ = Double.NaN;
        runtime.arrivedAtCurrentTargetViaAotv = false;
        runtime.killVacuumRetryPressAt = 0L;
        runtime.lastRoofRescanAt = 0L;
        runtime.roofAotvReturnState = null;
        runtime.navigation.fireworkFirstPos = null;
        runtime.navigation.fireworkLastPos = null;
        runtime.navigation.fireworkParticleCount = 0;
        runtime.navigation.isCapturingFirework = false;
        runtime.navigation.fireworkCaptureStartedAt = 0L;
        runtime.navigation.calculatedWaypoint = null;
        runtime.navigation.getLocationAttempts = 0;
        runtime.navigation.plotTpSent = false;
        runtime.navigation.plotTpWindow = null;
        runtime.navigation.trustedPlot = null;
        runtime.navigation.trustedPlotExpiresAt = 0L;
        runtime.navigation.discoWalkStarted = false;
        runtime.navigation.discoTargetReached = false;
        runtime.navigation.discoWalkStartedAt = 0L;
        runtime.navigation.leaveOneSkippedPlots.clear();
        runtime.zeroPestTabTicks = 0;
        runtime.targetWithoutSkullTicks = 0;
        runtime.lastPreRotateAt = 0;
        runtime.accountedKilledPestEntityIds.clear();

        // Build plot queue from tab list (always fresh read)
        runtime.navigation.plotQueue.clear();
        Set<String> infested = PestDiscoDestinationManager.prioritizePlots(PestManager.getInfestedPlotsFromTab(client));
        if (infested.isEmpty()) {
            // Fallback to cached value
            infested = PestDiscoDestinationManager.prioritizePlots(PestManager.currentInfestedPlots);
        }

        if (PestDiscoDestinationManager.isUsablePlot(initialPlot)) {
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
            PestManager.currentInfestedPlots = new java.util.LinkedHashSet<>(runtime.navigation.plotQueue);
        }
        runtime.navigation.currentPlotIdx = 0;

        ClientUtils.sendDebugMessage("[PestDestroyer] Started in-client pest killer. Plots: " + runtime.navigation.plotQueue);
        ClientUtils.sendMessage("\u00A7ePest destroyer active. Hunting pests...", false);

        // Check if we need to TP to an infested plot
        if (!runtime.navigation.plotQueue.isEmpty()) {
            String currentPlot = getEffectivePlot(client);
            String firstPlot = runtime.navigation.plotQueue.get(0);
            if (PestDiscoDestinationManager.matchesPlot(firstPlot)) {
                if (CommandUtils.isFreshKnownPlotChat(firstPlot, 15_000L) || plotsEqual(firstPlot, currentPlot)) {
                    ClientUtils.sendDebugMessage("[PestDestroyer] Disco destination confirmed on plot " + firstPlot
                                    + ". Skipping initial plot TP.");
                    runtime.navigation.trustedPlot = firstPlot;
                    runtime.navigation.trustedPlotExpiresAt = System.currentTimeMillis() + 120_000;
                    runtime.navigation.discoTargetReached = true;
                    runtime.state = State.DISCO_SPIN;
                    return;
                }

                ClientUtils.sendDebugMessage("[PestDestroyer] Disco destination active on plot " + firstPlot + ". Forcing initial plot TP.");
                runtime.state = State.TELEPORT_TO_PLOT;
                return;
            }
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
                    PestAotvManager.isSneakingForAotv = true; // Set flag early to prevent tick handler skip
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
        runtime.active = false;
        runtime.state = State.IDLE;
        runtime.currentTarget = null;
        runtime.killedEntities.clear();
        runtime.pestTargetQueue.clear();
        runtime.targetWithoutSkullTicks = 0;
        runtime.navigation.isCapturingFirework = false;
        runtime.navigation.leaveOneSkippedPlots.clear();
        runtime.navigation.discoWalkStarted = false;
        runtime.navigation.discoTargetReached = false;
        runtime.navigation.discoWalkStartedAt = 0L;
        runtime.accountedKilledPestEntityIds.clear();
        resetKillVacuumRetry();
        PathfindingManager.stop();
        PestAotvManager.resetState();
        runtime.aotvStartY = Double.NaN;
        runtime.aotvLastUseAt = 0L;
        runtime.aotvNextUseAt = 0L;
        runtime.aotvPostClickGraceUntil = 0L;
        runtime.aotvPendingUseAt = 0L;
        runtime.aotvLastUsePlayerX = Double.NaN;
        runtime.aotvLastUsePlayerY = Double.NaN;
        runtime.aotvLastUsePlayerZ = Double.NaN;
        runtime.arrivedAtCurrentTargetViaAotv = false;
        runtime.lastRoofRescanAt = 0L;
        runtime.roofAotvReturnState = null;
        if (client != null && client.options != null) {
            ClientUtils.setKeyMappingState(client.options.keyUse, false);
            ClientUtils.setKeyMappingState(client.options.keyAttack, false);
            ClientUtils.setKeyMappingState(client.options.keyShift, false);
        }
        ClientUtils.sendDebugMessage("[PestDestroyer] Stopped.");
    }

    public static void reset() {
        runtime.active = false;
        runtime.state = State.IDLE;
        runtime.currentTarget = null;
        runtime.killedEntities.clear();
        runtime.pestTargetQueue.clear();
        runtime.stuckTicks = 0;
        runtime.approachTicks = 0;
        runtime.flyRetryAfterUnflyAt = 0L;
        runtime.aotvSlot = -1;
        runtime.aotvStartY = Double.NaN;
        runtime.aotvUseCount = 0;
        runtime.aotvLastUseAt = 0L;
        runtime.aotvNextUseAt = 0L;
        runtime.aotvPostClickGraceUntil = 0L;
        runtime.aotvPendingUseAt = 0L;
        runtime.aotvLastUsePlayerX = Double.NaN;
        runtime.aotvLastUsePlayerY = Double.NaN;
        runtime.aotvLastUsePlayerZ = Double.NaN;
        runtime.arrivedAtCurrentTargetViaAotv = false;
        runtime.killVacuumRetryPressAt = 0L;
        runtime.lastRoofRescanAt = 0L;
        runtime.roofAotvReturnState = null;
        runtime.navigation.fireworkFirstPos = null;
        runtime.navigation.fireworkLastPos = null;
        runtime.navigation.fireworkParticleCount = 0;
        runtime.navigation.isCapturingFirework = false;
        runtime.navigation.fireworkCaptureStartedAt = 0L;
        runtime.navigation.calculatedWaypoint = null;
        runtime.navigation.getLocationAttempts = 0;
        runtime.navigation.waypointCycleCount = 0;
        runtime.navigation.plotTpSent = false;
        runtime.navigation.plotTpWindow = null;
        runtime.navigation.plotQueue.clear();
        runtime.navigation.leaveOneSkippedPlots.clear();
        runtime.navigation.currentPlotIdx = 0;
        runtime.navigation.discoWalkStarted = false;
        runtime.navigation.discoTargetReached = false;
        runtime.navigation.discoWalkStartedAt = 0L;
        runtime.zeroPestTabTicks = 0;
        runtime.targetWithoutSkullTicks = 0;
        runtime.accountedKilledPestEntityIds.clear();
        resetKillVacuumRetry();
        runtime.navigation.lastTargetPlot = null;
        runtime.navigation.trustedPlot = null;
        runtime.navigation.trustedPlotExpiresAt = 0;
        runtime.lastPreRotateAt = 0;
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

        // AOTV_TO_ROOF: hold sneak + right click until air is detected above head
        if (runtime.state == State.AOTV_TO_ROOF) {
            // Always keep sneaking during the entire AOTV phase
            PestAotvManager.isSneakingForAotv = true;

            if (!Double.isNaN(runtime.aotvStartY)) {
                // Once the initial item has been fired by the worker, keep holding right click
                // for rapid teleportation (Etherwarp climb) until we have air above us.
                int aotvHotbarSlot = dev.aether.modules.gear.GearManager.findAspectOfTheVoidSlot(client);
                if (aotvHotbarSlot != -1 && ((dev.aether.mixin.AccessorInventory) client.player.getInventory()).getSelected() == aotvHotbarSlot) {
                    ClientUtils.setKeyMappingState(client.options.keyUse, true);
                }

                boolean allAir = true;
                net.minecraft.core.BlockPos base = PestAotvManager.getRoofScanBase(client);
                for (int i = 2; i <= 20; i++) {
                    if (!client.level.getBlockState(base.above(i)).isAir()) {
                        allAir = false;
                        break;
                    }
                }

                if (allAir) {
                    ClientUtils.sendDebugMessage("[PestDestroyer] AOTV Success: 20 blocks of air detected above.");
                    runtime.aotvStartY = Double.NaN;
                    PestAotvManager.isSneakingForAotv = false;
                    ClientUtils.setKeyMappingState(client.options.keyShift, false);
                    ClientUtils.setKeyMappingState(client.options.keyUse, false);
                    completeRoofAotv();
                    return;
                }

                // Increased timeout (2.0s) to allow for multiple rapid teleports if the roof is thick/far
                if (System.currentTimeMillis() - runtime.stateEnteredAt > 2000) {
                    ClientUtils.sendDebugMessage("[PestDestroyer] AOTV timed out after 2.0s. Issuing /plottp to recover.");
                    runtime.aotvStartY = Double.NaN;
                    PestAotvManager.isSneakingForAotv = false;
                    ClientUtils.setKeyMappingState(client.options.keyShift, false);
                    ClientUtils.setKeyMappingState(client.options.keyUse, false);
                    // Always issue /plottp to the current plot, even if already there
                    String currentPlot = ClientUtils.getCurrentPlot();
                    if (currentPlot != null && !currentPlot.isEmpty()) {
                        runtime.navigation.plotTpWindow = CommandUtils.beginChatWindow();
                        CommandUtils.initiatePlotTp(currentPlot);
                        runtime.navigation.lastTargetPlot = currentPlot;
                        runtime.navigation.plotTpSent = true;
                        runtime.stateEnteredAt = System.currentTimeMillis();
                        runtime.roofAotvReturnState = null;
                        // After teleport, go to TELEPORT_TO_PLOT state to finalize arrival
                        setState(State.TELEPORT_TO_PLOT);
                    } else {
                        completeRoofAotv();
                    }
                    return;
                }
            } else if (!PestAotvManager.isSneakingForAotv) {
                // Worker finished (or failed) but aotvStartY was never set
                completeRoofAotv();
            }
            return;
        }

        // While AOTV sneak is held (rotation + swap phase), pause tick processing
        if (PestAotvManager.isSneakingForAotv) {
            return;
        }

        if (lockDiscoDestinationIfCurrentPlot(client)) {
            return;
        }

        if (tryStartPeriodicRoofAotv(client)) {
            return;
        }

        // Stop hunting quickly if tablist reports no pests alive.
        // Require consecutive confirmations to avoid one-tick tab desync noise.
        boolean lockedOnDiscoDestination = isLockedOnDiscoDestinationPlot(client);
        int aliveNow = lockedOnDiscoDestination ? -1 : PestManager.getEffectiveAliveCountNow(client);
        boolean inStartupGrace = System.currentTimeMillis() - runtime.activatedAt < STARTUP_FINISH_GRACE_MS;

        if (!lockedOnDiscoDestination
                && aliveNow >= 0 && shouldFinishForAliveCount(client, aliveNow)
                && (!inStartupGrace || isDiscoDestinationActive(client))) {
            runtime.zeroPestTabTicks++;
            if (runtime.zeroPestTabTicks >= ZERO_PEST_TAB_CONFIRM_TICKS) {
                ClientUtils.setKeyMappingState(client.options.keyUse, false);
                ClientUtils.setKeyMappingState(client.options.keyDown, false);
                ClientUtils.sendDebugMessage("PestDestroyer: tablist reports " + getAliveFinishReason(aliveNow) + ". Finishing.");
                finish(client);
                return;
            }
        } else {
            if (aliveNow >= 0 && shouldFinishForAliveCount(client, aliveNow) && inStartupGrace && runtime.zeroPestTabTicks == 0) {
                ClientUtils.sendDebugMessage("PestDestroyer: ignoring finish-level tab reading during startup grace.");
            }
            runtime.zeroPestTabTicks = 0;
        }

        // Periodically refresh the plot queue from tab list to detect when the current
        // plot is cleared.
        // If we're on a plot that's no longer the first priority, and we're not busy
        // killing or moving to a pest, leave.
        if (!lockedOnDiscoDestination
                && client.player.tickCount % 10 == 0 && runtime.state != State.TELEPORT_TO_PLOT && runtime.state != State.IDLE
                && runtime.state != State.FINISH) {
            Set<String> rawInfested = PestManager.getInfestedPlotsFromTab(client);
            Set<String> infested = filterSkippedInfestedPlots(PestDiscoDestinationManager.prioritizePlots(rawInfested));
            if (!infested.isEmpty()) {
                String firstPlot = infested.iterator().next();
                String currentPlot = getEffectivePlot(client);
                if (!plotsEqual(firstPlot, currentPlot)) {
                    // Current plot is no longer prioritized (likely cleared from tab).
                    // If we are currently "scanning" or "looking", skip the vacuum and move
                    // immediately.
                    if (runtime.state == State.CHECK_NEXT
                            || runtime.state == State.GET_LOCATION
                            || runtime.state == State.FLY_TO_WAYPOINT) {
                        ClientUtils.sendDebugMessage("[PestDestroyer] Plot " + currentPlot
                                + " no longer first in tab. Leaving immediately for " + firstPlot);
                        runtime.navigation.plotQueue.clear();
                        runtime.navigation.plotQueue.addAll(infested);
                        runtime.navigation.currentPlotIdx = 0;
                        runtime.navigation.plotTpSent = false;
                        setState(State.TELEPORT_TO_PLOT);
                        return;
                    }
                }
            } else if (!rawInfested.isEmpty() && shouldFinishForAliveCount(client, aliveNow)) {
                ClientUtils.sendDebugMessage("PestDestroyer: only skipped leave-one plots remain. Finishing.");
                finish(client);
                return;
            }
        }

        // Global stuck timeout
        if (System.currentTimeMillis() - runtime.activatedAt > STUCK_TIMEOUT_MS) {
            ClientUtils.sendMessage("\u00A7cPest destroyer timed out after 5 minutes. Returning to farm.", false);
            finish(client);
            return;
        }

        // Ensure flying during active flight states
        if (runtime.state != State.IDLE && runtime.state != State.FINISH && runtime.state != State.TELEPORT_TO_PLOT
                && runtime.state != State.DISCO_SPIN
                && runtime.state != State.EQUIP_VACUUM && runtime.state != State.FLY_UP
                && !isDiscoDestinationActive(client)) {
            if (!client.player.getAbilities().flying && client.player.getAbilities().mayfly) {
                setState(State.FLY_UP);
                return;
            }
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
                    || runtime.state == State.DISCO_SPIN || runtime.state == State.AOTV_TO_ROOF
                    || runtime.state == State.FLY_UP) {
                break;
            }
        }

        if (runtime.active && client.player != null) {
            int selected = ((AccessorInventory) client.player.getInventory()).getSelected();
            if (runtime.state == State.AOTV_BETWEEN_PESTS) {
                // Between-pests AOTV uses one-shot clicks via PestCombatCoordinator.
                // Do not force-release or re-hold use here.
            } else if (runtime.state == State.DISCO_SPIN && isDiscoDestinationActive(client)) {
                // Disco destination controls the first vacuum hold inside DISCO_SPIN so
                // the state can scan for a target before right-click kills it.
            } else if (runtime.vacuumSlot != -1) {
                // Aggressive holding behavior: when actively in KILL_PEST state we want to
                // ensure the vacuum's right-click is held even if the hotbar selection
                // briefly differs. This helps when a selection swap or client timing
                // causes the server to miss the use input. Respect the temporary
                // retry-release state and firework-capture state.
                if (runtime.state == State.KILL_PEST) {
                    if (!runtime.navigation.isCapturingFirework && !isKillVacuumUseTemporarilyReleased()) {
                        // If the vacuum isn't currently selected on the client, schedule
                        // a hotbar select on the client thread so the use packet will
                        // be sent with the correct item. Do not spam selection if it's
                        // already correct.
                        if (selected != runtime.vacuumSlot) {
                            client.execute(() -> FailsafeManager.selectHotbarSlot(client, runtime.vacuumSlot));
                        }
                        ClientUtils.setKeyMappingState(client.options.keyUse, true);
                    } else {
                        ClientUtils.setKeyMappingState(client.options.keyUse, false);
                    }
                } else {
                    // Legacy behavior for non-kill states: only hold when the vacuum
                    // is actually selected and we are not in a GET_LOCATION or
                    // firework capture window, and not temporarily released.
                    if (selected == runtime.vacuumSlot
                            && runtime.state != State.GET_LOCATION
                            && !runtime.navigation.isCapturingFirework
                            && !isKillVacuumUseTemporarilyReleased()) {
                        ClientUtils.setKeyMappingState(client.options.keyUse, true);
                    } else {
                        ClientUtils.setKeyMappingState(client.options.keyUse, false);
                    }
                }
            } else if (runtime.state == State.AOTV_TO_ROOF || runtime.state == State.AOTV_BETWEEN_PESTS) {
                // Handled in AOTV detection block above
            } else {
                ClientUtils.setKeyMappingState(client.options.keyUse, false);
            }
        }

        updateKillVacuumRetryPulse(client);
    }

    private static void processState(Minecraft client) {
        switch (runtime.state) {
            case TELEPORT_TO_PLOT -> handleTeleportToPlot(client);
            case DISCO_SPIN -> handleDiscoSpin(client);
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
        PestAotvManager.isSneakingForAotv = true;
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
            startPathToPest(client, runtime.currentTarget);
        } else if (returnState == State.APPROACH_PEST && runtime.currentTarget != null) {
            startPathToPest(client, runtime.currentTarget);
        } else if (returnState == State.FLY_TO_WAYPOINT && runtime.navigation.calculatedWaypoint != null) {
            Vec3 waypoint = runtime.navigation.calculatedWaypoint;
            PathfindingManager.startPathfind(client, (int) waypoint.x, (int) waypoint.y, (int) waypoint.z, true);
        }
    }

    // -- State handlers -------------------------------------------------------

    private static void handleEquipVacuum(Minecraft client) {
        int slot = findVacuumHotbarSlot(client);
        if (slot == -1) {
            ClientUtils.sendMessage("\u00A7cNo vacuum found in hotbar. Aborting pest destroyer.", false);
            finish(client);
            return;
        }
        runtime.vacuumSlot = slot;
        detectVacuumRange(client, slot);
        client.execute(() -> FailsafeManager.selectHotbarSlot(client, runtime.vacuumSlot));
        ClientUtils.sendDebugMessage("[PestDestroyer] Equipped vacuum (slot " + runtime.vacuumSlot + ", range " + runtime.vacuumRange + ")");

        if (isOnDiscoDestinationPlot(client)) {
            runtime.navigation.discoTargetReached = true;
            setState(State.DISCO_SPIN);
            return;
        }

        // Ensure flying
        if (!client.player.getAbilities().flying && client.player.getAbilities().mayfly) {
            setState(State.FLY_UP);
        } else if (client.player.getAbilities().flying) {
            setState(State.CHECK_NEXT);
        } else {
            ClientUtils.sendMessage("\u00A7cCannot fly. Aborting pest destroyer.", false);
            finish(client);
        }
    }

    private static void handleFlyUp(Minecraft client) {
        long elapsed = System.currentTimeMillis() - runtime.stateEnteredAt;

        if (!client.player.getAbilities().flying) {
            // Double-tap space: press(0-50ms) release(50-100ms) press(100-150ms)
            // release(150ms+)
            if (elapsed < 50) {
                ClientUtils.setKeyMappingState(client.options.keyJump, true);
            } else if (elapsed < 100) {
                ClientUtils.setKeyMappingState(client.options.keyJump, false);
            } else if (elapsed < 150) {
                ClientUtils.setKeyMappingState(client.options.keyJump, true);
            } else if (elapsed < 200) {
                ClientUtils.setKeyMappingState(client.options.keyJump, false);
            } else if (elapsed < 3000) {
                // Still not flying - retry the double-tap cycle
                runtime.stateEnteredAt = System.currentTimeMillis();
            } else {
                // Timeout - try proceeding anyway
                ClientUtils.setKeyMappingState(client.options.keyJump, false);
                setState(State.CHECK_NEXT);
            }
        } else {
            ClientUtils.setKeyMappingState(client.options.keyJump, false);
            setState(State.CHECK_NEXT);
        }
    }

    private static void handleFlyToPest(Minecraft client) {
        PestCombatCoordinator.handleFlyToPest(
                client,
                COMBAT_CONTEXT,
                TARGET_REACH_DISTANCE,
                PATHFINDER_STUCK_RETRY_TICKS,
                STATE_TIMEOUT_MS);
    }

    private static void handleApproachPest(Minecraft client) {
        PestCombatCoordinator.handleApproachPest(
                client,
                COMBAT_CONTEXT,
                TARGET_REACH_DISTANCE,
                APPROACH_TIMEOUT_TICKS);
    }

    private static void handleKillPest(Minecraft client) {
        if (isLockedOnDiscoDestinationPlot(client)) {
            handleKillPestFromDiscoDestination(client);
            return;
        }
        PestCombatCoordinator.handleKillPest(
                client,
                COMBAT_CONTEXT,
                SKULL_MISSING_CONFIRM_TICKS,
                STATE_TIMEOUT_MS);
    }

    private static void handleCheckNext(Minecraft client) {
        ClientUtils.setKeyMappingState(client.options.keyUse, false);
        ClientUtils.setKeyMappingState(client.options.keyDown, false);
        ClientUtils.setKeyMappingState(client.options.keyUp, false);
        PathfindingManager.stop();

        if (lockDiscoDestinationIfCurrentPlot(client)) {
            return;
        }

        if (tryLeaveOneOnCurrentWhitelistedPlot(client)) {
            return;
        }

        Entity pest = getNextQueuedPest(client);
        if (pest == null) {
            rebuildPestTargetQueue(client);
            pest = getNextQueuedPest(client);
        }

        if (pest != null) {
            engagePestTarget(client, pest);
        } else {
            if (isLockedOnDiscoDestinationPlot(client)) {
                handleDiscoPlotWithoutVisiblePests(client);
                return;
            }
            // No visible pests. Refresh infested plot data from tab.
            Set<String> rawInfested = PestManager.getInfestedPlotsFromTab(client);
            if (rawInfested.isEmpty()) {
                if (System.currentTimeMillis() - runtime.activatedAt < STARTUP_FINISH_GRACE_MS) {
                    ClientUtils.sendDebugMessage("[PestDestroyer] Empty infested-plot tab data during startup grace. Retrying location scan.");
                    setState(State.GET_LOCATION);
                    return;
                }
                ClientUtils.sendDebugMessage("[PestDestroyer] No infested plots in tab. Finshing.");
                finish(client);
                return;
            }
            Set<String> infested = filterSkippedInfestedPlots(PestDiscoDestinationManager.prioritizePlots(rawInfested));
            if (infested.isEmpty()) {
                ClientUtils.sendDebugMessage("PestDestroyer: all remaining infested plots are skipped leave-one plots. Finishing.");
                finish(client);
                return;
            }

            // Sync our queue with the latest tab info
            runtime.navigation.plotQueue.clear();
            runtime.navigation.plotQueue.addAll(infested);

            String firstPlot = runtime.navigation.plotQueue.get(0);
            String currentPlot = getEffectivePlot(client);

            if (!plotsEqual(firstPlot, currentPlot)) {
                // Not on the first infested plot - teleport there as prioritized.
                runtime.navigation.currentPlotIdx = 0;
                runtime.navigation.plotTpSent = false;
                runtime.navigation.getLocationAttempts = 0;
                runtime.navigation.waypointCycleCount = 0;
                ClientUtils.sendDebugMessage("[PestDestroyer] Not on first infested plot (current="
                        + currentPlot + ", target=" + firstPlot + "). Teleporting to Plot " + firstPlot);
                setState(State.TELEPORT_TO_PLOT);
                return;
            }

            // Already on the first infested plot but no pests visible - use firework
            // tracker
            ClientUtils.sendDebugMessage("[PestDestroyer] No visible pests on Plot " + firstPlot + ". Using firework tracker...");
            setState(State.GET_LOCATION);
        }
    }

    private static void rotateToTarget(Minecraft client, Entity target) {
        if (FailsafeManager.shouldSuppressPestCleanerRotation(client)) {
            return;
        }
        Vec3 targetEye = PestCombatCoordinator.buildCombatAimTarget(client, target);
        if (!isLookingAt(client, targetEye, AetherConfig.PEST_FOV_RANGE.get())) {
            RotationManager.initiateRotation(client, targetEye, TARGET_SWITCH_ROTATION_MS, AetherConfig.PEST_FOV_RANGE.get());
        }
    }

    private static boolean isLookingAt(Minecraft client, Vec3 targetPos, float tolerance) {
        if (client.player == null) return false;
        return dev.aether.util.RotationUtils.isLookingAt(
                client.player.getYRot(), client.player.getXRot(),
                client.player.getEyePosition(), targetPos, tolerance
        );
    }

    // -- New state handlers: plot TP, firework tracking, waypoint flight ------

    private static void handleTeleportToPlot(Minecraft client) {
        PestNavigationCoordinator.handleTeleportToPlot(
                client,
                runtime.navigation,
                NAVIGATION_CONTEXT,
                PLOT_TP_WAIT_MS);
    }

    private static void handleDiscoSpin(Minecraft client) {
        PathfindingManager.stop();
        ClientUtils.setKeyMappingState(client.options.keyUp, false);
        ClientUtils.setKeyMappingState(client.options.keyDown, false);
        ClientUtils.setKeyMappingState(client.options.keyLeft, false);
        ClientUtils.setKeyMappingState(client.options.keyRight, false);
        ClientUtils.setKeyMappingState(client.options.keyJump, false);
        ClientUtils.setKeyMappingState(client.options.keySprint, false);

        trustConfirmedDiscoDestination();

        if (runtime.vacuumSlot == -1) {
            runtime.vacuumSlot = findVacuumHotbarSlot(client);
        }
        if (runtime.vacuumSlot == -1) {
            ClientUtils.sendMessage("\u00A7cNo vacuum found in hotbar. Aborting pest destroyer.", false);
            finish(client);
            return;
        }
        if (((AccessorInventory) client.player.getInventory()).getSelected() != runtime.vacuumSlot) {
            client.execute(() -> FailsafeManager.selectHotbarSlot(client, runtime.vacuumSlot));
            return;
        }

        RotationManager.cancelRotation();
        rebuildPestTargetQueue(client);
        Entity pest = getNextQueuedPest(client);
        if (pest != null) {
            engagePestTarget(client, pest);
            return;
        }

        handleDiscoPlotWithoutVisiblePests(client);

        ClientUtils.setKeyMappingState(client.options.keyUse, true);
        handleDiscoPlotWithoutVisiblePests(client);
    }

    private static void handleGetLocation(Minecraft client) {
        PestNavigationCoordinator.handleGetLocation(
                client,
                runtime.navigation,
                NAVIGATION_CONTEXT,
                MAX_GET_LOCATION_ATTEMPTS,
                MAX_WAYPOINT_CYCLES,
                FIREWORK_CAPTURE_DURATION_MS,
                FIREWORK_EXTRAPOLATE_DISTANCE);
    }

    private static void handleFlyToWaypoint(Minecraft client) {
        PestNavigationCoordinator.handleFlyToWaypoint(
                client,
                runtime.navigation,
                NAVIGATION_CONTEXT,
                PATHFINDER_STUCK_RETRY_TICKS,
                STATE_TIMEOUT_MS);
    }

    private static void handleAotvBetweenPests(Minecraft client) {
        PestCombatCoordinator.handleAotvBetweenPests(
                client,
                COMBAT_CONTEXT,
                AOTV_RANGE,
                AOTV_GAP_MULTIPLIER,
                STATE_TIMEOUT_MS);
    }

    private static void handleDiscoPlotWithoutVisiblePests(Minecraft client) {
        if (shouldResolveDiscoPlotImmediately(client)) {
            ClientUtils.sendDebugMessage("PestDestroyer: disco plot locally cleared. Finishing immediately.");
            finish(client);
            return;
        }

        if (runtime.state != State.DISCO_SPIN) {
            setState(State.DISCO_SPIN);
        }
    }

    private static boolean shouldResolveDiscoPlotImmediately(Minecraft client) {
        if (!isLockedOnDiscoDestinationPlot(client)) {
            return false;
        }

        Entity currentTarget = runtime.currentTarget;
        return !runtime.killedEntities.isEmpty() || (currentTarget != null && isDeadOrDying(currentTarget));
    }

    private static void handleKillPestFromDiscoDestination(Minecraft client) {
        Entity currentTarget = runtime.currentTarget;
        if (isDeadOrDying(currentTarget)) {
            ClientUtils.setKeyMappingState(client.options.keyUse, false);
            if (currentTarget != null) {
                if (recordTrackedPestKill(client, currentTarget)) {
                    return;
                }
            }
            runtime.currentTarget = null;
            handleDiscoPlotWithoutVisiblePests(client);
            return;
        }

        if (runtime.vacuumSlot == -1) {
            runtime.vacuumSlot = findVacuumHotbarSlot(client);
        }
        if (runtime.vacuumSlot != -1
                && ((AccessorInventory) client.player.getInventory()).getSelected() != runtime.vacuumSlot) {
            client.execute(() -> FailsafeManager.selectHotbarSlot(client, runtime.vacuumSlot));
            return;
        }

        PathfindingManager.stop();
        ClientUtils.setKeyMappingState(client.options.keyUp, false);
        ClientUtils.setKeyMappingState(client.options.keyDown, false);
        ClientUtils.setKeyMappingState(client.options.keyLeft, false);
        ClientUtils.setKeyMappingState(client.options.keyRight, false);
        ClientUtils.setKeyMappingState(client.options.keyJump, false);
        ClientUtils.setKeyMappingState(client.options.keySprint, false);

        boolean retryingUse = shouldTemporarilyReleaseKillVacuum(client, true, true);
        ClientUtils.setKeyMappingState(client.options.keyUse, !retryingUse);

        if (!hasPestSkullMarkerForTarget(client, currentTarget)) {
            runtime.targetWithoutSkullTicks++;
            if (runtime.targetWithoutSkullTicks >= SKULL_MISSING_CONFIRM_TICKS) {
                ClientUtils.setKeyMappingState(client.options.keyUse, false);
                runtime.targetWithoutSkullTicks = 0;
                if (recordTrackedPestKill(client, currentTarget)) {
                    return;
                }
                runtime.currentTarget = null;
                if (isDiscoDestinationActive(client)) {
                    handleDiscoPlotWithoutVisiblePests(client);
                } else if (!switchToNextQueuedTarget(client)) {
                    handleDiscoPlotWithoutVisiblePests(client);
                }
            }
            return;
        }

        runtime.targetWithoutSkullTicks = 0;

        if (System.currentTimeMillis() - runtime.stateEnteredAt > STATE_TIMEOUT_MS) {
            runtime.currentTarget = null;
            ClientUtils.setKeyMappingState(client.options.keyUse, false);
            setState(State.DISCO_SPIN);
        }
    }

    private static boolean isLockedOnDiscoDestinationPlot(Minecraft client) {
        if (!runtime.navigation.discoTargetReached) {
            return false;
        }
        return PestDiscoDestinationManager.matchesPlot(runtime.navigation.trustedPlot)
                || PestDiscoDestinationManager.matchesPlot(getEffectivePlot(client));
    }

    private static boolean isDiscoDestinationActive(Minecraft client) {
        return isLockedOnDiscoDestinationPlot(client);
    }

    private static boolean isOnDiscoDestinationPlot(Minecraft client) {
        return PestDiscoDestinationManager.matchesPlot(getEffectivePlot(client));
    }

    private static void trustConfirmedDiscoDestination() {
        runtime.navigation.discoTargetReached = true;
        runtime.navigation.trustedPlot = PestDiscoDestinationManager.getConfiguredPlot();
        runtime.navigation.trustedPlotExpiresAt = System.currentTimeMillis() + 120_000;
    }

    private static boolean lockDiscoDestinationIfCurrentPlot(Minecraft client) {
        if (!isOnDiscoDestinationPlot(client)
                || runtime.state == State.TELEPORT_TO_PLOT
                || runtime.state == State.DISCO_SPIN
                || runtime.state == State.KILL_PEST
                || runtime.state == State.IDLE
                || runtime.state == State.FINISH) {
            return false;
        }

        PathfindingManager.stop();
        releaseMovementKeys(client);
        runtime.navigation.discoWalkStarted = false;
        runtime.navigation.discoTargetReached = true;
        runtime.navigation.discoWalkStartedAt = 0L;
        ClientUtils.sendDebugMessage("PestDestroyer: disco destination suppressed movement state " + runtime.state + ".");
        setState(State.DISCO_SPIN);
        return true;
    }

    private static void releaseMovementKeys(Minecraft client) {
        ClientUtils.setKeyMappingState(client.options.keyUp, false);
        ClientUtils.setKeyMappingState(client.options.keyDown, false);
        ClientUtils.setKeyMappingState(client.options.keyLeft, false);
        ClientUtils.setKeyMappingState(client.options.keyRight, false);
        ClientUtils.setKeyMappingState(client.options.keyJump, false);
        ClientUtils.setKeyMappingState(client.options.keySprint, false);
    }

    private static boolean isDeadOrDying(Entity entity) {
        return entity == null || entity.isRemoved()
                || (entity instanceof LivingEntity living && living.isDeadOrDying());
    }

    // -- Firework particle tracking -------------------------------------------

    /**
     * Called from the particle packet mixin when an ANGRY_VILLAGER particle
     * is received. These trace the firework trail fired by the vacuum.
     */
    public static void onFireworkParticle(double x, double y, double z) {
        PestPlotNavigator.onFireworkParticle(runtime.navigation, x, y, z);
    }

    private static boolean tryNextPlot(Minecraft client) {
        boolean shouldTeleport = PestPlotNavigator.tryNextPlot(client, runtime.navigation);
        if (shouldTeleport) {
            setState(State.TELEPORT_TO_PLOT);
            return true;
        }
        return false;
    }

    /**
     * Try to TP to a different infested plot, skipping the one we're already on.
     * 
     * @deprecated Favors staying on first plot until cleared.
     */
    @Deprecated
    private static boolean tryNextPlotExcluding(Minecraft client, String currentPlot) {
        boolean shouldTeleport = PestPlotNavigator.tryNextPlotExcluding(client, runtime.navigation, currentPlot);
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
        runtime.active = false;
        runtime.state = State.IDLE;
        runtime.currentTarget = null;
        runtime.killedEntities.clear();
        runtime.pestTargetQueue.clear();
        runtime.zeroPestTabTicks = 0;
        runtime.navigation.leaveOneSkippedPlots.clear();
        runtime.navigation.discoWalkStarted = false;
        runtime.navigation.discoTargetReached = false;
        runtime.navigation.discoWalkStartedAt = 0L;
        runtime.accountedKilledPestEntityIds.clear();
        resetKillVacuumRetry();
        runtime.navigation.lastTargetPlot = null;
        runtime.navigation.plotTpWindow = null;
        runtime.navigation.trustedPlot = null;
        runtime.navigation.trustedPlotExpiresAt = 0;
        runtime.lastPreRotateAt = 0;
        runtime.arrivedAtCurrentTargetViaAotv = false;
        runtime.lastRoofRescanAt = 0L;
        runtime.roofAotvReturnState = null;
        PathfindingManager.stop();

        // Signal back to the pest management system
        PestManager.handlePestCleaningFinished(client);
    }

    // -- Helpers --------------------------------------------------------------

    public static boolean shouldFinishForAliveCount(Minecraft client, int aliveCount) {
        if (aliveCount < 0) {
            return false;
        }
        if (aliveCount == 0) {
            return true;
        }
        if (!AetherConfig.LEAVE_ONE_PEST_ALIVE.get()) {
            return false;
        }

        Set<String> activeInfested = PestManager.getInfestedPlotsFromTab(client);
        Set<String> leaveOnePlots = new LinkedHashSet<>(runtime.navigation.leaveOneSkippedPlots);

        for (String plot : activeInfested) {
            String normalizedPlot = normalizePlot(plot);
            if (runtime.navigation.leaveOneSkippedPlots.contains(normalizedPlot)) {
                continue;
            }
            if (!isLeaveOneWhitelistedPlot(plot)) {
                return false;
            }
            leaveOnePlots.add(normalizedPlot);
        }

        return !leaveOnePlots.isEmpty() && aliveCount <= leaveOnePlots.size();
    }

    private static String getAliveFinishReason(int aliveCount) {
        if (aliveCount == 0) {
            return "0 pests alive";
        }
        return "only whitelisted leave-one plot(s) remaining";
    }

    private static boolean tryLeaveOneOnCurrentWhitelistedPlot(Minecraft client) {
        if (!AetherConfig.LEAVE_ONE_PEST_ALIVE.get()) {
            return false;
        }

        String currentPlot = getEffectivePlot(client);
        if (!isLeaveOneWhitelistedPlot(currentPlot)) {
            return false;
        }

        String normalizedPlot = normalizePlot(currentPlot);
        if (runtime.navigation.leaveOneSkippedPlots.contains(normalizedPlot)) {
            return false;
        }

        int localPests = Math.max(countAvailablePests(client), countVisiblePestSkulls(client));
        int aliveNow = PestManager.getEffectiveAliveCountNow(client);
        boolean onlyCurrentActivePlot = hasOnlyCurrentActiveInfestedPlot(client, currentPlot);
        boolean hasLocalEvidence = localPests > 0 || !runtime.killedEntities.isEmpty();
        boolean startupGraceElapsed = System.currentTimeMillis() - runtime.activatedAt >= STARTUP_FINISH_GRACE_MS;
        boolean shouldSkip = localPests == 1
                || (aliveNow == 1 && onlyCurrentActivePlot && (hasLocalEvidence || startupGraceElapsed));
        if (!shouldSkip) {
            return false;
        }

        runtime.navigation.leaveOneSkippedPlots.add(normalizedPlot);
        ClientUtils.sendDebugMessage("PestDestroyer: leaving one pest alive on whitelisted plot " + currentPlot + ".");
        moveToNextActivePlotOrFinish(client);
        return true;
    }

    private static void moveToNextActivePlotOrFinish(Minecraft client) {
        ClientUtils.setKeyMappingState(client.options.keyUse, false);
        ClientUtils.setKeyMappingState(client.options.keyDown, false);
        ClientUtils.setKeyMappingState(client.options.keyAttack, false);
        ClientUtils.setKeyMappingState(client.options.keyUp, false);
        PathfindingManager.stop();
        runtime.currentTarget = null;
        runtime.pestTargetQueue.clear();
        runtime.zeroPestTabTicks = 0;
        runtime.navigation.plotTpSent = false;
        runtime.navigation.getLocationAttempts = 0;
        runtime.navigation.waypointCycleCount = 0;
        runtime.navigation.discoWalkStarted = false;
        runtime.navigation.discoTargetReached = false;
        runtime.navigation.discoWalkStartedAt = 0L;

        Set<String> activeInfested = getFilteredInfestedPlotsFromTab(client);
        runtime.navigation.plotQueue.clear();
        runtime.navigation.plotQueue.addAll(activeInfested);
        runtime.navigation.currentPlotIdx = 0;

        if (runtime.navigation.plotQueue.isEmpty()) {
            setState(State.FINISH);
            return;
        }

        String nextPlot = runtime.navigation.plotQueue.get(0);
        String currentPlot = getEffectivePlot(client);
        if (!plotsEqual(nextPlot, currentPlot)) {
            ClientUtils.sendDebugMessage("PestDestroyer: moving from skipped plot " + currentPlot + " to plot " + nextPlot + ".");
            setState(State.TELEPORT_TO_PLOT);
            return;
        }

        setState(State.CHECK_NEXT);
    }

    private static Set<String> getFilteredInfestedPlotsFromTab(Minecraft client) {
        return filterSkippedInfestedPlots(
                PestDiscoDestinationManager.prioritizePlots(PestManager.getInfestedPlotsFromTab(client)));
    }

    private static Set<String> filterSkippedInfestedPlots(Set<String> infested) {
        Set<String> filtered = new LinkedHashSet<>();
        for (String plot : infested) {
            if (!runtime.navigation.leaveOneSkippedPlots.contains(normalizePlot(plot))) {
                filtered.add(plot);
            }
        }
        return filtered;
    }

    private static boolean hasOnlyCurrentActiveInfestedPlot(Minecraft client, String currentPlot) {
        Set<String> activeInfested = getFilteredInfestedPlotsFromTab(client);
        if (activeInfested.size() != 1) {
            return false;
        }
        return plotsEqual(activeInfested.iterator().next(), currentPlot);
    }

    private static boolean isLeaveOneWhitelistedPlot(String plot) {
        String normalizedPlot = normalizePlot(plot);
        if (normalizedPlot.isEmpty() || normalizedPlot.equals("unknown")) {
            return false;
        }

        for (String configuredPlot : AetherConfig.LEAVE_ONE_PEST_PLOTS.get()) {
            if (normalizePlot(configuredPlot).equals(normalizedPlot)) {
                return true;
            }
        }
        return false;
    }

    private static boolean plotsEqual(String first, String second) {
        return normalizePlot(first).equals(normalizePlot(second));
    }

    private static boolean containsPlot(Collection<String> plots, String plot) {
        for (String candidate : plots) {
            if (plotsEqual(candidate, plot)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizePlot(String plot) {
        if (plot == null) {
            return "";
        }
        String normalized = plot.trim().toLowerCase();
        String digits = normalized.replaceAll("\\D", "");
        return digits.isEmpty() ? normalized : digits;
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
        runtime.state = newState;
        runtime.stateEnteredAt = System.currentTimeMillis();
        runtime.stuckTicks = 0;
        runtime.approachTicks = 0;
        runtime.flyRetryAfterUnflyAt = 0L;
        if (newState == State.CHECK_NEXT || newState == State.FINISH || newState == State.IDLE) {
            runtime.arrivedAtCurrentTargetViaAotv = false;
        }
        if (newState != State.GET_LOCATION) {
            runtime.navigation.isCapturingFirework = false;
            runtime.navigation.fireworkCaptureStartedAt = 0L;
        }
        if (newState != State.AOTV_BETWEEN_PESTS) {
            runtime.aotvLastUseAt = 0L;
            runtime.aotvNextUseAt = 0L;
            runtime.aotvPostClickGraceUntil = 0L;
            runtime.aotvPendingUseAt = 0L;
            runtime.aotvLastUsePlayerX = Double.NaN;
            runtime.aotvLastUsePlayerY = Double.NaN;
            runtime.aotvLastUsePlayerZ = Double.NaN;
        }
        if (newState != State.KILL_PEST) {
            runtime.targetWithoutSkullTicks = 0;
            runtime.lastPreRotateAt = 0;
            resetKillVacuumRetry();
        }
    }

    static boolean shouldTemporarilyReleaseKillVacuum(Minecraft client, boolean vacuumReady, boolean targetInRange) {
        long now = System.currentTimeMillis();
        if (runtime.state != State.KILL_PEST || !vacuumReady || !targetInRange) {
            resetKillVacuumRetry();
            return false;
        }

        if (runtime.killVacuumRetryPressAt != 0L) {
            return true;
        }

        if (runtime.killVacuumHoldStartedAt == 0L) {
            runtime.killVacuumHoldStartedAt = now;
            return false;
        }

        if (now - runtime.killVacuumHoldStartedAt < KILL_USE_RETRY_HOLD_MS) {
            return false;
        }

        runtime.killVacuumHoldStartedAt = 0L;
        runtime.killVacuumRetryPressAt = now + KILL_USE_RETRY_RELEASE_MS;
        runtime.killVacuumReleaseUntil = runtime.killVacuumRetryPressAt + KILL_USE_RETRY_CLICK_HOLD_MS;
        ClientUtils.sendDebugMessage("PestDestroyer: retrying vacuum use");
        return true;
    }

    private static boolean isKillVacuumUseTemporarilyReleased() {
        if (runtime.killVacuumRetryPressAt == 0L) {
            return false;
        }
        return true;
    }

    private static void updateKillVacuumRetryPulse(Minecraft client) {
        if (runtime.killVacuumRetryPressAt == 0L || client.options == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now < runtime.killVacuumRetryPressAt) {
            ClientUtils.setKeyMappingState(client.options.keyUse, false);
            return;
        }

        if (now < runtime.killVacuumReleaseUntil) {
            ClientUtils.setKeyMappingState(client.options.keyUse, true);
            return;
        }

        ClientUtils.setKeyMappingState(client.options.keyUse, false);
        runtime.killVacuumRetryPressAt = 0L;
        runtime.killVacuumReleaseUntil = 0L;
    }

    private static void resetKillVacuumRetry() {
        runtime.killVacuumHoldStartedAt = 0L;
        runtime.killVacuumRetryPressAt = 0L;
        runtime.killVacuumReleaseUntil = 0L;
    }

    private static void startPathToPest(Minecraft client, Entity pest) {
        // Target slightly above pest Y so we fly above crops
        int targetY = (int) pest.getY() + 3;
        PathfindingManager.startPathfind(client,
                (int) pest.getX(), targetY, (int) pest.getZ(), true);
    }

    private static void engagePestTarget(Minecraft client, Entity pest) {
        runtime.currentTarget = pest;
        runtime.arrivedAtCurrentTargetViaAotv = false;
        runtime.navigation.waypointCycleCount = 0;
        runtime.navigation.getLocationAttempts = 0;
        resetRotationForTargetHandoff();
        double dist = client.player.distanceTo(pest);
        ClientUtils.sendDebugMessage("[PestDestroyer] Found pest at " + formatPos(pest.position())
                        + " (dist: " + String.format("%.1f", dist) + ")");

        if (isLockedOnDiscoDestinationPlot(client)) {
            runtime.aotvSlot = -1;
            setState(State.KILL_PEST);
            return;
        }

        // Check if we should use AOTV to close large gaps
        if (dist > AOTV_RANGE * AOTV_GAP_MULTIPLIER && runtime.aotvSlot == -1) {
            runtime.aotvSlot = findAOTVHotbarSlot(client);
        }

        if (dist <= runtime.vacuumRange) {
            rotateToTarget(client, pest);
            runtime.aotvSlot = -1; // Reset AOTV slot if pest is within vacuum range
            setState(State.KILL_PEST);
        } else if (dist > AOTV_RANGE * AOTV_GAP_MULTIPLIER && runtime.aotvSlot != -1 && AetherConfig.PEST_AOTV_BETWEEN.get()) {
            // Distance is too large for pathfinding - use AOTV to close gap
            runtime.aotvUseCount = 0;
            ClientUtils.sendDebugMessage("[PestDestroyer] Distance too large (" + String.format("%.1f", dist)
                            + "). Using AOTV to close gap.");
            setState(State.AOTV_BETWEEN_PESTS);
        } else {
            rotateToTarget(client, pest);
            runtime.aotvSlot = -1; // Reset if not using AOTV
            startPathToPest(client, pest);
            setState(State.FLY_TO_PEST);
        }
    }

    private static void resetRotationForTargetHandoff() {
        if (runtime.state == State.APPROACH_PEST
                || runtime.state == State.KILL_PEST
                || runtime.state == State.AOTV_BETWEEN_PESTS) {
            RotationManager.cancelRotation();
        }
    }

    private static boolean switchToNextQueuedTarget(Minecraft client) {
        if (tryLeaveOneOnCurrentWhitelistedPlot(client)) {
            return true;
        }

        Entity next = getNextQueuedPest(client);
        if (next == null) {
            rebuildPestTargetQueue(client);
            next = getNextQueuedPest(client);
        }
        if (next == null) {
            return false;
        }
        engagePestTarget(client, next);
        return true;
    }

    private static void maybePreMoveToNextTarget(Minecraft client, Entity nextTarget, double currentDist) {
        if (nextTarget == null) {
            ClientUtils.setKeyMappingState(client.options.keyUp, false);
            return;
        }

        // Keep stable vacuum lock first; only pre-move once we're comfortably on
        // target.
        if (currentDist > PRE_TRIGGER_DISTANCE) {
            ClientUtils.setKeyMappingState(client.options.keyUp, false);
            return;
        }

        double nextDist = client.player.distanceTo(nextTarget);
        if (nextDist > PRE_MOVE_MIN_NEXT_DIST) {
            ClientUtils.setKeyMappingState(client.options.keyDown, false);
            ClientUtils.setKeyMappingState(client.options.keyUp, true);
        } else {
            ClientUtils.setKeyMappingState(client.options.keyUp, false);
        }
    }

    private static Entity peekNextQueuedPest(Minecraft client) {
        return PestTargetTracker.peekNextQueuedPest(client, runtime.pestTargetQueue, runtime.killedEntities);
    }

    private static void rebuildPestTargetQueue(Minecraft client) {
        PestTargetTracker.rebuildPestTargetQueue(client, runtime.pestTargetQueue, runtime.killedEntities);
    }

    private static Entity getNextQueuedPest(Minecraft client) {
        return PestTargetTracker.getNextQueuedPest(client, runtime.pestTargetQueue, runtime.killedEntities);
    }

    /**
     * Scans all loaded entities for pest entities.
     * Priority 1: Bats/Silverfish above y=50 (always pests in the garden)
     * Priority 2: ArmorStands with pest textures (for validation/fallback)
     */
    static Entity findClosestPest(Minecraft client) {
        return PestTargetTracker.findClosestPest(client, runtime.killedEntities);
    }

    private static boolean hasPestArmorStandNearby(Minecraft client, Entity targetEntity) {
        return PestTargetTracker.hasPestArmorStandNearby(client, targetEntity);
    }

    private static int countVisiblePestSkulls(Minecraft client) {
        return PestTargetTracker.countVisiblePestSkulls(client);
    }

    private static int countAvailablePests(Minecraft client) {
        return PestTargetTracker.countAvailablePests(client, runtime.killedEntities);
    }

    private static boolean hasPestSkullMarkerForTarget(Minecraft client, Entity target) {
        return PestTargetTracker.hasPestSkullMarkerForTarget(client, target);
    }

    private static int findVacuumHotbarSlot(Minecraft client) {
        return PestLoadoutHelper.findVacuumHotbarSlot(client);
    }

    private static int findAOTVHotbarSlot(Minecraft client) {
        return PestLoadoutHelper.findAotvHotbarSlot(client);
    }

    private static void detectVacuumRange(Minecraft client, int slot) {
        runtime.vacuumRange = PestLoadoutHelper.detectVacuumRange(client, slot);
    }

    /**
     * Notify the destroyer that an entity died - used to clear current target
     * or remove from killed list tracking.
     */
    public static void onEntityDeath(Entity entity) {
        if (!runtime.active)
            return;
        if (!runtime.killedEntities.contains(entity)) {
            runtime.killedEntities.add(entity);
        }
        if (runtime.currentTarget != null && runtime.currentTarget.equals(entity)) {
            Minecraft client = Minecraft.getInstance();
            if (client.options != null) {
                ClientUtils.setKeyMappingState(client.options.keyUse, false);
                ClientUtils.setKeyMappingState(client.options.keyDown, false);
            }
            if (recordTrackedPestKill(client, entity)) {
                return;
            }
            runtime.currentTarget = null;
            setState(State.CHECK_NEXT);
        }
    }

    static boolean recordTrackedPestKill(Minecraft client, Entity entity) {
        if (entity == null) {
            return false;
        }
        if (!runtime.killedEntities.contains(entity)) {
            runtime.killedEntities.add(entity);
        }
        if (!runtime.accountedKilledPestEntityIds.add(entity.getId())) {
            return false;
        }
        if (isLockedOnDiscoDestinationPlot(client)) {
            ClientUtils.sendDebugMessage("PestDestroyer: disco pest death detected. Finishing immediately.");
            finish(client);
            return true;
        }
        PestManager.decrementPredictedAliveCount(client);
        return false;
    }

    private static String formatPos(Vec3 pos) {
        return String.format("%.0f, %.0f, %.0f", pos.x, pos.y, pos.z);
    }

    private static String getEffectivePlot(Minecraft client) {
        return PestPlotNavigator.getEffectivePlot(client, runtime.navigation);
    }
}


