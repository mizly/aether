package dev.aether.modules.pathfinding.execution

import dev.aether.config.AetherConfig
import dev.aether.modules.pathfinding.Node
import dev.aether.modules.pathfinding.Node.MoveType
import dev.aether.modules.pathfinding.rotation.AngleUtils
import dev.aether.modules.pathfinding.rotation.RotationExecutor
import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft
import net.minecraft.tags.BlockTags
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3

class PathExecutor {
    enum class State { IDLE, WALKING, REPLANNING, FINISHED, FAILED }

    private var state = State.IDLE
    private var path: List<Node> = emptyList()
    private var route = WalkingRoute(java.util.List.of())
    private var aimSpline = WalkingAimSpline(java.util.List.of())
    private var cameraPath: List<Vec3> = emptyList()
    private var aimPoint: Vec3? = null
    private var movementDirection = Vec3.ZERO
    private var progressAnchor: Vec3? = null
    private var backupDirection = Vec3.ZERO
    private var pursuitSegment = 0
    private var goalX = 0
    private var goalY = 0
    private var goalZ = 0
    private var goalCenterX = 0.5
    private var goalCenterZ = 0.5
    private var precise = false
    private var rotationTarget: Entity? = null
    private var lookTargetPos: Vec3? = null
    private var allowReplan = true
    private var allowJumps = true
    private var allowRotation = true
    private var exactGoalCentering = false
    private var strictGoalCompletion = false
    private var stickySneakDistance = -1.0
    private var sneakLatched = false
    private var preciseGoalTolerance = 0.5
    private var onFinished: Runnable? = null
    private var jumpCooldown = 0
    private var backupTicksLeft = 0
    private var recoveryAttempted = false
    private var lastProgressTime = 0L
    private var lastPathProgressTime = 0L
    private var lastReplanTime = 0L
    private var bestPathProgress = 0.0

    fun start(path: List<Node>?, goalX: Int, goalY: Int, goalZ: Int, precise: Boolean) {
        start(path, goalX, goalY, goalZ, precise, null)
    }

    fun start(path: List<Node>?, goalX: Int, goalY: Int, goalZ: Int, precise: Boolean, rotationTarget: Entity?) {
        start(path, goalX, goalY, goalZ, precise, rotationTarget, null)
    }

    fun start(
        path: List<Node>?, goalX: Int, goalY: Int, goalZ: Int, precise: Boolean,
        rotationTarget: Entity?, onFinished: Runnable?
    ) {
        this.path = if (path == null) emptyList() else path.toList()
        this.goalX = goalX
        this.goalY = goalY
        this.goalZ = goalZ
        this.goalCenterX = 0.5
        this.goalCenterZ = 0.5
        this.precise = precise
        this.pursuitSegment = 0
        this.rotationTarget = rotationTarget
        this.lookTargetPos = null
        this.allowReplan = true
        this.allowJumps = true
        this.allowRotation = true
        this.exactGoalCentering = false
        this.strictGoalCompletion = false
        this.stickySneakDistance = -1.0
        this.sneakLatched = false
        this.preciseGoalTolerance = 0.5
        this.onFinished = onFinished
        this.jumpCooldown = 0
        this.backupTicksLeft = 0
        this.recoveryAttempted = false
        this.progressAnchor = null
        this.movementDirection = Vec3.ZERO
        this.aimPoint = null
        this.lastProgressTime = System.currentTimeMillis()
        this.lastPathProgressTime = lastProgressTime
        this.bestPathProgress = Double.NEGATIVE_INFINITY
        val anchors: List<Vec3> = this.path.map { feetCenter(it) }
        this.route = WalkingRoute(anchors)
        this.aimSpline = WalkingAimSpline(anchors)
        this.cameraPath = aimSpline.points(1.62)
        state = State.WALKING
    }

    fun setLookTarget(lookTargetPos: Vec3?) {
        this.lookTargetPos = lookTargetPos
    }

    fun setRotationTarget(rotationTarget: Entity?) {
        this.rotationTarget = rotationTarget
    }

    fun setAllowReplan(allowReplan: Boolean) {
        this.allowReplan = allowReplan
    }

    fun setPreciseGoalTolerance(tolerance: Double) {
        preciseGoalTolerance = Math.max(0.01, tolerance)
    }

    fun setAllowJumps(allowJumps: Boolean) {
        this.allowJumps = allowJumps
    }

    fun setAllowRotation(allowRotation: Boolean) {
        if (this.allowRotation && !allowRotation) RotationExecutor.stopRotating()
        this.allowRotation = allowRotation
    }

    fun setExactGoalCentering(exactGoalCentering: Boolean) {
        this.exactGoalCentering = exactGoalCentering
    }

