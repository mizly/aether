package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.pest.PestManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.RotationUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

// target discovery, queueing, handoff and kill accounting
final class PestTargetController {
    static final double AOTV_RANGE = 12.0;
    static final double AOTV_GAP_MULTIPLIER = 1.6;
    private static final double SMART_AOTV_VERTICAL_WEIGHT = 1.35;
    private static final double SMART_AOTV_NO_LOS_PENALTY = 6.0;
    private static final double SMART_AOTV_MIN_START_STOP_GAP = 3.0;

    private static final double TARGET_REACH_DISTANCE = 12.0;
    private static final double PRE_TRIGGER_RATIO = 0.67;
    private static final double PRE_TRIGGER_DISTANCE =
            TARGET_REACH_DISTANCE * PRE_TRIGGER_RATIO;
    private static final double PRE_MOVE_MIN_NEXT_DIST = 2.5;

    interface Context extends PestLeaveOneController.Context {
        boolean tryLeaveOneOnCurrentPlot(Minecraft client);
    }

    private PestTargetController() {
    }

    static void startPathToPest(Minecraft client, Entity pest) {
        // Vacuuming aims down from above, but a lasso needs a level shot.
        boolean lassoTarget = PestHuntingController.shouldLassoTarget(client, pest);
        int targetX = Mth.floor(pest.getX());
        int targetY = Mth.floor(pest.getY()) + (lassoTarget ? 0 : 3);
        int targetZ = Mth.floor(pest.getZ());
        PathfindingManager.startPathfind(
                client,
                targetX,
                targetY,
                targetZ,
                true);
    }

    static void engage(
            Minecraft client,
            PestDestroyerRuntime runtime,
            Context context,
            Entity pest) {
        runtime.currentTarget = pest;
        runtime.flightController.reset();
        runtime.arrivedAtCurrentTargetViaAotv = false;
        runtime.navigation.waypointCycleCount = 0;
        runtime.navigation.getLocationAttempts = 0;
        resetRotationForHandoff(runtime);

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
                    "[PestDestroyer] Distance too large ("
                            + String.format("%.1f", distance)
                            + "). Using AOTV to close gap.");
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
        if (context.tryLeaveOneOnCurrentPlot(client)) {
            return true;
        }

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
                client, runtime.pestTargetQueue, runtime.killedEntities, queuedTarget(client, runtime));
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
                client, runtime.pestTargetQueue, runtime.killedEntities, queuedTarget(client, runtime));
    }

    private static Predicate<Entity> queuedTarget(Minecraft client, PestDestroyerRuntime runtime) {
        return eligibleTarget(client, runtime).and(entity -> entity != runtime.currentTarget
                && entity.getId() != runtime.navigation.leaveOneReservedEntityId);
    }

    static Entity findClosestPest(
            Minecraft client,
            PestDestroyerRuntime runtime,
            Context context) {
        updateReservedPest(client, runtime, context);
        return PestTargetTracker.findClosestPest(
                client,
                runtime.killedEntities,
                runtime.navigation.leaveOneReservedEntityId,
                eligibleTarget(client, runtime));
    }

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
        double startThreshold = Math.max(
                AetherConfig.PEST_AOTV_START_DISTANCE.get(),
                stopDistance + SMART_AOTV_MIN_START_STOP_GAP);
        if (directDistance <= stopDistance) {
            return false;
        }

        Vec3 playerEye = client.player.getEyePosition();
        Vec3 targetEye = pest.position().add(0, pest.getEyeHeight(pest.getPose()), 0);
        double horizontalDistance = Math.hypot(
                targetEye.x - playerEye.x,
                targetEye.z - playerEye.z);
        double verticalDistance = Math.abs(targetEye.y - playerEye.y);
        double routeCost = horizontalDistance + verticalDistance * SMART_AOTV_VERTICAL_WEIGHT;
        if (!ClientUtils.hasLineOfSight(client.player, targetEye)) {
            routeCost += SMART_AOTV_NO_LOS_PENALTY;
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

    static List<Entity> buildPlannedRoute(
            Minecraft client,
            PestDestroyerRuntime runtime) {
        if (client == null || client.player == null) {
            return List.of();
        }
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
                    AetherConfig.PEST_TRACKING_SMOOTHING_MS.get(),
                    AetherConfig.PEST_NEXT_TARGET_TURN_SPEED.get());
        }
    }

    private static void resetRotationForHandoff(PestDestroyerRuntime runtime) {
        if (runtime.state == PestDestroyer.State.APPROACH_PEST
                || runtime.state == PestDestroyer.State.KILL_PEST
                || runtime.state == PestDestroyer.State.AOTV_BETWEEN_PESTS) {
            RotationManager.cancelRotation();
        }
    }

    private static String formatPosition(Vec3 position) {
        return String.format(
                "%.0f, %.0f, %.0f",
                position.x,
                position.y,
                position.z);
    }
}
