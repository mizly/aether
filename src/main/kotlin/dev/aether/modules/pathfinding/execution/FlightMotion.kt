package dev.aether.modules.pathfinding.execution

import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import java.util.function.Predicate

class FlightMotion private constructor() {
    class Input(private val forward: Int, private val right: Int) {
        fun forward() = forward
        fun right() = right
        override fun equals(other: Any?) = other is Input && forward == other.forward && right == other.right
        override fun hashCode() = 31 * forward + right
        override fun toString() = "Input[forward=$forward, right=$right]"
    }
    companion object {
        private const val HORIZONTAL_DRAG = 0.91
        private const val DRIVE_DEADBAND = 0.04
        private const val BRAKE_DEADBAND = 0.18
        @JvmStatic fun coastTicks(extraTicks: Double) = 1.0 / (1.0 - HORIZONTAL_DRAG) + Math.max(0.0, extraTicks)
        @JvmStatic fun approachVelocity(offset: Vec3, targetVelocity: Vec3, distance: Double, maxSpeed: Double, extraTicks: Double): Vec3 {
            val horizontal = offset.horizontalDistance(); val speed = Math.min(maxSpeed, Math.max(0.0, horizontal - distance) / coastTicks(extraTicks))
            val closing = if (horizontal > 1.0e-6) Vec3(offset.x * speed / horizontal, 0.0, offset.z * speed / horizontal) else Vec3.ZERO
            val desired = closing.add(targetVelocity.x, 0.0, targetVelocity.z); val length = desired.horizontalDistance()
            return if (length > maxSpeed) desired.scale(maxSpeed / length) else desired
        }
        @JvmStatic fun horizontalInput(desiredVelocity: Vec3, velocity: Vec3, yaw: Float) = horizontalInput(desiredVelocity, velocity, yaw, BRAKE_DEADBAND)
        @JvmStatic fun brakingInput(velocity: Vec3, yaw: Float) = horizontalInput(Vec3.ZERO, velocity, yaw, 0.001)
        @JvmStatic fun avoidObstacles(requested: Input, velocity: Vec3, yaw: Float, acceleration: Double, canCoast: Predicate<Vec3>): Input {
            val desired = velocity.add(acceleration(requested, yaw, acceleration)); if (canCoast.test(desired)) return requested
            var best = brakingInput(velocity, yaw); var bestScore = Double.POSITIVE_INFINITY
            for (forward in -1..1) for (right in -1..1) {
                val candidate = Input(forward, right); val next = velocity.add(acceleration(candidate, yaw, acceleration)); val score = next.distanceToSqr(desired)
                if (score < bestScore && canCoast.test(next)) { best = candidate; bestScore = score }
            }
            return best
        }
        @JvmStatic fun acceleration(input: Input, yaw: Float, amount: Double): Vec3 {
            var local = Vec3(-input.right().toDouble(), 0.0, input.forward().toDouble())
            if (local.lengthSqr() > 1.0) local = local.normalize()
            return local.yRot((-Math.toRadians(yaw.toDouble())).toFloat()).scale(amount)
        }
        private fun horizontalInput(desiredVelocity: Vec3, velocity: Vec3, yaw: Float, brakeDeadband: Double): Input {
            val angle = Math.toRadians(yaw.toDouble()); val sin = Math.sin(angle); val cos = Math.cos(angle)
            return Input(axisInput(-desiredVelocity.x * sin + desiredVelocity.z * cos, -velocity.x * sin + velocity.z * cos, brakeDeadband), axisInput(-desiredVelocity.x * cos - desiredVelocity.z * sin, -velocity.x * cos - velocity.z * sin, brakeDeadband))
        }
        private fun axisInput(desired: Double, current: Double, brakeDeadband: Double): Int {
            val error = desired - current; val deadband = if (desired * error <= 0.0) brakeDeadband else DRIVE_DEADBAND
            if (Math.abs(error) <= deadband) return 0
            return if (error > 0.0) 1 else -1
        }
        @JvmStatic fun shouldCoast(offset: Vec3, velocity: Vec3, tolerance: Double, extraTicks: Double): Boolean {
            val distance = offset.horizontalDistance(); if (distance <= tolerance) return true
            val speed = velocity.horizontalDistance(); if (speed < 0.01) return false
            val along = (offset.x * velocity.x + offset.z * velocity.z) / speed; val lateral = Math.abs(offset.x * velocity.z - offset.z * velocity.x) / speed
            return along > 0.0 && lateral <= tolerance && speed * coastTicks(extraTicks) >= along - tolerance
        }
        @JvmStatic fun verticalInput(heightError: Double, velocity: Double, tolerance: Double): Int {
            val projectedError = heightError - velocity / (1.0 - 0.6)
            if (heightError > tolerance && projectedError > tolerance) return 1
            if (heightError < -tolerance && projectedError < -tolerance) return -1
            return 0
        }
        @JvmStatic fun apply(client: Minecraft, input: Input) {
            ClientUtils.setKeyMappingState(client.options.keyUp, input.forward() > 0); ClientUtils.setKeyMappingState(client.options.keyDown, input.forward() < 0)
            ClientUtils.setKeyMappingState(client.options.keyRight, input.right() > 0); ClientUtils.setKeyMappingState(client.options.keyLeft, input.right() < 0)
            ClientUtils.setKeyMappingState(client.options.keySprint, false)
        }
    }
}