    fun setStrictGoalCompletion(strictGoalCompletion: Boolean) {
        this.strictGoalCompletion = strictGoalCompletion
    }

    fun setStickySneakDistance(distance: Double) {
        stickySneakDistance = distance
    }

    fun setSneakLatched(sneakLatched: Boolean) {
        this.sneakLatched = sneakLatched
    }

    fun setGoalCenterOffsets(x: Double, z: Double) {
        goalCenterX = x
        goalCenterZ = z
    }

    fun isSneakLatched(): Boolean = sneakLatched
    fun getState(): State = state
    fun getWaypointIndex(): Int = pursuitSegment
    fun getCamTargetIdx(): Int = if (aimPoint == null) -1 else aimSpline.index()
    fun getCameraPath(): List<Vec3> = cameraPath
    fun getCameraIndex(): Int = aimSpline.index()
    fun getAimPoint(): Vec3? = aimPoint
    fun getMovementDirection(): Vec3 = movementDirection

    fun tick(mc: Minecraft) {
        if (state != State.WALKING && state != State.REPLANNING) return
        val player = mc.player
        if (player == null || mc.level == null || state == State.REPLANNING) {
            releaseAll(mc)
            return
        }
        val now = System.currentTimeMillis()
        if (ClientUtils.isInventoryScreenOpen() || player.abilities.flying) {
            releaseAll(mc)
            RotationExecutor.stopRotating()
            resetProgress(player.position(), now)
            if (player.abilities.flying) ClientUtils.setKeyMappingState(mc.options.keyShift, true)
            return
        }
        jumpCooldown = Math.max(0, jumpCooldown - 1)
        ClientUtils.setKeyMappingState(mc.options.keyJump, false)
        if (path.isEmpty()) {
            fail(mc)
            return
        }

        val playerPos = player.position()
        val goal = Vec3(goalX + goalCenterX, goalY.toDouble(), goalZ + goalCenterZ)
        val goalDistance = playerPos.subtract(goal).horizontalDistance()
        val goalTolerance = if (precise || exactGoalCentering || strictGoalCompletion)
            preciseGoalTolerance else GOAL_REACHED_DISTANCE
        if (route.endsAt(goal) && WalkingRoute.reachedGoal(playerPos, goal, goalTolerance)
            && (player.onGround() || player.isInWater || isOnClimbable(mc))
        ) {
            finish(mc)
            return
        }

        pursuitSegment = route.advance(playerPos, pursuitSegment)
        if (pursuitSegment >= path.size - 1 && !route.endsAt(goal)) {
            if (now - lastReplanTime >= REPLAN_COOLDOWN_MS) {
                triggerReplan(mc)
            } else {
                releaseAll(mc)
            }
            return
        }
        updateProgress(playerPos, now)
        updateAim(mc, playerPos)
        if (!sneakLatched && stickySneakDistance > 0.0 && playerPos.distanceTo(goal) <= stickySneakDistance) {
            sneakLatched = true
        }
        ClientUtils.setKeyMappingState(mc.options.keyShift, sneakLatched)

        val timeout = AetherConfig.PATHFINDER_STUCK_TIMEOUT_MS.get().toLong()
        val pathStale = now - lastPathProgressTime
        val motionStale = now - lastProgressTime
        val drift = route.distance(playerPos, pursuitSegment)
        if ((pathStale > timeout * 2 || (pathStale > timeout && drift > 1.75))
            && now - lastReplanTime >= REPLAN_COOLDOWN_MS
        ) {
            triggerReplan(mc)
            return
        }

        if (backupTicksLeft > 0) {
            if (!player.onGround() || !hasBackupSupport(mc, backupDirection)) {
                backupTicksLeft = 0
                releaseAll(mc)
                return
            }
            backupTicksLeft--
            WalkingMotion.apply(
                mc, WalkingMotion.horizontalInput(
                    backupDirection, player.yRot, MOVEMENT_DEADZONE
                )
            )
            movementDirection = backupDirection
            return
        }
        if (!recoveryAttempted && motionStale > timeout && player.onGround() && !sneakLatched) {
            recoveryAttempted = true
            backupDirection = movementDirection.scale(-1.0)
            if (backupDirection.horizontalDistanceSqr() > 1.0e-6 && hasBackupSupport(mc, backupDirection)) {
                backupTicksLeft = BACKUP_TICKS
                releaseAll(mc)
                return
            }
        }

        val waypoint = path[Math.min(path.size - 1, pursuitSegment + 1)]
        val atRouteEnd = pursuitSegment >= path.size - 1
        var target = if (atRouteEnd) goal else route.steeringTarget(playerPos, pursuitSegment, STEERING_LOOKAHEAD)
        val centering = route.endsAt(goal) && (precise || exactGoalCentering || strictGoalCompletion)
            && goalDistance < 1.5 && Math.abs(playerPos.y - goal.y) <= 0.75
            && pursuitSegment >= path.size - 2
        if (centering) target = goal
        val offset = target.subtract(playerPos).multiply(1.0, 0.0, 1.0)
        val input = WalkingMotion.horizontalInput(
            offset, player.yRot,
            if (centering) Math.min(MOVEMENT_DEADZONE, goalTolerance * 0.5) else MOVEMENT_DEADZONE
        )
        WalkingMotion.apply(mc, input)
        movementDirection = WalkingMotion.direction(input, player.yRot)

        val approachingRise = target.y - playerPos.y > 0.6 && offset.horizontalDistance() < 2.0
        val sharpTurn = approachingCorner(playerPos)
        val sprint = AetherConfig.PATHFINDER_SPRINT.get()
            && input.forward() > 0 && !sneakLatched && !centering
            && goalDistance > 2.5 && !approachingRise && !sharpTurn
        ClientUtils.setKeyMappingState(mc.options.keySprint, sprint)
        if (!sprint) player.setSprinting(false)
        if (centering) ClientUtils.setKeyMappingState(mc.options.keyShift, true)

        if (allowJumps && !centering) {
            handleJumps(mc, waypoint, playerPos, motionStale > timeout / 2.0)
        }
        if (player.isInWater && allowJumps && target.y > playerPos.y + 0.2) {
            ClientUtils.setKeyMappingState(mc.options.keyJump, true)
        }
        if (isOnClimbable(mc)) {
            if (target.y > playerPos.y + 0.2 && allowJumps) {
                ClientUtils.setKeyMappingState(mc.options.keyJump, true)
            } else if (target.y < playerPos.y - 0.2) {
                ClientUtils.setKeyMappingState(mc.options.keyShift, sneakLatched)
            }
        }
    }

