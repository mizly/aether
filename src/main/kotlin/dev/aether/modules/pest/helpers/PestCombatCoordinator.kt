package dev.aether.modules.pest.helpers

import dev.aether.config.AetherConfig
import dev.aether.mixin.AccessorInventory
import dev.aether.modules.failsafe.FailsafeManager
import dev.aether.modules.gear.GearManager
import dev.aether.modules.pathfinding.PathfindingManager
import dev.aether.modules.pathfinding.execution.FlightPathClearance
import dev.aether.modules.pathfinding.etherwarp.EtherwarpHelper
import dev.aether.modules.pathfinding.movement.WalkabilityChecker
import dev.aether.modules.rotation.RotationManager
import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.ArrayList
import java.util.Comparator
import java.util.concurrent.ThreadLocalRandom

class PestCombatCoordinator private constructor() {

    interface Context {
            fun runtime(): PestDestroyerRuntime
            fun getCurrentTarget(): Entity? = runtime().currentTarget
            fun getVacuumSlot(): Int = if (runtime().killVacuumSlot >= 0) runtime().killVacuumSlot else runtime().vacuumSlot
            fun setVacuumSlot(slot: Int) { runtime().vacuumSlot = slot }
            fun getVacuumRange(): Double = runtime().vacuumRange.toDouble()
            fun getAotvSlot(): Int = runtime().aotvSlot
            fun setAotvSlot(slot: Int) { runtime().aotvSlot = slot }
            fun getAotvUseCount(): Int = runtime().aotvUseCount
            fun setAotvUseCount(count: Int) { runtime().aotvUseCount = count }
            fun getAotvLastUseAt(): Long = runtime().aotvLastUseAt
            fun setAotvLastUseAt(value: Long) { runtime().aotvLastUseAt = value }
            fun getAotvNextUseAt(): Long = runtime().aotvNextUseAt
            fun setAotvNextUseAt(value: Long) { runtime().aotvNextUseAt = value }
            fun getAotvPostClickGraceUntil(): Long = runtime().aotvPostClickGraceUntil
            fun setAotvPostClickGraceUntil(value: Long) { runtime().aotvPostClickGraceUntil = value }
            fun getAotvPendingUseAt(): Long = runtime().aotvPendingUseAt
            fun setAotvPendingUseAt(value: Long) { runtime().aotvPendingUseAt = value }
            fun getAotvAimStartedAt(): Long = runtime().aotvAimStartedAt
            fun setAotvAimStartedAt(value: Long) { runtime().aotvAimStartedAt = value }
            fun getAotvLastUsePlayerX(): Double = runtime().aotvLastUsePlayerX
            fun setAotvLastUsePlayerX(value: Double) { runtime().aotvLastUsePlayerX = value }
            fun getAotvLastUsePlayerY(): Double = runtime().aotvLastUsePlayerY
            fun setAotvLastUsePlayerY(value: Double) { runtime().aotvLastUsePlayerY = value }
            fun getAotvLastUsePlayerZ(): Double = runtime().aotvLastUsePlayerZ
            fun setAotvLastUsePlayerZ(value: Double) { runtime().aotvLastUsePlayerZ = value }
            fun didArriveAtCurrentTargetViaAotv(): Boolean = runtime().arrivedAtCurrentTargetViaAotv
            fun setArrivedAtCurrentTargetViaAotv(value: Boolean) { runtime().arrivedAtCurrentTargetViaAotv = value }
            fun getStateEnteredAt(): Long = runtime().stateEnteredAt
            fun setStateEnteredAt(value: Long) { runtime().stateEnteredAt = value }
            fun getStuckTicks(): Int = runtime().stuckTicks
            fun setStuckTicks(value: Int) { runtime().stuckTicks = value }
            fun getApproachTicks(): Int = runtime().approachTicks
            fun setApproachTicks(value: Int) { runtime().approachTicks = value }
            fun getTargetWithoutSkullTicks(): Int = runtime().targetWithoutSkullTicks
            fun setTargetWithoutSkullTicks(value: Int) { runtime().targetWithoutSkullTicks = value }
            fun isLookingAt(client: Minecraft, targetPos: Vec3, tolerance: Float): Boolean
            fun setState(state: PestDestroyer.State)
            fun beginTerminalState(client: Minecraft)
            fun startPathToPest(client: Minecraft, pest: Entity)
            fun switchToNextQueuedTarget(client: Minecraft): Boolean
            fun peekNextQueuedPest(client: Minecraft): Entity?
            fun maybePreMoveToNextTarget(client: Minecraft, nextTarget: Entity, currentDist: Double)
            fun hasPestSkullMarkerForTarget(client: Minecraft, target: Entity): Boolean
            fun markKilled(entity: Entity)
            fun deferTarget(entity: Entity)
            fun recordTrackedPestKill(client: Minecraft, entity: Entity): Boolean
            fun shouldTemporarilyReleaseKillVacuum(client: Minecraft, vacuumReady: Boolean, targetInRange: Boolean): Boolean
            fun findVacuumHotbarSlot(client: Minecraft): Int
            fun findAotvHotbarSlot(client: Minecraft): Int
    }

