package dev.aether.modules.pest.helpers

import dev.aether.config.AetherConfig
import dev.aether.modules.failsafe.FailsafeManager
import dev.aether.modules.pathfinding.PathfindingManager
import dev.aether.modules.pest.PestManager
import dev.aether.modules.rotation.RotationManager
import dev.aether.util.ClientUtils
import dev.aether.util.RotationUtils
import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3
import java.util.LinkedHashMap
import java.util.function.Predicate

// target discovery, queueing, handoff and kill accounting
class PestTargetController private constructor() {
    interface Context : PestLeaveOneController.Context { fun tryLeaveOneOnCurrentPlot(client: Minecraft): Boolean }

    companion object {
        const val AOTV_RANGE = 12.0
        const val AOTV_GAP_MULTIPLIER = 1.6
        private const val SMART_AOTV_VERTICAL_WEIGHT = 1.35
        private const val SMART_AOTV_NO_LOS_PENALTY = 6.0
        private const val SMART_AOTV_MIN_START_STOP_GAP = 3.0
        private const val TARGET_REACH_DISTANCE = 12.0
        private const val PRE_TRIGGER_RATIO = 0.67
        private const val PRE_TRIGGER_DISTANCE = TARGET_REACH_DISTANCE * PRE_TRIGGER_RATIO
        private const val PRE_MOVE_MIN_NEXT_DIST = 2.5

        @JvmStatic fun startPathToPest(client: Minecraft, pest: Entity) {
            if (AetherConfig.PEST_DESTROYER_WALK_MODE.get()) {
                val walkTarget = PestCombatCoordinator.findWalkTargetNearPest(client, pest)
                if (walkTarget == null) { ClientUtils.sendDebugMessage("[PestDestroyer] No standable block found near pest."); return }
                PathfindingManager.startPathfind(client, Mth.floor(walkTarget.x), Mth.floor(walkTarget.y), Mth.floor(walkTarget.z), false)
                return
            }
            val lassoTarget = PestHuntingController.shouldLassoTarget(client, pest)
            PathfindingManager.startPathfind(client, Mth.floor(pest.x), Mth.floor(pest.y) + if (lassoTarget) 0 else 3, Mth.floor(pest.z), true)
        }

        @JvmStatic fun engage(client: Minecraft, runtime: PestDestroyerRuntime, context: Context, pest: Entity) {
            runtime.currentTarget = pest
            runtime.flightController.reset()
            runtime.arrivedAtCurrentTargetViaAotv = false
            runtime.navigation.waypointCycleCount = 0
            runtime.navigation.getLocationAttempts = 0
            resetRotationForHandoff(runtime)
            val player = client.player ?: return
            val distance = player.distanceTo(pest)
            ClientUtils.sendDebugMessage("[PestDestroyer] Found pest at " + formatPosition(pest.position()) + " (dist: " + String.format("%.1f", distance) + ")")
            val shouldUseAotv = !AetherConfig.PEST_DESTROYER_WALK_MODE.get() && AetherConfig.PEST_AOTV_BETWEEN.get() && shouldUseAotvBetweenPests(client, pest, runtime.vacuumRange.toDouble())
            if (shouldUseAotv && runtime.aotvSlot == -1) runtime.aotvSlot = PestLoadoutHelper.findAotvHotbarSlot(client)
            if (distance.toDouble() <= PestHuntingController.handoffRange(client, pest, runtime.vacuumRange.toDouble())) {
                if (!PestHuntingController.shouldLassoTarget(client, pest)) rotateToTarget(client, pest)
                runtime.aotvSlot = -1
                beginTerminalState(client, runtime, context)
            } else if (shouldUseAotv && runtime.aotvSlot != -1) {
                runtime.aotvUseCount = 0
                ClientUtils.sendDebugMessage("[PestDestroyer] Distance too large (" + String.format("%.1f", distance) + "). Using AOTV to close gap.")
                context.setState(PestDestroyer.State.AOTV_BETWEEN_PESTS)
            } else {
                RotationManager.cancelRotation()
                runtime.aotvSlot = -1
                startPathToPest(client, pest)
                context.setState(PestDestroyer.State.FLY_TO_PEST)
            }
        }

        @JvmStatic fun beginTerminalState(client: Minecraft, runtime: PestDestroyerRuntime, context: PestLeaveOneController.Context) {
            val currentTarget = runtime.currentTarget
            val lassoTarget = PestHuntingController.shouldLassoTarget(client, currentTarget ?: return)
            PathfindingManager.stop()
            runtime.currentTargetUsesLasso = lassoTarget
            ClientUtils.sendDebugMessage("[PestDestroyer] Target route: " + (if (lassoTarget) "LASSO" else "VACUUM") + " (type=" + PestHuntingPolicy.findPestTypeIndex(client, currentTarget) + ")")
            if (lassoTarget) PestHuntingController.beginHunt(runtime, client)
            context.setState(if (lassoTarget) PestDestroyer.State.HUNT_PEST else PestDestroyer.State.KILL_PEST)
        }

        @JvmStatic fun switchToNextQueuedTarget(client: Minecraft, runtime: PestDestroyerRuntime, context: Context): Boolean {
            if (context.tryLeaveOneOnCurrentPlot(client)) return true
            rebuildQueue(client, runtime, context)
            val next = nextQueuedPest(client, runtime) ?: return false
            engage(client, runtime, context, next)
            return true
        }

        @JvmStatic fun maybePreMoveToNextTarget(client: Minecraft, nextTarget: Entity?, currentDistance: Double) {
            if (nextTarget == null || currentDistance > PRE_TRIGGER_DISTANCE) { ClientUtils.setKeyMappingState(client.options.keyUp, false); return }
            val player = client.player ?: return
            val nextDistance = player.distanceTo(nextTarget)
            ClientUtils.setKeyMappingState(client.options.keyDown, false)
            ClientUtils.setKeyMappingState(client.options.keyUp, nextDistance > PRE_MOVE_MIN_NEXT_DIST)
        }

        @JvmStatic fun peekNextQueuedPest(client: Minecraft, runtime: PestDestroyerRuntime): Entity? = PestTargetTracker.peekNextQueuedPest(client, runtime.pestTargetQueue, runtime.killedEntities.toList(), queuedTarget(client, runtime))

        @JvmStatic fun rebuildQueue(client: Minecraft, runtime: PestDestroyerRuntime, context: Context) {
            updateReservedPest(client, runtime, context)
            PestTargetTracker.rebuildPestTargetQueue(client, runtime.pestTargetQueue, runtime.killedEntities.toList(), runtime.navigation.leaveOneReservedEntityId, eligibleTarget(client, runtime))
        }

        @JvmStatic fun nextQueuedPest(client: Minecraft, runtime: PestDestroyerRuntime): Entity? = PestTargetTracker.getNextQueuedPest(client, runtime.pestTargetQueue, runtime.killedEntities.toList(), queuedTarget(client, runtime))

        private fun queuedTarget(client: Minecraft, runtime: PestDestroyerRuntime): Predicate<Entity> = eligibleTarget(client, runtime).and { entity -> entity !== runtime.currentTarget && entity.id != runtime.navigation.leaveOneReservedEntityId }

        @JvmStatic fun findClosestPest(client: Minecraft, runtime: PestDestroyerRuntime, context: Context): Entity? {
            updateReservedPest(client, runtime, context)
            return PestTargetTracker.findClosestPest(client, runtime.killedEntities.toList(), runtime.navigation.leaveOneReservedEntityId, eligibleTarget(client, runtime))
        }

        @JvmStatic fun shouldUseAotvBetweenPests(client: Minecraft?, pest: Entity?, vacuumRange: Double): Boolean {
            if (client == null || pest == null) return false
            val player = client.player ?: return false
            val directDistance = player.distanceTo(pest)
            if (!AetherConfig.PEST_SMART_AOTV_ROUTING.get()) return directDistance > AOTV_RANGE * AOTV_GAP_MULTIPLIER
            val stopDistance = getAotvStopDistance(client, pest, vacuumRange)
            val startThreshold = Math.max(AetherConfig.PEST_AOTV_START_DISTANCE.get().toDouble(), stopDistance + SMART_AOTV_MIN_START_STOP_GAP)
            if (directDistance <= stopDistance) return false
            val playerEye = player.eyePosition
            val targetEye = pest.position().add(0.0, pest.getEyeHeight(pest.pose).toDouble(), 0.0)
            val horizontalDistance = Math.hypot(targetEye.x - playerEye.x, targetEye.z - playerEye.z)
            val verticalDistance = Math.abs(targetEye.y - playerEye.y)
            var routeCost = horizontalDistance + verticalDistance * SMART_AOTV_VERTICAL_WEIGHT
            if (!ClientUtils.hasLineOfSight(player, targetEye)) routeCost += SMART_AOTV_NO_LOS_PENALTY
            return routeCost >= startThreshold
        }

        @JvmStatic fun getAotvStopDistance(client: Minecraft, pest: Entity?, vacuumRange: Double): Double {
            if (!AetherConfig.PEST_SMART_AOTV_ROUTING.get()) return AOTV_RANGE * AOTV_GAP_MULTIPLIER
            val handoffRange = if (pest == null) vacuumRange else PestHuntingController.handoffRange(client, pest, vacuumRange)
            return Math.max(AetherConfig.PEST_AOTV_STOP_DISTANCE.get().toDouble(), handoffRange)
        }

        @JvmStatic fun buildPlannedRoute(client: Minecraft?, runtime: PestDestroyerRuntime): List<Entity> {
            if (client == null || client.player == null) return emptyList()
            return PestTargetTracker.buildNearestRoute(client, runtime.killedEntities.toList(), runtime.navigation.leaveOneReservedEntityId, eligibleTarget(client, runtime), runtime.currentTarget)
        }

        private fun eligibleTarget(client: Minecraft, runtime: PestDestroyerRuntime): Predicate<Entity> {
            val onPlot = PestPlotNavigator.currentPlotFilter(client, runtime.navigation)
            return Predicate { entity -> entity != null && !runtime.deferredTargets.isDeferred(entity.id) && onPlot.test(entity) }
        }

        @JvmStatic fun hasPestSkullMarkerForTarget(client: Minecraft, target: Entity): Boolean = PestTargetTracker.hasPestSkullMarkerForTarget(client, target)

        @JvmStatic fun onEntityDeath(client: Minecraft, runtime: PestDestroyerRuntime, context: Context, entity: Entity) {
            if (!runtime.active) return
            if (!runtime.killedEntities.contains(entity)) runtime.killedEntities.add(entity)
            if (runtime.currentTarget == null || runtime.currentTarget != entity) return
            if (client.options != null) { ClientUtils.setKeyMappingState(client.options.keyUse, false); ClientUtils.setKeyMappingState(client.options.keyDown, false) }
            if (recordTrackedKill(client, runtime, context, entity)) return
            runtime.currentTarget = null
            context.setState(PestDestroyer.State.CHECK_NEXT)
        }

        @JvmStatic fun reconcileTrackedKills(client: Minecraft, runtime: PestDestroyerRuntime, context: Context): Boolean {
            if (!runtime.active || runtime.state != PestDestroyer.State.KILL_PEST) return false
            val trackedTargets = LinkedHashMap<Int, Entity>()
            if (runtime.currentTarget != null) trackedTargets[runtime.currentTarget!!.id] = runtime.currentTarget!!
            for (queuedTarget in runtime.pestTargetQueue) trackedTargets.putIfAbsent(queuedTarget.id, queuedTarget)
            var newlyKilled = 0
            var currentTargetDied = false
            for (entity in trackedTargets.values) {
                if (!isDead(entity)) continue
                if (!runtime.killedEntities.contains(entity)) runtime.killedEntities.add(entity)
                if (runtime.claimKilledPestEntityId(entity.id)) newlyKilled++
                if (entity === runtime.currentTarget) currentTargetDied = true
            }
            if (newlyKilled == 0) return false
            runtime.pestTargetQueue.removeIf { isDead(it) }
            PestManager.decrementPredictedAliveCount(client, newlyKilled)
            if (!runtime.active) return true
            if (currentTargetDied) {
                runtime.currentTarget = null
                if (client.options != null) { ClientUtils.setKeyMappingState(client.options.keyUse, false); ClientUtils.setKeyMappingState(client.options.keyDown, false) }
                PathfindingManager.stop()
                if (!switchToNextQueuedTarget(client, runtime, context)) context.setState(PestDestroyer.State.CHECK_NEXT)
            }
            return true
        }

        @JvmStatic fun recordTrackedKill(client: Minecraft, runtime: PestDestroyerRuntime, context: Context, entity: Entity?): Boolean {
            if (entity == null) return false
            if (!runtime.killedEntities.contains(entity)) runtime.killedEntities.add(entity)
            if (!runtime.claimKilledPestEntityId(entity.id)) return false
            dev.aether.modules.visuals.PestDefeatEffects.onDefeat(entity)
            PestManager.decrementPredictedAliveCount(client)
            return PestLeaveOneController.recordTrackedKill(client, runtime, context) || !runtime.active
        }

        private fun updateReservedPest(client: Minecraft, runtime: PestDestroyerRuntime, context: Context) {
            if (!PestLeaveOneController.isTrackingPlot(runtime, context.getEffectivePlot(client))) { runtime.navigation.leaveOneReservedEntityId = -1; return }
            val reservedId = runtime.navigation.leaveOneReservedEntityId
            val reservedStillAvailable = reservedId != -1 && PestTargetTracker.isAvailablePest(client, runtime.killedEntities.toList(), reservedId, eligibleTarget(client, runtime))
            val reserved = PestTargetTracker.findMostIsolatedPest(client, runtime.killedEntities.toList(), eligibleTarget(client, runtime))
            if (reserved != null) runtime.navigation.leaveOneReservedEntityId = reserved.id else if (!reservedStillAvailable) runtime.navigation.leaveOneReservedEntityId = -1
        }

        private fun isDead(entity: Entity): Boolean = entity is LivingEntity && entity.isDeadOrDying

        @JvmStatic fun isLookingAt(client: Minecraft, targetPosition: Vec3, tolerance: Float): Boolean {
            val player = client.player ?: return false
            return RotationUtils.isLookingAt(player.yRot, player.xRot, player.eyePosition, targetPosition, tolerance)
        }

        private fun rotateToTarget(client: Minecraft, target: Entity) {
            if (FailsafeManager.shouldSuppressPestCleanerRotation(client)) return
            val targetEye = PestCombatCoordinator.buildCombatAimTarget(client, target)
            if (!isLookingAt(client, targetEye, AetherConfig.PEST_FOV_RANGE.get())) RotationManager.trackRotation(client, targetEye, AetherConfig.PEST_TRACKING_SMOOTHING_MS.get(), AetherConfig.PEST_NEXT_TARGET_TURN_SPEED.get())
        }

        private fun resetRotationForHandoff(runtime: PestDestroyerRuntime) {
            if (runtime.state == PestDestroyer.State.APPROACH_PEST || runtime.state == PestDestroyer.State.KILL_PEST || runtime.state == PestDestroyer.State.AOTV_BETWEEN_PESTS) RotationManager.cancelRotation()
        }

        private fun formatPosition(position: Vec3): String = String.format("%.0f, %.0f, %.0f", position.x, position.y, position.z)
    }
}