    private fun updateAim(mc: Minecraft, playerPos: Vec3) {
        val player = mc.player ?: return
        val splinePoint = aimSpline.aimPoint(
            playerPos, pursuitSegment,
            AetherConfig.PATHFINDER_AIM_LOOKAHEAD.get().toDouble(), player.eyeHeight.toDouble()
        )
        val lookTarget = lookTargetPos
        val rotTarget = rotationTarget
        aimPoint = if (lookTarget != null) {
            lookTarget
        } else if (rotTarget != null && rotTarget.isAlive && !rotTarget.isRemoved
            && rotTarget.level() == mc.level
        ) {
            rotTarget.eyePosition
        } else {
            splinePoint
        }
        val aim = aimPoint
        if (allowRotation && aim != null && aim.distanceToSqr(player.eyePosition) > 0.01) {
            RotationExecutor.track(
                AngleUtils.getRotation(player.eyePosition, aim),
                AetherConfig.PATHFINDER_TURN_SPEED.get()
            )
        }
    }

    private fun handleJumps(mc: Minecraft, waypoint: Node, playerPos: Vec3, stalled: Boolean) {
        val player = mc.player ?: return
        if (!player.onGround() || jumpCooldown > 0 || sneakLatched
            || player.isInWater || isOnClimbable(mc)
        ) return
        val predictionTicks = AetherConfig.PATHFINDER_JUMP_LOOKAHEAD_TICKS.get().toDouble()
        val triggerDistance = 0.35 + player.deltaMovement.horizontalDistance() * predictionTicks
        var jump = false
        var obstacleAhead = false
        if (AetherConfig.PATHFINDER_RAYCAST_JUMP.get()) {
            val obstacle = WalkingObstacleProbe.probe(
                mc, movementDirection, 0.35, predictionTicks,
                AetherConfig.PATHFINDER_MAX_JUMP_HEIGHT.get().toDouble()
            )
            jump = obstacle.jumpRequired()
            obstacleAhead = obstacle.obstacleAhead()
        } else {
            val rise = waypoint.position.flooredY().toDouble() - playerPos.y
            val distance = feetCenter(waypoint).subtract(playerPos).horizontalDistance()
            jump = rise > player.maxUpStep() && distance < 0.8 + triggerDistance
                && WalkingObstacleProbe.hasJumpHeadroom(mc)
        }
        if (!jump && !obstacleAhead && waypoint.moveType == MoveType.PARKOUR && pursuitSegment < path.size - 1) {
            val takeoff = feetCenter(path[pursuitSegment])
            val takeoffDistance = takeoff.subtract(playerPos).horizontalDistance()
            jump = takeoffDistance <= 0.4 + triggerDistance && WalkingObstacleProbe.hasJumpHeadroom(mc)
        }
        if (!jump && stalled && player.horizontalCollision && !AetherConfig.PATHFINDER_RAYCAST_JUMP.get()) {
            jump = WalkingObstacleProbe.hasJumpHeadroom(mc)
        }
        if (jump) {
            ClientUtils.setKeyMappingState(mc.options.keyJump, true)
            jumpCooldown = JUMP_COOLDOWN_TICKS
        }
    }

