package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.pest.PestManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.RotationUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/** Owns target discovery, queueing, handoff, and kill accounting. */
final class PestTargetController {
    static final double AOTV_RANGE = 12.0;
    static final double AOTV_GAP_MULTIPLIER = 1.6;

    // Smart routing estimates normal travel cost instead of using only one
    // hard 3D-distance cutoff. Vertical travel is deliberately weighted a
    // little more because flying up/down between pests is slower than covering
    // the same horizontal distance. Lack of line-of-sight raises the bar for
    // AOTV, since the cleaner may need to reposition before a teleport works.
    private static final double SMART_AOTV_VERTICAL_WEIGHT = 1.35;
    private static final double SMART_AOTV_NO_LOS_PENALTY = 6.0;
    private static final double SMART_AOTV_MIN_START_STOP_GAP = 3.0;

    private static final int TARGET_SWITCH_ROTATION_MS = 90;
    private static final double TARGET_REACH_DISTANCE = 12.0;
    private static final double PRE_TRIGGER_RATIO = 0.67;
    private static final double PRE_TRIGGER_DISTANCE =
            TARGET_REACH_DISTANCE * PRE_TRIGGER_RATIO;
    private static final double PRE_MOVE_MIN_NEXT_DIST = 2.5;
    private static final long ONE_TAP_RECHECK_GRACE_MS = 750L;
    // Normal vacuum navigation should not chase the pest's live airborne Y
    // coordinate. Garden pests can jump sharply and otherwise make every
    // repath send the player climbing after a temporary mid-air position.
    private static final int PEST_GROUND_SCAN_DEPTH = 24;
    private static final double VACUUM_CHASE_HEIGHT_ABOVE_GROUND = 5.0;

    interface Context extends PestLeaveOneController.Context {
        boolean tryLeaveOneOnCurrentPlot(Minecraft client);
    }

    private PestTargetController() {
    }

    static void startPathToPest(Minecraft client, Entity pest) {
        // Lasso hunting still wants the pest's live height. Vacuum navigation,
        // however, must not chase a temporary jump into the sky. Anchor the
        // movement Y to the collision surface below the pest and keep only a
        // modest flight height above that ground. The combat camera continues
        // aiming at the pest's real position, so an airborne pest is still
        // tracked without making the entire player path follow it vertically.
        boolean lassoTarget = PestHuntingController.shouldLassoTarget(client, pest);
        int targetX = Mth.floor(pest.getX());
        int targetZ = Mth.floor(pest.getZ());
        int targetY;
        if (lassoTarget) {
            targetY = Mth.floor(pest.getY());
        } else {
            double groundSurfaceY = findGroundSurfaceBelowPest(client, pest);
            if (Double.isFinite(groundSurfaceY)) {
                targetY = Mth.floor(groundSurfaceY + VACUUM_CHASE_HEIGHT_ABOVE_GROUND);
            } else {
                // Preserve the old behavior as a fallback for unloaded/void
                // columns where no collision surface can be identified.
                targetY = Mth.floor(pest.getY()) + 3;
            }
        }
        PathfindingManager.startPathfind(
                client,
                targetX,
                targetY,
                targetZ,
                true);
    }