    companion object {
        private const val AOTV_POST_CLICK_GRACE_MS = 250L
        private const val AOTV_CONFIRM_DISTANCE = 2.0
        private const val AOTV_CONFIRM_DISTANCE_SQ = AOTV_CONFIRM_DISTANCE * AOTV_CONFIRM_DISTANCE
        private const val AOTV_AIM_TOLERANCE_DEGREES = 2.0f
        // A pest that never settles inside the tight tolerance must not stall the hop
        // chain. Held just past the aim's own worst case (a half turn, ~280ms) so a
        // normal hop still gets the tight tolerance and a stubborn one gives up fast.
        private const val AOTV_AIM_SETTLE_TIMEOUT_MS = 400L
        private const val AOTV_AIM_FALLBACK_TOLERANCE_DEGREES = 6.0f
        // The hop aim is not a chase: it points at a spot and warps, and every extra
        // millisecond here lands on top of the configured AOTV delay. Kept fast
        // enough that settling inside AOTV_AIM_TOLERANCE_DEGREES stays in the same
        // ballpark as that delay even from a half-turn away.
        private const val AOTV_AIM_SMOOTHING_MS = 40.0f
        private const val VACUUM_REAPPROACH_BUFFER = 6.0
        private const val TARGET_REACQUIRE_CONE_DEGREES = 120.0
        private const val AIRBORNE_RECOVERY_TRIGGER_GAP = 2.5
        private const val AIRBORNE_RECOVERY_EXIT_GAP = 1.25
        private const val AIRBORNE_RECOVERY_HORIZONTAL_BUFFER = 2.0
        private const val AIRBORNE_RECOVERY_EXIT_HORIZONTAL_BUFFER = 4.0
        private const val AIRBORNE_RECOVERY_AIM_REFRESH_MS = 100L
        private const val AIRBORNE_RECOVERY_AIM_BLEND = 0.45
        private const val AIRBORNE_RECOVERY_AIM_SMOOTHING_MS = 240.0f
        private const val ETHERWARP_AIM_TOLERANCE_DEGREES = 2.0f
        private const val ETHERWARP_AIM_MIN_DURATION_MS = 120L
        private const val ETHERWARP_CONFIRM_TIMEOUT_MS = 900L
        private const val ETHERWARP_RETRY_COOLDOWN_MS = 1500L
        private const val ETHERWARP_FAILED_BLOCK_MEMORY_MS = 15_000L
        private const val ETHERWARP_BLOCK_SCAN_DEPTH = 20
        private const val ETHERWARP_MIN_TRAVEL_DISTANCE = 3.0
        private const val ETHERWARP_FRONT_OFFSET = 10.0
        private const val ETHERWARP_FRONT_MATCH_TOLERANCE = 0.75
        private const val ETHERWARP_POST_HOVER_MIN_CLEARANCE = 3.0
        private const val ETHERWARP_POST_HOVER_RELEASE_CLEARANCE = 3.35
        private const val ETHERWARP_POST_HOVER_GROUND_SCAN_DEPTH = 32
        private val PEST_APPROACH_OFFSETS = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1), intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1), intArrayOf(2, 0), intArrayOf(-2, 0), intArrayOf(0, 2), intArrayOf(0, -2))
        @JvmStatic fun handleFlyToPest(
            client: Minecraft,
            context: Context,
            targetReachDistance: Double,
            pathfinderStuckRetryTicks: Int,
            stateTimeoutMs: Long
        ) {
            val currentTarget = context.getCurrentTarget()
            if (currentTarget == null || currentTarget.isRemoved || (currentTarget is LivingEntity && currentTarget.isDeadOrDying)) {
                PathfindingManager.stop()
                context.setState(PestDestroyer.State.CHECK_NEXT)
                return
            }

            // Following a pest across the plot line makes the plot check teleport us back mid-route.
            if (!PestPlotNavigator.currentPlotFilter(client, context.runtime().navigation).test(currentTarget)) {
                ClientUtils.sendDebugMessage("[PestDestroyer] Target drifted off the plot. Picking another.")
                PathfindingManager.stop()
                context.runtime().currentTarget = null
                context.setState(PestDestroyer.State.CHECK_NEXT)
                return
            }

            val player = client.player ?: return
            val dist = player.distanceTo(currentTarget)
            // The fly executor owns the camera until the route reaches its handoff.
            // Tracking the moving pest here overwrote the path heading after only a
            // few movement ticks, making every stuck recovery forget its goal.

            val lassoTarget = PestHuntingController.shouldLassoTarget(client, currentTarget)
            val terminalRange = PestHuntingController.handoffRange(client, currentTarget, context.getVacuumRange())
            if (AetherConfig.PEST_DESTROYER_WALK_MODE.get() && dist <= terminalRange) {
                PathfindingManager.stop()
                context.beginTerminalState(client)
                return
            }
            if (tryWalkEtherwarpNearPest(client, context, currentTarget, System.currentTimeMillis())) {
                return
            }
            val directApproach = !AetherConfig.PEST_DESTROYER_WALK_MODE.get() &&
                !lassoTarget &&
                context.runtime().flightController.canApproachDirectly(client, currentTarget, context.getVacuumRange())
            if (directApproach || lassoTarget && dist <= targetReachDistance) {
                PathfindingManager.stop()
                context.setState(PestDestroyer.State.APPROACH_PEST)
                if (directApproach) {
                    context.runtime().flightController.update(client, currentTarget, context.getVacuumRange(), true)
                }
                return
            }

            when (context.runtime().flightRecovery.update(PathfindingManager.isNavigating(),
                    player.position(), System.currentTimeMillis(), context.getStateEnteredAt(), stateTimeoutMs)) {
                PestFlightRecovery.Action.GIVE_UP -> {
                    ClientUtils.sendDebugMessage("[PestDestroyer] Fly route exhausted recovery. Checking for next pest.")
                    PathfindingManager.stop()
                    context.deferTarget(currentTarget)
                    context.setState(PestDestroyer.State.CHECK_NEXT)
                }
                PestFlightRecovery.Action.RETRY -> {
                    ClientUtils.sendDebugMessage("[PestDestroyer] Fly route ended before reaching pest. Repathing.")
                    context.setStuckTicks(0)
                    context.startPathToPest(client, currentTarget)
                }
                PestFlightRecovery.Action.CONTINUE -> context.setStuckTicks(0)
                PestFlightRecovery.Action.WAIT -> Unit
            }
        }

        @JvmStatic fun handleApproachPest(
            client: Minecraft,
            context: Context,
            targetReachDistance: Double,
            approachTimeoutTicks: Int
        ) {
            val currentTarget = context.getCurrentTarget()
            if (currentTarget == null || currentTarget.isRemoved || (currentTarget is LivingEntity && currentTarget.isDeadOrDying)) {
                context.setState(PestDestroyer.State.CHECK_NEXT)
                return
            }

            val player = client.player ?: return
            val dist = player.distanceTo(currentTarget)
            context.setApproachTicks(context.getApproachTicks() + 1)

            val lassoTarget = PestHuntingController.shouldLassoTarget(client, currentTarget)
            val terminalRange = PestHuntingController.handoffRange(client, currentTarget, context.getVacuumRange())
            val approachHorizontalDistance = Math.hypot(
                currentTarget.x - player.x,
                currentTarget.z - player.z
            )
            val approachVerticalGap = getEntityEyePosition(currentTarget).y - player.eyePosition.y
            if (!lassoTarget &&
                player.abilities.flying &&
                approachHorizontalDistance <= terminalRange &&
                approachVerticalGap > AIRBORNE_RECOVERY_TRIGGER_GAP
            ) {
                PathfindingManager.stop(false)
                context.beginTerminalState(client)
                return
            }
            if (AetherConfig.PEST_DESTROYER_WALK_MODE.get() && dist <= terminalRange) {
                context.beginTerminalState(client)
                return
            }
            if (tryWalkEtherwarpNearPest(client, context, currentTarget, System.currentTimeMillis())) {
                return
            }
            val directApproach = !AetherConfig.PEST_DESTROYER_WALK_MODE.get() &&
                !lassoTarget &&
                context.runtime().flightController.canApproachDirectly(client, currentTarget, context.getVacuumRange())
            if (dist <= terminalRange && (lassoTarget || directApproach)) {
                context.beginTerminalState(client)
                if (!lassoTarget) {
                    context.runtime().flightController.update(client, currentTarget, context.getVacuumRange(), true)
                }
                return
            }

            if (directApproach) {
                if (PathfindingManager.isNavigating()) {
                    PathfindingManager.stop()
                }
                context.runtime().flightController.update(client, currentTarget, context.getVacuumRange(), true)
            } else if (!PathfindingManager.isNavigating()) {
                RotationManager.cancelRotation()
                context.startPathToPest(client, currentTarget)
            }

            if (context.getApproachTicks() > approachTimeoutTicks) {
                ClientUtils.sendDebugMessage("[PestDestroyer] Approach timed out.")
                PathfindingManager.stop()
                context.deferTarget(currentTarget)
                context.setState(PestDestroyer.State.CHECK_NEXT)
            }
        }

        @JvmStatic fun handleKillPest(
            client: Minecraft,
            context: Context,
            skullMissingConfirmTicks: Int,
            stateTimeoutMs: Long
        ) {
            val currentTarget = context.getCurrentTarget()
            if (currentTarget == null || currentTarget.isRemoved || (currentTarget is LivingEntity && currentTarget.isDeadOrDying)) {
                ClientUtils.setKeyMappingState(client.options.keyUse, false)
                if (currentTarget != null) {
                    if (context.recordTrackedPestKill(client, currentTarget)) {
                        return
                    }
                    if (context.switchToNextQueuedTarget(client)) {
                        return
                    }
                }
                context.setState(PestDestroyer.State.CHECK_NEXT)
                return
            }

            val player = client.player ?: return

            val dist = player.distanceTo(currentTarget)
            val horizontalDistance = Math.hypot(
                currentTarget.x - player.x,
                currentTarget.z - player.z
            ).toFloat()
            val verticalGap = getEntityEyePosition(currentTarget).y - player.eyePosition.y.toDouble()
            val runtime = context.runtime()
            var sameAirborneTarget = runtime.airborneRecoveryActive &&
                runtime.airborneRecoveryTargetEntityId == currentTarget.id
            val shouldEnterAirborneRecovery = player.abilities.flying &&
                verticalGap > AIRBORNE_RECOVERY_TRIGGER_GAP &&
                horizontalDistance <= context.getVacuumRange() + AIRBORNE_RECOVERY_HORIZONTAL_BUFFER

            if (!sameAirborneTarget && shouldEnterAirborneRecovery) {
                runtime.airborneRecoveryActive = true
                runtime.airborneRecoveryTargetEntityId = currentTarget.id
                runtime.airborneRecoveryAimPoint = getEntityEyePosition(currentTarget)
                runtime.airborneRecoveryAimUpdatedAt = System.currentTimeMillis()
                context.setTargetWithoutSkullTicks(0)
                PathfindingManager.stop(false)
                RotationManager.cancelRotation()
                sameAirborneTarget = true
                ClientUtils.sendDebugMessage(
                    "[PestDestroyer] Pest jumped vertically. Recovering on target " + currentTarget.id + ".")
            }

            if (sameAirborneTarget) {
                val keepRecovering: Boolean = player.abilities.flying &&
                    verticalGap > AIRBORNE_RECOVERY_EXIT_GAP &&
                    horizontalDistance.toDouble() <= context.getVacuumRange() + AIRBORNE_RECOVERY_EXIT_HORIZONTAL_BUFFER
                if (keepRecovering) {
                    handleAirbornePestRecovery(client, context, currentTarget, dist.toDouble(), verticalGap)
                    return
                }
                runtime.resetAirborneRecovery()
                context.setTargetWithoutSkullTicks(0)
                ClientUtils.setKeyMappingState(client.options.keyJump, false)
                ClientUtils.sendDebugMessage("[PestDestroyer] Airborne pest stabilized. Resuming normal combat.")
            }

            val directApproach = context.runtime().flightController.canApproachDirectly(client, currentTarget, context.getVacuumRange())
            if (PathfindingManager.isNavigating()) {
                PathfindingManager.stop()
            }
            context.runtime().flightController.update(client, currentTarget, context.getVacuumRange(), directApproach)
            if (context.getVacuumSlot() == -1) {
                context.setVacuumSlot(context.findVacuumHotbarSlot(client))
            }
            if (context.getVacuumSlot() != -1 &&
                (player.inventory as AccessorInventory).getSelected() != context.getVacuumSlot()) {
                client.execute { FailsafeManager.selectHotbarSlot(client, context.getVacuumSlot()) }
                return
            }

            if (dist <= context.getVacuumRange() && directApproach) {
                val retryingUse = context.shouldTemporarilyReleaseKillVacuum(client, true, true)
                ClientUtils.setKeyMappingState(client.options.keyUse, !retryingUse &&
                    !isOutsideForwardCone(client, currentTarget, TARGET_REACQUIRE_CONE_DEGREES))

                if (!context.hasPestSkullMarkerForTarget(client, currentTarget)) {
                    context.setTargetWithoutSkullTicks(context.getTargetWithoutSkullTicks() + 1)
                    if (context.getTargetWithoutSkullTicks() >= skullMissingConfirmTicks) {
                        ClientUtils.setKeyMappingState(client.options.keyUse, false)
                        ClientUtils.setKeyMappingState(client.options.keyDown, false)
                        context.markKilled(currentTarget)
                        if (context.recordTrackedPestKill(client, currentTarget)) {
                            return
                        }
                        ClientUtils.sendDebugMessage("[PestDestroyer] Pest skull disappeared. Switching target immediately.")
                        if (!context.switchToNextQueuedTarget(client)) {
                            context.setState(PestDestroyer.State.CHECK_NEXT)
                        }
                        return
                    }
                } else {
                    context.setTargetWithoutSkullTicks(0)
                }
            } else {
                context.shouldTemporarilyReleaseKillVacuum(client, true, false)
                ClientUtils.setKeyMappingState(client.options.keyUse, false)
                context.setTargetWithoutSkullTicks(0)
                if (!directApproach || dist > context.getVacuumRange() + VACUUM_REAPPROACH_BUFFER) {
                    RotationManager.cancelRotation()
                    context.setState(PestDestroyer.State.APPROACH_PEST)
                    return
                }
            }

            if (System.currentTimeMillis() - context.getStateEnteredAt() > stateTimeoutMs) {
                ClientUtils.setKeyMappingState(client.options.keyUse, false)
                ClientUtils.setKeyMappingState(client.options.keyDown, false)
                ClientUtils.setKeyMappingState(client.options.keyUp, false)
                ClientUtils.sendDebugMessage("[PestDestroyer] Kill pest timed out. Moving on.")
                context.deferTarget(currentTarget)
                context.setTargetWithoutSkullTicks(0)
                if (!context.switchToNextQueuedTarget(client)) {
                    context.setState(PestDestroyer.State.CHECK_NEXT)
                }
            }
        }

        @JvmStatic fun handleAotvBetweenPests(
            client: Minecraft,
            context: Context,
            aotvRange: Double,
            aotvGapMultiplier: Double,
            stateTimeoutMs: Long
        ) {
            if (AetherConfig.PEST_DESTROYER_WALK_MODE.get()) {
                val target = context.getCurrentTarget()
                clearAotvBetweenPests(client, context)
                if (target == null || target.isRemoved) {
                    context.setState(PestDestroyer.State.CHECK_NEXT)
                } else {
                    context.startPathToPest(client, target)
                    context.setState(PestDestroyer.State.FLY_TO_PEST)
                }
                return
            }
            val currentTarget = context.getCurrentTarget()
            if (currentTarget == null || currentTarget.isRemoved || (currentTarget is LivingEntity && currentTarget.isDeadOrDying)) {
                clearAotvBetweenPests(client, context)
                context.setState(PestDestroyer.State.CHECK_NEXT)
                return
            }

            if (context.getAotvSlot() == -1) {
                context.setAotvSlot(context.findAotvHotbarSlot(client))
                if (context.getAotvSlot() == -1) {
                    clearAotvBetweenPests(client, context)
                    ClientUtils.sendDebugMessage("[PestDestroyer] No AOTV found. Falling back to pathfinding.")
                    context.startPathToPest(client, currentTarget)
                    context.setState(PestDestroyer.State.FLY_TO_PEST)
                    return
                }
                context.setStateEnteredAt(System.currentTimeMillis())
            }

            val player = client.player ?: return
            val now = System.currentTimeMillis()
            // Measured from the last hop, not from state entry: a long chain of hops is
            // progress. Checked up front because waiting on an aim or a climb returns
            // early, so a timeout further down never runs.
            val lastProgressAt = Math.max(
                context.getStateEnteredAt(),
                Math.max(context.getAotvLastUseAt(), context.getAotvPendingUseAt())
            )
            if (now - lastProgressAt > stateTimeoutMs) {
                clearAotvBetweenPests(client, context)
                ClientUtils.sendDebugMessage("[PestDestroyer] AOTV state timed out. Falling back to pathfinding.")
                context.startPathToPest(client, currentTarget)
                context.setState(PestDestroyer.State.FLY_TO_PEST)
                return
            }

            val stopDistance = PestTargetController.getAotvStopDistance(
                client, currentTarget, context.getVacuumRange())
            var dist = player.distanceTo(currentTarget).toDouble()
            if (finishAotvIfClose(client, context, currentTarget, dist, stopDistance)) {
                return
            }

            if ((AetherConfig.PEST_ETHERWARP_TO_PEST.get() || context.runtime().pestEtherwarpActive) &&
                handleEtherwarpNearPest(client, context, currentTarget, stopDistance, now)) {
                return
            }

            val aimPos = getEntityEyePosition(currentTarget)

            val hopDirection = aimPos.subtract(player.eyePosition).normalize()
            if (!hasClearAotvHop(client, hopDirection, aotvRange)) {
                pathAroundAotvObstacle(client, context, currentTarget)
                return
            }
            ClientUtils.setKeyMappingState(client.options.keyJump, false)

            if (context.getAotvSlot() != -1 &&
                (player.inventory as AccessorInventory).getSelected() != context.getAotvSlot()) {
                client.execute { FailsafeManager.selectHotbarSlot(client, context.getAotvSlot()) }
                return
            }

            val suppressRotation = FailsafeManager.shouldSuppressPestCleanerRotation(client)
            if (!suppressRotation) {
                ClientUtils.setKeyMappingState(client.options.keyUp, false)
                ClientUtils.setKeyMappingState(client.options.keySprint, false)

                if (context.getAotvAimStartedAt() == 0L) {
                    context.setAotvAimStartedAt(now)
                }
                // A one-shot rotation lands where the pest was and has to restart, which is
                // what froze the hop chain staring at the pest. Retarget every tick instead.
                RotationManager.trackRotation(
                    client, aimPos, AOTV_AIM_SMOOTHING_MS,
                    AetherConfig.PEST_NEXT_TARGET_TURN_SPEED.get()
                )

                val tolerance = if (now - context.getAotvAimStartedAt() > AOTV_AIM_SETTLE_TIMEOUT_MS) {
                    AOTV_AIM_FALLBACK_TOLERANCE_DEGREES
                } else {
                    AOTV_AIM_TOLERANCE_DEGREES
                }
                if (!context.isLookingAt(client, aimPos, tolerance)) {
                    return
                }
                ClientUtils.sendDebugMessage(
                    "[PestDestroyer] AOTV aim settled in " +
                            (now - context.getAotvAimStartedAt()) + "ms (tolerance " +
                            String.format("%.0f", tolerance) + "\u00B0)."
                    )
                context.setAotvAimStartedAt(0L)
            }

            if (AetherConfig.PEST_AOTV_CONFIRM_BETWEEN.get() && context.getAotvPendingUseAt() != 0L) {
                val movedDistance = getAotvMovedDistance(client, context)
                if (movedDistance >= AOTV_CONFIRM_DISTANCE) {
                    context.setAotvLastUseAt(context.getAotvPendingUseAt())
                    context.setAotvPendingUseAt(0L)
                    context.setAotvPostClickGraceUntil(0L)
                    context.setAotvUseCount(context.getAotvUseCount() + 1)
                    ClientUtils.sendDebugMessage(
                        "[PestDestroyer] AOTV confirmed by movement: " +
                            String.format("%.2f", movedDistance) + " blocks."
                    )
                    dist = player.distanceTo(currentTarget).toDouble()
                    if (finishAotvIfClose(client, context, currentTarget, dist, stopDistance)) {
                        return
                    }
                } else if (context.getAotvPostClickGraceUntil() > now) {
                    ClientUtils.sendDebugMessage(
                        "[PestDestroyer] Waiting for AOTV confirm: moved " +
                            String.format("%.2f", movedDistance) +
                            "/" + String.format("%.2f", AOTV_CONFIRM_DISTANCE) + " blocks."
                    )
                    return
                } else {
                    ClientUtils.sendDebugMessage(
                        "[PestDestroyer] AOTV confirm failed: moved " +
                            String.format("%.2f", movedDistance) +
                            "/" + String.format("%.2f", AOTV_CONFIRM_DISTANCE) + " blocks. Retrying."
                    )
                    context.setAotvPendingUseAt(0L)
                    context.setAotvPostClickGraceUntil(0L)
                }
            } else if (context.getAotvPostClickGraceUntil() > now) {
                val movedDistanceSq = getAotvMovedDistanceSq(client, context)
                if (movedDistanceSq <= AOTV_CONFIRM_DISTANCE_SQ) {
                    return
                }
                context.setAotvPostClickGraceUntil(0L)
                dist = player.distanceTo(currentTarget).toDouble()
                if (finishAotvIfClose(client, context, currentTarget, dist, stopDistance)) {
                    return
                }
            }

            var readyAt = context.getAotvNextUseAt()
            if (readyAt == 0L) {
                val anchor = if (context.getAotvLastUseAt() == 0L) {
                    context.getStateEnteredAt()
                } else {
                    context.getAotvLastUseAt()
                }
                readyAt = anchor + dev.aether.config.ConfigHelpers.getRandomizedDelay(
                    AetherConfig.PEST_AOTV_DELAY_MIN.get(),
                    AetherConfig.PEST_AOTV_DELAY_MAX.get()
                ).toLong()
                context.setAotvNextUseAt(readyAt)
            }
            if (now >= readyAt) {
                if (!hasClearAotvHop(client, player.getViewVector(1.0f), aotvRange)) {
                    pathAroundAotvObstacle(client, context, currentTarget)
                    return
                }
                ClientUtils.sendDebugMessage(
                    "[PestDestroyer] Using AOTV (" + (context.getAotvUseCount() + 1) + "). Distance: " +
                        String.format("%.1f", dist)
                )
                ClientUtils.performUseClick()
                FailsafeManager.addRotationGracePeriod(AOTV_POST_CLICK_GRACE_MS)
                context.setAotvPostClickGraceUntil(now + AOTV_POST_CLICK_GRACE_MS)
                context.setAotvLastUsePlayerX(player.x)
                context.setAotvLastUsePlayerY(player.y)
                context.setAotvLastUsePlayerZ(player.z)
                if (AetherConfig.PEST_AOTV_CONFIRM_BETWEEN.get()) {
                    context.setAotvPendingUseAt(now)
                    ClientUtils.sendDebugMessage(
                        "[PestDestroyer] Waiting for AOTV position confirm (>= " +
                            String.format("%.0f", AOTV_CONFIRM_DISTANCE) + " blocks)."
                    )
                } else {
                    context.setAotvLastUseAt(now)
                    context.setAotvUseCount(context.getAotvUseCount() + 1)
                }
                context.setAotvNextUseAt(0L)
            }

            if (context.getAotvUseCount() > 10) {
                clearAotvBetweenPests(client, context)
                ClientUtils.sendDebugMessage("[PestDestroyer] AOTV usage exceeded maximum. Falling back to pathfinding.")
                context.startPathToPest(client, currentTarget)
                context.setState(PestDestroyer.State.FLY_TO_PEST)
            }
        }

        @JvmStatic fun isOutsideForwardCone(client: Minecraft?, target: Entity?, coneDegrees: Double): Boolean { val player = client?.player; if (player == null || target == null || coneDegrees <= 0.0) return false; val toTarget = getEntityEyePosition(target).subtract(player.eyePosition); if (toTarget.lengthSqr() == 0.0) return false; val dot = player.getViewVector(1.0f).normalize().dot(toTarget.normalize()); val threshold = Math.cos(Math.toRadians(coneDegrees)); return dot < threshold }
        private fun getEntityEyePosition(entity: Entity): Vec3 = entity.position().add(0.0, entity.getEyeHeight(entity.pose).toDouble(), 0.0)
        private fun handleAirbornePestRecovery(client: Minecraft, context: Context, currentTarget: Entity, dist: Double, verticalGap: Double) {
            val runtime = context.runtime()
            if (PathfindingManager.isNavigating()) PathfindingManager.stop(false)
            ClientUtils.setKeyMappingState(client.options.keyUp, false); ClientUtils.setKeyMappingState(client.options.keyDown, false); ClientUtils.setKeyMappingState(client.options.keySprint, false); ClientUtils.setKeyMappingState(client.options.keyJump, verticalGap > AIRBORNE_RECOVERY_EXIT_GAP); context.setTargetWithoutSkullTicks(0)
            val player = client.player ?: return
            if (context.getVacuumSlot() == -1) context.setVacuumSlot(context.findVacuumHotbarSlot(client))
            if (context.getVacuumSlot() != -1 && (player.inventory as AccessorInventory).getSelected() != context.getVacuumSlot()) client.execute { FailsafeManager.selectHotbarSlot(client, context.getVacuumSlot()) }
            val now = System.currentTimeMillis(); val rawAim = buildCombatAimTarget(client, currentTarget)
            if (runtime.airborneRecoveryAimPoint == null) { runtime.airborneRecoveryAimPoint = rawAim; runtime.airborneRecoveryAimUpdatedAt = now } else if (now - runtime.airborneRecoveryAimUpdatedAt >= AIRBORNE_RECOVERY_AIM_REFRESH_MS) { runtime.airborneRecoveryAimPoint = runtime.airborneRecoveryAimPoint?.lerp(rawAim, AIRBORNE_RECOVERY_AIM_BLEND); runtime.airborneRecoveryAimUpdatedAt = now }
            val aimPoint = runtime.airborneRecoveryAimPoint
            if (!FailsafeManager.shouldSuppressPestCleanerRotation(client) && aimPoint != null) RotationManager.trackRotation(client, aimPoint, AIRBORNE_RECOVERY_AIM_SMOOTHING_MS, AetherConfig.PEST_MAX_TURN_SPEED.get())
            val inVacuumRange = dist <= context.getVacuumRange(); val retryingUse = context.shouldTemporarilyReleaseKillVacuum(client, true, inVacuumRange); ClientUtils.setKeyMappingState(client.options.keyUse, inVacuumRange && !retryingUse)
        }
        private fun clearAotvBetweenPests(client: Minecraft, context: Context) { val player = client.player ?: return; ClientUtils.setKeyMappingState(client.options.keyUse, false); ClientUtils.setKeyMappingState(client.options.keyUp, false); ClientUtils.setKeyMappingState(client.options.keySprint, false); ClientUtils.setKeyMappingState(client.options.keyJump, false); ClientUtils.setKeyMappingState(client.options.keyShift, false); player.setShiftKeyDown(false); RotationManager.cancelRotation(); context.setAotvSlot(-1); context.setAotvUseCount(0); context.setAotvLastUseAt(0L); context.setAotvNextUseAt(0L); context.setAotvPostClickGraceUntil(0L); context.setAotvPendingUseAt(0L); context.setAotvAimStartedAt(0L); clearPestEtherwarpAttempt(client, context.runtime(), true) }
        private class PestEtherwarpCandidate(private val aimPoint: Vec3, private val landingFeet: Vec3, private val landingBlock: BlockPos) { fun aimPoint(): Vec3 = aimPoint; fun landingFeet(): Vec3 = landingFeet; fun landingBlock(): BlockPos = landingBlock }
        private fun handleEtherwarpNearPest(client: Minecraft, context: Context, currentTarget: Entity, stopDistance: Double, now: Long): Boolean {
            val runtime = context.runtime()
            val player = client.player ?: return false
            val level = client.level ?: return false
            if (!AetherConfig.PEST_ETHERWARP_TO_PEST.get() && !runtime.pestEtherwarpActive) return false
            if (runtime.pestEtherwarpRetryAfter > now && !runtime.pestEtherwarpActive) return false
            if (runtime.pestEtherwarpActive && runtime.pestEtherwarpTargetEntityId != currentTarget.id) clearPestEtherwarpAttempt(client, runtime, false)
            if (runtime.pestEtherwarpActive && AetherConfig.PEST_DESTROYER_WALK_MODE.get() && PathfindingManager.isNavigating()) PathfindingManager.stop(false)
            if (!runtime.pestEtherwarpActive) {
                val pestDistance = player.distanceTo(currentTarget); if (pestDistance < AetherConfig.PEST_ETHERWARP_MIN_DISTANCE.get()) return false
                val etherwarpSlot = GearManager.findEtherwarpAspectOfTheVoidHotbarSlot(client); val nextRoutePest = findNextPlannedPest(client, runtime, currentTarget); val candidate = findEtherwarpCandidateNearPest(client, runtime, currentTarget, nextRoutePest, now)
                if (etherwarpSlot < 0 || candidate == null) return false
                if (AetherConfig.PEST_DESTROYER_WALK_MODE.get() && PathfindingManager.isNavigating()) PathfindingManager.stop(false)
                runtime.pestEtherwarpActive = true; runtime.pestEtherwarpTargetEntityId = currentTarget.id; runtime.pestEtherwarpAimPoint = candidate.aimPoint(); runtime.pestEtherwarpLandingBlock = candidate.landingBlock(); runtime.pestEtherwarpClickAt = 0L; context.setAotvSlot(etherwarpSlot); context.setAotvAimStartedAt(0L)
            }
            val etherwarpSlot = GearManager.findEtherwarpAspectOfTheVoidHotbarSlot(client)
            if (etherwarpSlot < 0 || runtime.pestEtherwarpAimPoint == null) { clearPestEtherwarpAttempt(client, runtime, true); return false }
            context.setAotvSlot(etherwarpSlot); ClientUtils.setKeyMappingState(client.options.keyUp, false); ClientUtils.setKeyMappingState(client.options.keySprint, false); val walkMode = AetherConfig.PEST_DESTROYER_WALK_MODE.get(); ClientUtils.setKeyMappingState(client.options.keyJump, !walkMode); runtime.pestEtherwarpJumpHeld = !walkMode; ClientUtils.setKeyMappingState(client.options.keyShift, true); player.setShiftKeyDown(true)
            if (runtime.pestEtherwarpClickAt != 0L) { val movedDistance = getAotvMovedDistance(client, context); if (movedDistance >= AOTV_CONFIRM_DISTANCE) { context.setAotvLastUseAt(runtime.pestEtherwarpClickAt); context.setAotvUseCount(context.getAotvUseCount() + 1); runtime.pestEtherwarpMaintainHeight = true; runtime.pestEtherwarpJumpHeld = true; clearPestEtherwarpAttempt(client, runtime, false); val distance = player.distanceTo(currentTarget).toDouble(); finishAotvIfClose(client, context, currentTarget, distance, stopDistance); return true }; if (now - runtime.pestEtherwarpClickAt <= ETHERWARP_CONFIRM_TIMEOUT_MS) return true; val landingBlock = runtime.pestEtherwarpLandingBlock; if (landingBlock != null) runtime.pestEtherwarpFailedBlocksUntil[landingBlock.asLong()] = now + ETHERWARP_FAILED_BLOCK_MEMORY_MS; runtime.pestEtherwarpRetryAfter = now + ETHERWARP_RETRY_COOLDOWN_MS; clearPestEtherwarpAttempt(client, runtime, false); return false }
            if (FailsafeManager.shouldSuppressPestCleanerRotation(client)) { clearPestEtherwarpAttempt(client, runtime, true); return false }
            val etherwarpSelected = (player.inventory as AccessorInventory).getSelected() == etherwarpSlot; if (!etherwarpSelected) client.execute { FailsafeManager.selectHotbarSlot(client, etherwarpSlot) }
            val aimPoint = runtime.pestEtherwarpAimPoint ?: run { clearPestEtherwarpAttempt(client, runtime, true); return false }
            if (context.getAotvAimStartedAt() == 0L) { RotationManager.cancelRotation(); RotationManager.initiateRotation(client, aimPoint, ETHERWARP_AIM_MIN_DURATION_MS, 0.0f, AetherConfig.PEST_NEXT_TARGET_TURN_SPEED.get()); context.setAotvAimStartedAt(now); return true }
            if (RotationManager.isRotating()) return true
            if (!context.isLookingAt(client, aimPoint, ETHERWARP_AIM_TOLERANCE_DEGREES)) { RotationManager.cancelRotation(); RotationManager.initiateRotation(client, aimPoint, ETHERWARP_AIM_MIN_DURATION_MS, 0.0f, AetherConfig.PEST_NEXT_TARGET_TURN_SPEED.get()); context.setAotvAimStartedAt(now); return true }
            if (!etherwarpSelected) return true
            var readyAt = context.getAotvNextUseAt(); if (readyAt == 0L) { val anchor = if (context.getAotvLastUseAt() == 0L) context.getStateEnteredAt() else context.getAotvLastUseAt(); readyAt = anchor + dev.aether.config.ConfigHelpers.getRandomizedDelay(AetherConfig.PEST_AOTV_DELAY_MIN.get(), AetherConfig.PEST_AOTV_DELAY_MAX.get()).toLong(); context.setAotvNextUseAt(readyAt) }; if (now < readyAt) return true
            context.setAotvLastUsePlayerX(player.x); context.setAotvLastUsePlayerY(player.y); context.setAotvLastUsePlayerZ(player.z); ClientUtils.performUseClick(); FailsafeManager.addRotationGracePeriod(AOTV_POST_CLICK_GRACE_MS); runtime.pestEtherwarpClickAt = now; context.setAotvPostClickGraceUntil(now + AOTV_POST_CLICK_GRACE_MS); context.setAotvAimStartedAt(0L); context.setAotvNextUseAt(0L); return true
        }
        private fun tryWalkEtherwarpNearPest(client: Minecraft, context: Context, currentTarget: Entity, now: Long): Boolean { if (!AetherConfig.PEST_DESTROYER_WALK_MODE.get() || !AetherConfig.PEST_AOTV_BETWEEN.get() || !AetherConfig.PEST_ETHERWARP_TO_PEST.get()) return false; val stopDistance = PestTargetController.getAotvStopDistance(client, currentTarget, context.getVacuumRange()); return handleEtherwarpNearPest(client, context, currentTarget, stopDistance, now) }
        private fun findNextPlannedPest(client: Minecraft, runtime: PestDestroyerRuntime, currentTarget: Entity?): Entity? { val route = PestTargetController.buildPlannedRoute(client, runtime); var foundCurrent = false; for (candidate in route) { if (candidate == null || candidate.isRemoved) continue; if (currentTarget != null && candidate.id == currentTarget.id) { foundCurrent = true; continue }; if (foundCurrent || currentTarget == null) return candidate }; for (candidate in route) if (candidate != null && !candidate.isRemoved && (currentTarget == null || candidate.id != currentTarget.id)) return candidate; return null }
        private fun findEtherwarpCandidateNearPest(client: Minecraft, runtime: PestDestroyerRuntime, pest: Entity, nextPest: Entity?, now: Long): PestEtherwarpCandidate? {
            val player = client.player ?: return null
            val level = client.level ?: return null
            val checker = WalkabilityChecker(level); val sneakingEye = EtherwarpHelper.getEyePosition(client, player.position()) ?: return null; val pestBlockX = Math.floor(pest.x).toInt(); val pestBlockZ = Math.floor(pest.z).toInt(); val startY = Math.floor(pest.y).toInt() - 1
            runtime.pestEtherwarpFailedBlocksUntil.entries.removeAll { it.value <= now }
            var candidates = ArrayList<PestEtherwarpCandidate>()
            for (offset in PEST_APPROACH_OFFSETS) { val x = pestBlockX + offset[0]; val z = pestBlockZ + offset[1]; for (blockY in startY downTo startY - ETHERWARP_BLOCK_SCAN_DEPTH) { val landingFeet = EtherwarpHelper.resolveTargetFeet(checker, x, blockY, z) ?: continue; val centeredFeet = EtherwarpHelper.getCenteredFeet(landingFeet); if (player.position().distanceTo(centeredFeet) < ETHERWARP_MIN_TRAVEL_DISTANCE) break; val landingBlock = EtherwarpHelper.getTargetBlock(landingFeet); val failedUntil = runtime.pestEtherwarpFailedBlocksUntil[landingBlock.asLong()]; if (failedUntil != null && failedUntil > now) continue; val aimPoint = EtherwarpHelper.findVisibleTargetPoint(client, checker, sneakingEye, landingFeet); if (aimPoint != null) { candidates.add(PestEtherwarpCandidate(aimPoint, centeredFeet, landingBlock)); break } } }
            if (candidates.isEmpty()) return null
            val pestPosition = pest.position(); val preferredLanding = getPreferredEtherwarpLanding(client, pest)
            if (preferredLanding != null) { val bestFrontDistance = candidates.minOf { horizontalDistance(it.landingFeet(), preferredLanding) }; candidates = candidates.filterTo(ArrayList()) { horizontalDistance(it.landingFeet(), preferredLanding) <= bestFrontDistance + ETHERWARP_FRONT_MATCH_TOLERANCE } }
            val random = ThreadLocalRandom.current(); if (nextPest == null || nextPest.isRemoved) return candidates[random.nextInt(candidates.size)]
            val nextPosition = nextPest.position(); candidates.sortWith(Comparator.comparingDouble { candidate -> candidate.landingFeet().distanceTo(nextPosition) + candidate.landingFeet().distanceTo(pestPosition) * 0.20 }); val first = candidates[0]; val bestScore = first.landingFeet().distanceTo(nextPosition) + first.landingFeet().distanceTo(pestPosition) * 0.20; val nearBest = ArrayList<PestEtherwarpCandidate>()
            for (candidate in candidates) { val score = candidate.landingFeet().distanceTo(nextPosition) + candidate.landingFeet().distanceTo(pestPosition) * 0.20; if (score <= bestScore + 1.25) nearBest.add(candidate) }
            return nearBest[random.nextInt(nearBest.size)]
        }
        @JvmStatic fun findWalkTargetNearPest(client: Minecraft?, pest: Entity?): Vec3? {
            if (client == null || client.level == null || pest == null) return null
            val player = client.player ?: return null
            val level = client.level ?: return null
            val checker = WalkabilityChecker(level); val pestBlockX = Math.floor(pest.x).toInt(); val pestBlockZ = Math.floor(pest.z).toInt(); val startY = Math.floor(pest.y).toInt() - 1; var candidates = ArrayList<Vec3>()
            for (offset in PEST_APPROACH_OFFSETS) { val x = pestBlockX + offset[0]; val z = pestBlockZ + offset[1]; for (blockY in startY downTo startY - ETHERWARP_BLOCK_SCAN_DEPTH) { val landingFeet = EtherwarpHelper.resolveTargetFeet(checker, x, blockY, z); if (landingFeet != null) { val centeredFeet = EtherwarpHelper.getCenteredFeet(landingFeet); if (hasLineOfSightToPest(client, centeredFeet, pest)) { candidates.add(centeredFeet); break } } } }
            if (candidates.isEmpty()) return null
            val preferredLanding = getPreferredEtherwarpLanding(client, pest); if (preferredLanding != null) { val bestFrontDistance = candidates.minOf { horizontalDistance(it, preferredLanding) }; candidates = candidates.filterTo(ArrayList()) { horizontalDistance(it, preferredLanding) <= bestFrontDistance + ETHERWARP_FRONT_MATCH_TOLERANCE } }
            return candidates.minByOrNull { player.position().distanceTo(it) }
        }
        private fun hasLineOfSightToPest(client: Minecraft, feet: Vec3, pest: Entity): Boolean { val player = client.player ?: return false; val eye = feet.add(0.0, player.getEyeHeight().toDouble(), 0.0); val pestEye = getEntityEyePosition(pest); val level = client.level ?: return false; return level.clip(ClipContext(eye, pestEye, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player)).type == HitResult.Type.MISS }
        private fun getPreferredEtherwarpLanding(client: Minecraft, pest: Entity): Vec3? { val player = client.player ?: return null; val towardPlayerX = player.x - pest.x; val towardPlayerZ = player.z - pest.z; val horizontalDistance = Math.hypot(towardPlayerX, towardPlayerZ); if (horizontalDistance < 1.0e-6) return null; return Vec3(pest.x + towardPlayerX / horizontalDistance * ETHERWARP_FRONT_OFFSET, pest.y, pest.z + towardPlayerZ / horizontalDistance * ETHERWARP_FRONT_OFFSET) }
        private fun horizontalDistance(first: Vec3, second: Vec3): Double = Math.hypot(first.x - second.x, first.z - second.z)
        private fun clearPestEtherwarpAttempt(client: Minecraft?, runtime: PestDestroyerRuntime, clearRetryCooldown: Boolean) {
            if (client != null && client.options != null) { ClientUtils.setKeyMappingState(client.options.keyShift, false); if (!runtime.pestEtherwarpMaintainHeight) { ClientUtils.setKeyMappingState(client.options.keyJump, false); runtime.pestEtherwarpJumpHeld = false } }
            client?.player?.setShiftKeyDown(false)
            RotationManager.cancelRotation(); runtime.pestEtherwarpActive = false; runtime.pestEtherwarpTargetEntityId = -1; runtime.pestEtherwarpAimPoint = null; runtime.pestEtherwarpLandingBlock = null; runtime.pestEtherwarpClickAt = 0L; runtime.aotvAimStartedAt = 0L; runtime.aotvPostClickGraceUntil = 0L; if (clearRetryCooldown) runtime.pestEtherwarpRetryAfter = 0L
        }
        @JvmStatic fun updateEtherwarpAltitudeHold(client: Minecraft?, runtime: PestDestroyerRuntime?) {
            if (client == null || client.level == null || client.options == null || runtime == null) return
            val player = client.player ?: return
            if (AetherConfig.PEST_DESTROYER_WALK_MODE.get() && !runtime.pestEtherwarpActive) { val releaseFlightKeys = runtime.pestEtherwarpActive || runtime.pestEtherwarpMaintainHeight || runtime.pestEtherwarpJumpHeld; if (releaseFlightKeys) clearPestEtherwarpAttempt(client, runtime, true); runtime.pestEtherwarpMaintainHeight = false; runtime.pestEtherwarpJumpHeld = false; if (releaseFlightKeys) { ClientUtils.setKeyMappingState(client.options.keyJump, false); ClientUtils.setKeyMappingState(client.options.keyShift, false) }; return }
            if (runtime.pestEtherwarpActive) { val walkMode = AetherConfig.PEST_DESTROYER_WALK_MODE.get(); ClientUtils.setKeyMappingState(client.options.keyJump, !walkMode); runtime.pestEtherwarpJumpHeld = !walkMode; return }
            val canMaintain = runtime.active && runtime.pestEtherwarpMaintainHeight && AetherConfig.PEST_ETHERWARP_TO_PEST.get() && player.abilities.flying && runtime.state != PestDestroyer.State.TELEPORT_TO_PLOT && runtime.state != PestDestroyer.State.AOTV_TO_ROOF && runtime.state != PestDestroyer.State.AOTV_TO_ROOF_RETURN && runtime.state != PestDestroyer.State.AOTV_POST_LOOKDOWN && runtime.state != PestDestroyer.State.HUNT_PEST && runtime.state != PestDestroyer.State.CHECK_NEXT && runtime.state != PestDestroyer.State.BALLSACK_SHREDDER && runtime.state != PestDestroyer.State.FINISH && runtime.state != PestDestroyer.State.IDLE
            if (!canMaintain) { if (runtime.pestEtherwarpJumpHeld) { ClientUtils.setKeyMappingState(client.options.keyJump, false); runtime.pestEtherwarpJumpHeld = false }; return }
            val clearance = getGroundClearanceBelowPlayer(client); if (!java.lang.Double.isFinite(clearance)) { if (runtime.pestEtherwarpJumpHeld) { ClientUtils.setKeyMappingState(client.options.keyJump, false); runtime.pestEtherwarpJumpHeld = false }; return }
            val shouldJump = if (runtime.pestEtherwarpJumpHeld) clearance < ETHERWARP_POST_HOVER_RELEASE_CLEARANCE else clearance < ETHERWARP_POST_HOVER_MIN_CLEARANCE; ClientUtils.setKeyMappingState(client.options.keyJump, shouldJump); runtime.pestEtherwarpJumpHeld = shouldJump
        }
        private fun getGroundClearanceBelowPlayer(client: Minecraft): Double {
            val player = client.player ?: return Double.NaN
            val level = client.level ?: return Double.NaN
            val feetY = player.y; val blockX = Math.floor(player.x).toInt(); val blockZ = Math.floor(player.z).toInt(); val startY = Math.floor(feetY - 1.0e-4).toInt(); val endY = startY - ETHERWARP_POST_HOVER_GROUND_SCAN_DEPTH; val pos = BlockPos.MutableBlockPos()
            for (y in startY downTo endY) { pos.set(blockX, y, blockZ); val shape = level.getBlockState(pos).getCollisionShape(level, pos); if (shape.isEmpty) continue; val surfaceY = y + shape.bounds().maxY; if (surfaceY <= feetY + 1.0e-4) return Math.max(0.0, feetY - surfaceY) }
            return Double.NaN
        }
        private fun getAotvMovedDistance(client: Minecraft, context: Context): Double = Math.sqrt(getAotvMovedDistanceSq(client, context))
        private fun getAotvMovedDistanceSq(client: Minecraft, context: Context): Double { val player = client.player ?: return 0.0; val dx = player.x - context.getAotvLastUsePlayerX(); val dy = player.y - context.getAotvLastUsePlayerY(); val dz = player.z - context.getAotvLastUsePlayerZ(); return dx * dx + dy * dy + dz * dz }
        @JvmStatic fun buildCombatAimTarget(client: Minecraft, target: Entity): Vec3 { if (PestDestroyer.isCatchInProgress()) return target.position().add(0.0, target.getEyeHeight(target.pose).toDouble(), 0.0); if (PestHuntingController.shouldLassoTarget(client, target)) return target.position().add(0.0, target.getEyeHeight(target.pose).toDouble(), 0.0); return PestAimTracker.trackingAim(client, target) }
        // high aim point that lets the vacuum beam connect from above
        @JvmStatic fun buildVacuumAimTarget(client: Minecraft, target: Entity): Vec3 = buildVacuumAimTarget(client, target, getEntityEyePosition(target))
        @JvmStatic fun buildVacuumAimTarget(client: Minecraft, target: Entity, targetEye: Vec3): Vec3 { val player = client.player ?: return targetEye; val eyePos = player.eyePosition; if (eyePos.y > targetEye.y) { val horizontalDistance = Math.sqrt((targetEye.x - eyePos.x) * (targetEye.x - eyePos.x) + (targetEye.z - eyePos.z) * (targetEye.z - eyePos.z)); val desiredPitch = getAbovePestPitch(target); val targetY = eyePos.y + Math.tan(Math.toRadians(-desiredPitch.toDouble())) * horizontalDistance; return Vec3(targetEye.x, targetY, targetEye.z) }; return targetEye }
        private fun getAbovePestPitch(target: Entity): Float = PestPitchRange.configured().bucketFor(target.id)
        private fun hasClearAotvHop(client: Minecraft, direction: Vec3, range: Double): Boolean {
            val player = client.player ?: return false
            val start = player.position()
            return FlightPathClearance.isClear(client, start, start.add(direction.normalize().scale(range)))
        }
        private fun pathAroundAotvObstacle(client: Minecraft, context: Context, target: Entity) { clearAotvBetweenPests(client, context); ClientUtils.sendDebugMessage("[PestDestroyer] AOTV hop obstructed. Pathfinding around the obstacle."); context.startPathToPest(client, target); context.setState(PestDestroyer.State.FLY_TO_PEST) }
        private fun finishAotvIfClose(client: Minecraft, context: Context, currentTarget: Entity, dist: Double, stopDistance: Double): Boolean {
            if (dist > stopDistance) return false
            val arrivedViaAotv = context.getAotvUseCount() > 0; clearAotvBetweenPests(client, context); context.setArrivedAtCurrentTargetViaAotv(arrivedViaAotv); ClientUtils.sendDebugMessage("[PestDestroyer] AOTV closed gap. Distance now " + String.format("%.1f", dist) + ". Switching to pathfinding.")
            if (dist <= PestHuntingController.handoffRange(client, currentTarget, context.getVacuumRange())) context.beginTerminalState(client) else { context.startPathToPest(client, currentTarget); context.setState(PestDestroyer.State.FLY_TO_PEST) }
            return true
        }
    }
}
