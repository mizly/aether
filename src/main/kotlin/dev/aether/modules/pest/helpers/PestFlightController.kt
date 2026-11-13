package dev.aether.modules.pest.helpers

import dev.aether.config.AetherConfig
import dev.aether.modules.pathfinding.execution.FlightMotion
import dev.aether.modules.pathfinding.execution.FlightPathClearance
import dev.aether.modules.pathfinding.execution.WalkingMotion
import dev.aether.modules.rotation.RotationManager
import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3

class PestFlightController {
    private var targetId = -1
    private var lastTick = 0
    private var lastPosition: Vec3? = null
    private var targetVelocity = Vec3.ZERO
    private var approachRange = 12.0
    private var walkingApproach = false

    fun reset() {
        targetId = -1
        lastPosition = null
        targetVelocity = Vec3.ZERO
        approachRange = 12.0
        walkingApproach = false
    }

    fun sampleVelocity(id: Int, position: Vec3, tick: Int): Vec3 {
        val previous = lastPosition
        val elapsed = tick - lastTick
        if (id != targetId || previous == null || elapsed < 0 || elapsed > 5 || position.distanceToSqr(previous) > 9.0) {
            targetVelocity = Vec3.ZERO
            walkingApproach = false
        } else if (elapsed == 0) return targetVelocity
        else {
            val sample = position.subtract(previous).scale(1.0 / elapsed)
            targetVelocity = targetVelocity.scale(0.65).add(sample.scale(0.35))
        }
        targetId = id
        lastPosition = position
        lastTick = tick
        return targetVelocity
    }

    fun handoffDistance(velocity: Vec3, vacuumRange: Double): Double {
        approachRange = maxOf(approachRange, AetherConfig.PEST_VACUUM_FOLLOW_DISTANCE.get().toDouble()
            + velocity.horizontalDistance() * FlightMotion.coastTicks(AetherConfig.FLY_BRAKING_LOOKAHEAD_TICKS.get().toDouble()) + 2.0)
        return maxOf(vacuumRange, approachRange)
    }

    fun canApproachDirectly(client: Minecraft, target: Entity, vacuumRange: Double): Boolean {
        val player = client.player ?: return false
        if (client.level == null || player.distanceTo(target) > handoffDistance(player.deltaMovement, vacuumRange)) return false
        val eye = target.position().add(0.0, target.getEyeHeight(target.pose).toDouble(), 0.0)
        if (!ClientUtils.hasLineOfSight(player, eye)) return false
        val destination = target.position().add(0.0, if (player.abilities.flying) 3.0 else 0.0, 0.0)
        return FlightPathClearance.isClear(client, player.position(), destination)
    }

    fun update(client: Minecraft, target: Entity, vacuumRange: Double, canTranslate: Boolean) {
        val player = client.player ?: return
        val velocity = player.deltaMovement
        val pestVelocity = sampleVelocity(target.id, target.position(), player.tickCount)
        val offset = target.position().subtract(player.position())
        val followHeight = if (player.abilities.flying) 3.0 else offset.y
        val follow = followDistance(AetherConfig.PEST_VACUUM_FOLLOW_DISTANCE.get().toDouble(), vacuumRange, followHeight)
        if (AetherConfig.PEST_DESTROYER_WALK_MODE.get()) {
            WalkingMotion.apply(client, walkingInput(offset, player.yRot, follow, canTranslate))
            ClientUtils.setKeyMappingState(client.options.keySprint, false)
            ClientUtils.setKeyMappingState(client.options.keyJump, false)
            ClientUtils.setKeyMappingState(client.options.keyShift, false)
            RotationManager.trackRotation(client, PestAimTracker.trackingAim(client, target), AetherConfig.PEST_TRACKING_SMOOTHING_MS.get(), AetherConfig.PEST_MAX_TURN_SPEED.get())
            return
        }
        walkingApproach = false
        var desired = if (canTranslate) FlightMotion.approachVelocity(offset, pestVelocity, follow, AetherConfig.PEST_APPROACH_SPEED.get().toDouble(), AetherConfig.FLY_BRAKING_LOOKAHEAD_TICKS.get().toDouble()) else Vec3.ZERO
        if (!facesTarget(offset, player.yRot)) desired = Vec3.ZERO
        FlightMotion.apply(client, FlightMotion.horizontalInput(desired, velocity, player.yRot))
        val vertical = if (canTranslate && player.abilities.flying) FlightMotion.verticalInput(offset.y + 3.0, velocity.y, 0.5) else 0
        ClientUtils.setKeyMappingState(client.options.keyJump, vertical > 0)
        ClientUtils.setKeyMappingState(client.options.keyShift, vertical < 0)
        RotationManager.trackRotation(client, PestAimTracker.trackingAim(client, target), AetherConfig.PEST_TRACKING_SMOOTHING_MS.get(), AetherConfig.PEST_MAX_TURN_SPEED.get())
    }

    fun walkingInput(offset: Vec3, yaw: Float, follow: Double, canTranslate: Boolean): WalkingMotion.Input {
        if (!canTranslate || !facesTarget(offset, yaw)) {
            walkingApproach = false
            return WalkingMotion.Input(0, 0)
        }
        val horizontal = offset.horizontalDistance()
        walkingApproach = horizontal > follow + if (walkingApproach) 0.0 else WALK_REAPPROACH_BUFFER
        return if (walkingApproach) WalkingMotion.horizontalInput(offset, yaw, 0.0) else WalkingMotion.Input(0, 0)
    }

    companion object {
        private const val WALK_REAPPROACH_BUFFER = 0.75

        @JvmStatic fun followDistance(configured: Double, range: Double, heightDifference: Double): Double {
            val usableRange = maxOf(0.0, range - 1.0)
            return minOf(configured, kotlin.math.sqrt(maxOf(0.0, usableRange * usableRange - heightDifference * heightDifference)))
        }

        @JvmStatic fun facesTarget(offset: Vec3, yaw: Float): Boolean {
            val horizontal = offset.horizontalDistance()
            if (horizontal < 0.25) return false
            val angle = Math.toRadians(yaw.toDouble())
            return (-offset.x * Math.sin(angle) + offset.z * Math.cos(angle)) / horizontal >= 0.5
        }
    }
}