    private fun approachingCorner(playerPos: Vec3): Boolean {
        if (pursuitSegment + 2 >= path.size) return false
        val corner = feetCenter(path[pursuitSegment + 1])
        if (corner.subtract(playerPos).horizontalDistance() > 1.5) return false
        val incoming = corner.subtract(feetCenter(path[pursuitSegment])).multiply(1.0, 0.0, 1.0).normalize()
        val outgoing = feetCenter(path[pursuitSegment + 2]).subtract(corner).multiply(1.0, 0.0, 1.0).normalize()
        return incoming.dot(outgoing) < 0.7
    }

    private fun hasBackupSupport(mc: Minecraft, direction: Vec3): Boolean {
        val player = mc.player ?: return false
        val level = mc.level ?: return false
        val distance = Math.max(0.6, player.deltaMovement.horizontalDistance() * 2.0)
        val offset = direction.normalize().scale(distance)
        val samples = Math.max(1, Math.ceil(distance / 0.2).toInt())
        for (sample in 1..samples) {
            val box = player.boundingBox.move(offset.scale(sample.toDouble() / samples))
            if (!level.noCollision(player, box)
                || level.noCollision(player, box.move(0.0, -0.5, 0.0))
            ) return false
        }
        return true
    }

    private fun updateProgress(position: Vec3, now: Long) {
        val anchor = progressAnchor
        if (anchor == null || position.distanceTo(anchor) >= 0.15) {
            progressAnchor = position
            lastProgressTime = now
        }
        val progress = route.progress(position, pursuitSegment)
        if (progress > bestPathProgress + PROGRESS_EPSILON) {
            bestPathProgress = progress
            lastPathProgressTime = now
            recoveryAttempted = false
        }
    }

    private fun resetProgress(position: Vec3, now: Long) {
        progressAnchor = position
        lastProgressTime = now
        lastPathProgressTime = now
        backupTicksLeft = 0
        recoveryAttempted = false
    }

    private fun triggerReplan(mc: Minecraft) {
        if (!allowReplan) {
            fail(mc)
            return
        }
        releaseAll(mc)
        RotationExecutor.stopRotating()
        lastReplanTime = System.currentTimeMillis()
        state = State.REPLANNING
    }

    fun stop(mc: Minecraft) {
        state = State.IDLE
        sneakLatched = false
        RotationExecutor.stopRotating()
        releaseAll(mc)
    }

    fun releaseAll(mc: Minecraft) {
        movementDirection = Vec3.ZERO
        mc.player?.setSprinting(false)
        if (mc.options == null) return
        ClientUtils.setKeyMappingState(mc.options.keyUp, false)
        ClientUtils.setKeyMappingState(mc.options.keyDown, false)
        ClientUtils.setKeyMappingState(mc.options.keyLeft, false)
        ClientUtils.setKeyMappingState(mc.options.keyRight, false)
        ClientUtils.setKeyMappingState(mc.options.keyJump, false)
        ClientUtils.setKeyMappingState(mc.options.keySprint, false)
        ClientUtils.setKeyMappingState(mc.options.keyShift, sneakLatched)
    }

    private fun finish(mc: Minecraft) {
        RotationExecutor.stopRotating()
        releaseAll(mc)
        state = State.FINISHED
        onFinished?.run()
    }

    private fun fail(mc: Minecraft) {
        RotationExecutor.stopRotating()
        releaseAll(mc)
        state = State.FAILED
    }

    companion object {
        private const val JUMP_COOLDOWN_TICKS = 8
        private const val BACKUP_TICKS = 6
        private const val MOVEMENT_DEADZONE = 0.08
        private const val STEERING_LOOKAHEAD = 1.0
        private const val PROGRESS_EPSILON = 0.08
        private const val GOAL_REACHED_DISTANCE = 1.2
        private const val REPLAN_COOLDOWN_MS = 1500L

        private fun feetCenter(node: Node): Vec3 {
            return Vec3(node.position.centeredX().toDouble(), node.position.flooredY().toDouble(), node.position.centeredZ().toDouble())
        }

        private fun isOnClimbable(mc: Minecraft): Boolean {
            val level = mc.level ?: return false
            val player = mc.player ?: return false
            return level.getBlockState(player.blockPosition()).`is`(BlockTags.CLIMBABLE)
        }
    }
}
