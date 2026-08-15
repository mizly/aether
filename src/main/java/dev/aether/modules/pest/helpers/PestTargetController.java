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
import java.util.Map;
import java.util.function.Predicate;

/** Owns target discovery, queueing, handoff, and kill accounting. */
final class PestTargetController {
    static final double AOTV_RANGE = 12.0;
    static final double AOTV_GAP_MULTIPLIER = 1.6;

    private static final int TARGET_SWITCH_ROTATION_MS = 90;
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

        if (distance > AOTV_RANGE * AOTV_GAP_MULTIPLIER && runtime.aotvSlot == -1) {
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
        } else if (distance > AOTV_RANGE * AOTV_GAP_MULTIPLIER
                && runtime.aotvSlot != -1
                && AetherConfig.PEST_AOTV_BETWEEN.get()) {
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

        Entity next = nextQueuedPest(client, runtime);
        if (next == null) {
            rebuildQueue(client, runtime, context);
            next = nextQueuedPest(client, runtime);
        }
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
        return PestTargetTracker.findClosestPest(
                client,
                runtime.killedEntities,
                runtime.navigation.leaveOneReservedEntityId,
                eligibleTarget(client, runtime));
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
            context.setState(PestDestroyer.State.CHECK_NEXT);
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
        return entity.isRemoved()
                || entity instanceof LivingEntity living && living.isDeadOrDying();
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
            RotationManager.initiatePestRotation(
                    client,
                    targetEye,
                    TARGET_SWITCH_ROTATION_MS,
                    AetherConfig.PEST_FOV_RANGE.get());
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