    private static double findGroundSurfaceBelowPest(Minecraft client, Entity pest) {
        if (client == null || client.level == null || pest == null) {
            return Double.NaN;
        }

        int x = Mth.floor(pest.getX());
        int z = Mth.floor(pest.getZ());
        int startY = Mth.floor(pest.getY());
        int endY = startY - PEST_GROUND_SCAN_DEPTH;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int y = startY; y >= endY; y--) {
            pos.set(x, y, z);
            VoxelShape shape = client.level.getBlockState(pos).getCollisionShape(client.level, pos);
            if (shape.isEmpty()) {
                continue;
            }
            return y + shape.bounds().maxY;
        }
        return Double.NaN;
    }

    static void engage(
            Minecraft client,
            PestDestroyerRuntime runtime,
            Context context,
            Entity pest) {
        runtime.currentTarget = pest;
        runtime.lockedTargetEntityId = AetherConfig.PEST_TARGET_LOCK.get() ? pest.getId() : -1;
        runtime.arrivedAtCurrentTargetViaAotv = false;
        runtime.navigation.waypointCycleCount = 0;
        runtime.navigation.getLocationAttempts = 0;
        resetRotationForHandoff();

        double distance = client.player.distanceTo(pest);
        ClientUtils.sendDebugMessage(
                "[PestDestroyer] Found pest at "
                        + formatPosition(pest.position())
                        + " (dist: "
                        + String.format("%.1f", distance)
                        + ")");

        boolean shouldUseAotv = AetherConfig.PEST_AOTV_BETWEEN.get()
                && shouldUseAotvBetweenPests(client, pest, runtime.vacuumRange);
        if (shouldUseAotv && runtime.aotvSlot == -1) {
            runtime.aotvSlot = PestLoadoutHelper.findAotvHotbarSlot(client);
        }

        if (distance <= PestHuntingController.handoffRange(client, pest, runtime.vacuumRange)) {
            // Lasso hunting performs its own precise, short aim immediately
            // before the stun/throw. Starting a second generic rotation here
            // made the cleaner stare at the pest before the hunt began.
            if (!PestHuntingController.shouldLassoTarget(client, pest)) {
                rotateToTarget(client, pest);
            }
            runtime.aotvSlot = -1;
            beginTerminalState(client, runtime, context);
        } else if (shouldUseAotv && runtime.aotvSlot != -1) {
            runtime.aotvUseCount = 0;
            ClientUtils.sendDebugMessage(
                    "[PestDestroyer] Smart route chose AOTV ("
                            + describeAotvDecision(client, pest, runtime.vacuumRange)
                            + ").");
            context.setState(PestDestroyer.State.AOTV_BETWEEN_PESTS);
        } else {
            // The fly executor owns the camera while approaching. Pre-rotating
            // here left two rotation systems fighting over yaw/pitch, producing
            // the upward look and last-moment whip visible in the recording.
            RotationManager.cancelRotation();
            runtime.aotvSlot = -1;
            startPathToPest(client, pest);
            context.setState(PestDestroyer.State.FLY_TO_PEST);
        }
    }

    static void beginTerminalState(
            Minecraft client,
            PestDestroyerRuntime runtime,
            PestLeaveOneController.Context context) {
        boolean lassoTarget = PestHuntingController.shouldLassoTarget(client, runtime.currentTarget);
        PathfindingManager.stop();
        runtime.currentTargetUsesLasso = lassoTarget;
        runtime.resetOneTapTracking();
        if (!lassoTarget && AetherConfig.PEST_ONE_TAP_PESTS.get()) {
            PestCombatCoordinator.prepareOneTapTarget(client, runtime, runtime.currentTarget);
        }
        ClientUtils.sendDebugMessage("[PestDestroyer] Target route: "
                + (lassoTarget ? "LASSO" : "VACUUM")
                + " (type=" + PestHuntingPolicy.findPestTypeIndex(client, runtime.currentTarget) + ")");
        if (lassoTarget) {
            PestHuntingController.beginHunt(runtime, client);
        }
        context.setState(lassoTarget ? PestDestroyer.State.HUNT_PEST : PestDestroyer.State.KILL_PEST);
    }

    static boolean switchToNextQueuedTarget(
            Minecraft client,
            PestDestroyerRuntime runtime,
            Context context) {
        if (AetherConfig.PEST_TARGET_LOCK.get() && runtime.lockedTargetEntityId != -1) {
            Entity locked = PestTargetTracker.findAvailablePestById(
                    client, runtime.killedEntities, runtime.lockedTargetEntityId, eligibleTarget(client, runtime));
            if (locked != null) {
                ClientUtils.sendDebugMessage("[PestDestroyer] Target Lock keeping pest "
                        + locked.getId() + " despite distance-order changes.");
                engage(client, runtime, context, locked);
                return true;
            }
            runtime.lockedTargetEntityId = -1;
        }

        if (context.tryLeaveOneOnCurrentPlot(client)) {
            return true;
        }

        // Rebuild on every handoff so target order is based on where the player
        // is now, not where they were when an older queue was created. This
        // prevents crossing the plot for a stale queued target while another
        // available pest is already nearby.
        rebuildQueue(client, runtime, context);
        Entity next = nextQueuedPest(client, runtime);
        if (next == null) {
            return false;
        }
        engage(client, runtime, context, next);
        return true;
    }

    static void maybePreMoveToNextTarget(
            Minecraft client,
            Entity nextTarget,
            double currentDistance) {
        if (nextTarget == null || currentDistance > PRE_TRIGGER_DISTANCE) {
            ClientUtils.setKeyMappingState(client.options.keyUp, false);
            return;
        }
        double nextDistance = client.player.distanceTo(nextTarget);
        ClientUtils.setKeyMappingState(client.options.keyDown, false);
        ClientUtils.setKeyMappingState(
                client.options.keyUp,
                nextDistance > PRE_MOVE_MIN_NEXT_DIST);
    }

    static Entity peekNextQueuedPest(Minecraft client, PestDestroyerRuntime runtime) {
        return PestTargetTracker.peekNextQueuedPest(
                client, runtime.pestTargetQueue, runtime.killedEntities, eligibleTarget(client, runtime));
    }

    static void rebuildQueue(
            Minecraft client,
            PestDestroyerRuntime runtime,
            Context context) {
        updateReservedPest(client, runtime, context);
        PestTargetTracker.rebuildPestTargetQueue(
                client,
                runtime.pestTargetQueue,
                runtime.killedEntities,
                runtime.navigation.leaveOneReservedEntityId,
                eligibleTarget(client, runtime));
    }

    static Entity nextQueuedPest(Minecraft client, PestDestroyerRuntime runtime) {
        return PestTargetTracker.getNextQueuedPest(
                client, runtime.pestTargetQueue, runtime.killedEntities, eligibleTarget(client, runtime));
    }

    static Entity findClosestPest(
            Minecraft client,
            PestDestroyerRuntime runtime,
            Context context) {
        updateReservedPest(client, runtime, context);
        if (AetherConfig.PEST_TARGET_LOCK.get() && runtime.lockedTargetEntityId != -1) {
            Entity locked = PestTargetTracker.findAvailablePestById(
                    client, runtime.killedEntities, runtime.lockedTargetEntityId, eligibleTarget(client, runtime));
            if (locked != null && locked.getId() != runtime.navigation.leaveOneReservedEntityId) {
                return locked;
            }
            runtime.lockedTargetEntityId = -1;
        }
        return PestTargetTracker.findClosestPest(
                client,
                runtime.killedEntities,
                runtime.navigation.leaveOneReservedEntityId,
                eligibleTarget(client, runtime));
    }

    static java.util.List<Entity> buildPlannedRoute(
            Minecraft client,
            PestDestroyerRuntime runtime) {
        if (client == null || client.player == null) {
            return java.util.List.of();
        }
        // Rendering must be read-only: use the reservation already maintained
        // by the target-selection state machine instead of changing it here.
        return PestTargetTracker.buildNearestRoute(
                client,
                runtime.killedEntities,
                runtime.navigation.leaveOneReservedEntityId,
                eligibleTarget(client, runtime),
                runtime.currentTarget);
    }

    private static Predicate<Entity> eligibleTarget(Minecraft client, PestDestroyerRuntime runtime) {
        Predicate<Entity> onPlot = PestPlotNavigator.currentPlotFilter(client, runtime.navigation);
        return entity -> entity != null
                && !runtime.deferredTargets.isDeferred(entity.getId())
                && onPlot.test(entity);
    }

    static boolean hasPestSkullMarkerForTarget(Minecraft client, Entity target) {
        return PestTargetTracker.hasPestSkullMarkerForTarget(client, target);
    }

    static void onEntityDeath(
            Minecraft client,
            PestDestroyerRuntime runtime,
            Context context,
            Entity entity) {
        if (!runtime.active) {
            return;
        }
        runtime.oneTapAssumedKilledAt.remove(entity.getId());
        if (runtime.lockedTargetEntityId == entity.getId()) {
            runtime.lockedTargetEntityId = -1;
        }
        if (!runtime.killedEntities.contains(entity)) {
            runtime.killedEntities.add(entity);
        }
        if (runtime.currentTarget == null || !runtime.currentTarget.equals(entity)) {
            return;
        }
        if (client.options != null) {
            ClientUtils.setKeyMappingState(client.options.keyUse, false);
            ClientUtils.setKeyMappingState(client.options.keyDown, false);
        }
        if (recordTrackedKill(client, runtime, context, entity)) {
            return;
        }
        runtime.currentTarget = null;
        context.setState(PestDestroyer.State.CHECK_NEXT);
    }

    static boolean reconcileTrackedKills(
            Minecraft client,
            PestDestroyerRuntime runtime,
            Context context) {
        // Hunting counts catches from chat; a pest that merely despawned or
        // wandered off is not a kill and must not drain the alive count.
        if (!runtime.active || runtime.state != PestDestroyer.State.KILL_PEST) {
            return false;
        }

        Map<Integer, Entity> trackedTargets = new LinkedHashMap<>();
        if (runtime.currentTarget != null) {
            trackedTargets.put(runtime.currentTarget.getId(), runtime.currentTarget);
        }
        for (Entity queuedTarget : runtime.pestTargetQueue) {
            trackedTargets.putIfAbsent(queuedTarget.getId(), queuedTarget);
        }

        int newlyKilled = 0;
        boolean currentTargetDied = false;
        for (Entity entity : trackedTargets.values()) {
            if (!isDead(entity)) {
                continue;
            }
            runtime.oneTapAssumedKilledAt.remove(entity.getId());
            if (runtime.lockedTargetEntityId == entity.getId()) {
                runtime.lockedTargetEntityId = -1;
            }
            if (!runtime.killedEntities.contains(entity)) {
                runtime.killedEntities.add(entity);
            }
            if (runtime.claimKilledPestEntityId(entity.getId())) {
                newlyKilled++;
            }
            if (entity == runtime.currentTarget) {
                currentTargetDied = true;
            }
        }

        if (newlyKilled == 0) {
            return false;
        }

        runtime.pestTargetQueue.removeIf(PestTargetController::isDead);
        PestManager.decrementPredictedAliveCount(client, newlyKilled);
        if (!runtime.active) {
            return true;
        }

        if (currentTargetDied) {
            runtime.currentTarget = null;
            if (client.options != null) {
                ClientUtils.setKeyMappingState(client.options.keyUse, false);
                ClientUtils.setKeyMappingState(client.options.keyDown, false);
            }
            PathfindingManager.stop();
            // Bouncing off CHECK_NEXT costs a full tick parked on the corpse before
            // the next pest is even picked; choose it here so the swing starts now.
            if (!switchToNextQueuedTarget(client, runtime, context)) {
                context.setState(PestDestroyer.State.CHECK_NEXT);
            }
        }
        return true;
    }

    static boolean recordTrackedKill(
            Minecraft client,
            PestDestroyerRuntime runtime,
            Context context,
            Entity entity) {
        if (entity == null) {
            return false;
        }
        if (runtime.lockedTargetEntityId == entity.getId()) {
            runtime.lockedTargetEntityId = -1;
        }
        if (!runtime.killedEntities.contains(entity)) {
            runtime.killedEntities.add(entity);
        }
        if (!runtime.claimKilledPestEntityId(entity.getId())) {
            return false;
        }
        dev.aether.modules.visuals.PestDefeatEffects.onDefeat(entity);
        PestManager.decrementPredictedAliveCount(client);
        return PestLeaveOneController.recordTrackedKill(client, runtime, context)
                || !runtime.active;
    }

    /**
     * One Tap Pests intentionally moves on before server-side death is final.
     * When a route is about to run dry, re-check those optimistic handoffs and
     * make any still-live, still-eligible pest targetable again.
     */
    static int reviveVisibleAssumedOneTapTargets(
            Minecraft client,
            PestDestroyerRuntime runtime,
            Context context) {
        if (!AetherConfig.PEST_ONE_TAP_PESTS.get()
                || runtime.oneTapAssumedKilledAt.isEmpty()
                || client == null
                || client.player == null) {
            return 0;
        }

        long now = System.currentTimeMillis();
        Predicate<Entity> eligible = eligibleTarget(client, runtime);
        int revived = 0;
        for (Entity pest : PestTargetTracker.getLoadedPests(client)) {
            Long assumedAt = runtime.oneTapAssumedKilledAt.get(pest.getId());
            if (assumedAt == null || now - assumedAt < ONE_TAP_RECHECK_GRACE_MS) {
                continue;
            }
            if (pest.isRemoved() || isDead(pest)) {
                runtime.oneTapAssumedKilledAt.remove(pest.getId());
                continue;
            }
            if (!eligible.test(pest)) {
                continue;
            }

            int id = pest.getId();
            boolean wasIgnored = runtime.killedEntities.removeIf(entity -> entity.getId() == id);
            boolean wasAccounted = runtime.accountedKilledPestEntityIds.remove(id);
            runtime.oneTapAssumedKilledAt.remove(id);
            if (wasIgnored || wasAccounted) {
                revived++;
                ClientUtils.sendDebugMessage(
                        "[PestDestroyer] One Tap recheck found surviving pest " + id
                                + ". Re-adding it to the route.");
            }
        }

        if (revived > 0) {
            PestManager.restorePredictedAliveCount(client, revived);
            rebuildQueue(client, runtime, context);
        }
        return revived;
    }

    static boolean waitingForOneTapRecheck(PestDestroyerRuntime runtime) {
        if (!AetherConfig.PEST_ONE_TAP_PESTS.get()
                || runtime.oneTapAssumedKilledAt.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        return runtime.oneTapAssumedKilledAt.values().stream()
                .anyMatch(assumedAt -> now - assumedAt < ONE_TAP_RECHECK_GRACE_MS);
    }

    private static void updateReservedPest(
            Minecraft client,
            PestDestroyerRuntime runtime,
            Context context) {
        if (!PestLeaveOneController.isTrackingPlot(
                runtime, context.getEffectivePlot(client))) {
            runtime.navigation.leaveOneReservedEntityId = -1;
            return;
        }
        int reservedId = runtime.navigation.leaveOneReservedEntityId;
        boolean reservedStillAvailable = reservedId != -1
                && PestTargetTracker.isAvailablePest(
                        client, runtime.killedEntities, reservedId, eligibleTarget(client, runtime));
        Entity reserved = PestTargetTracker.findMostIsolatedPest(
                client, runtime.killedEntities, eligibleTarget(client, runtime));
        if (reserved != null) {
            runtime.navigation.leaveOneReservedEntityId = reserved.getId();
        } else if (!reservedStillAvailable) {
            runtime.navigation.leaveOneReservedEntityId = -1;
        }
    }

    /**
     * Returns whether another AOTV hop is likely to beat ordinary movement.
     * With Smart AOTV disabled this preserves the original ~19.2 block cutoff.
     */
    static boolean shouldUseAotvBetweenPests(
            Minecraft client,
            Entity pest,
            double vacuumRange) {
        if (client == null || client.player == null || pest == null) {
            return false;
        }

        double directDistance = client.player.distanceTo(pest);
        if (!AetherConfig.PEST_SMART_AOTV_ROUTING.get()) {
            return directDistance > AOTV_RANGE * AOTV_GAP_MULTIPLIER;
        }

        double stopDistance = getAotvStopDistance(client, pest, vacuumRange);
        double configuredStart = AetherConfig.PEST_AOTV_START_DISTANCE.get();
        double startThreshold = Math.max(
                configuredStart,
                stopDistance + SMART_AOTV_MIN_START_STOP_GAP);
        if (directDistance <= stopDistance) {
            return false;
        }

        Vec3 playerEye = client.player.getEyePosition();
        Vec3 targetEye = pest.position().add(0, pest.getEyeHeight(pest.getPose()), 0);
        double dx = targetEye.x - playerEye.x;
        double dz = targetEye.z - playerEye.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        double verticalDistance = Math.abs(targetEye.y - playerEye.y);

        // Horizontal distance is ordinary travel. Vertical movement gets extra
        // weight because changing altitude costs more time during pest routing.
        double routeCost = horizontalDistance + (verticalDistance * SMART_AOTV_VERTICAL_WEIGHT);

        // A blocked line makes an immediate AOTV less attractive. This is a
        // penalty rather than a hard rejection because the existing AOTV state
        // can still rise briefly to regain line-of-sight when the pest is above.
        boolean hasLineOfSight = ClientUtils.hasLineOfSight(client.player, targetEye);
        if (!hasLineOfSight) {
            routeCost -= SMART_AOTV_NO_LOS_PENALTY;
        }

        return routeCost >= startThreshold;
    }

    static double getAotvStopDistance(
            Minecraft client,
            Entity pest,
            double vacuumRange) {
        if (!AetherConfig.PEST_SMART_AOTV_ROUTING.get()) {
            return AOTV_RANGE * AOTV_GAP_MULTIPLIER;
        }

        double handoffRange = pest == null
                ? vacuumRange
                : PestHuntingController.handoffRange(client, pest, vacuumRange);
        return Math.max(AetherConfig.PEST_AOTV_STOP_DISTANCE.get(), handoffRange);
    }

    private static String describeAotvDecision(
            Minecraft client,
            Entity pest,
            double vacuumRange) {
        if (!AetherConfig.PEST_SMART_AOTV_ROUTING.get()) {
            return "legacy distance=" + String.format("%.1f", client.player.distanceTo(pest));
        }

        Vec3 playerEye = client.player.getEyePosition();
        Vec3 targetEye = pest.position().add(0, pest.getEyeHeight(pest.getPose()), 0);
        double dx = targetEye.x - playerEye.x;
        double dz = targetEye.z - playerEye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double vertical = Math.abs(targetEye.y - playerEye.y);
        boolean los = ClientUtils.hasLineOfSight(client.player, targetEye);
        double routeCost = horizontal + (vertical * SMART_AOTV_VERTICAL_WEIGHT)
                - (los ? 0.0 : SMART_AOTV_NO_LOS_PENALTY);
        double stop = getAotvStopDistance(client, pest, vacuumRange);
        double start = Math.max(
                AetherConfig.PEST_AOTV_START_DISTANCE.get(),
                stop + SMART_AOTV_MIN_START_STOP_GAP);
        int estimatedHops = Math.max(1,
                (int) Math.ceil(Math.max(0.0, client.player.distanceTo(pest) - stop) / AOTV_RANGE));
        return "dist=" + String.format("%.1f", client.player.distanceTo(pest))
                + ", horizontal=" + String.format("%.1f", horizontal)
                + ", vertical=" + String.format("%.1f", vertical)
                + ", routeCost=" + String.format("%.1f", routeCost)
                + "/" + String.format("%.1f", start)
                + ", LOS=" + los
                + ", hops~" + estimatedHops;
    }

    private static boolean isDead(Entity entity) {
        return entity instanceof LivingEntity living && living.isDeadOrDying();
    }

    static boolean isLookingAt(Minecraft client, Vec3 targetPosition, float tolerance) {
        if (client.player == null) {
            return false;
        }
        return RotationUtils.isLookingAt(
                client.player.getYRot(),
                client.player.getXRot(),
                client.player.getEyePosition(),
                targetPosition,
                tolerance);
    }

    private static void rotateToTarget(Minecraft client, Entity target) {
        if (FailsafeManager.shouldSuppressPestCleanerRotation(client)) {
            return;
        }
        Vec3 targetEye = PestCombatCoordinator.buildCombatAimTarget(client, target);
        if (!isLookingAt(client, targetEye, AetherConfig.PEST_FOV_RANGE.get())) {
            RotationManager.trackRotation(
                    client,
                    targetEye,
                    TARGET_SWITCH_ROTATION_MS,
                    AetherConfig.PEST_FOV_RANGE.get(),
                    AetherConfig.PEST_NEXT_TARGET_TURN_SPEED.get());
        }
    }

    private static void resetRotationForHandoff() {
        // A tracking rotation from the previous pest may still be alive when
        // CHECK_NEXT engages a replacement. initiateRotation() intentionally
        // refuses to interrupt an active rotation, so leaving the old one here
        // can make the camera keep aiming at the dead pest's last position.
        RotationManager.cancelRotation();
    }

    private static String formatPosition(Vec3 position) {
        return String.format(
                "%.0f, %.0f, %.0f",
                position.x,
                position.y,
                position.z);
    }
}
