package dev.aether.modules.pathfinding.execution

import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3

class WalkingMotion private constructor() {
    class Input(private val forward: Int, private val right: Int) {
        fun forward() = forward
        fun right() = right
        override fun equals(other: Any?) = other is Input && forward == other.forward && right == other.right
        override fun hashCode() = 31 * forward + right
        override fun toString() = "Input[forward=$forward, right=$right]"
    }
    companion object {
        private val FORWARD = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
        private val RIGHT = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
        @JvmStatic fun horizontalInput(desiredDirection: Vec3, actualPlayerYaw: Float, deadzone: Double): Input {
            val distance = desiredDirection.horizontalDistance()
            if (!java.lang.Double.isFinite(distance) || !java.lang.Float.isFinite(actualPlayerYaw) || distance <= Math.max(1.0e-6, deadzone)) return Input(0, 0)
            val yaw = Math.toRadians(actualPlayerYaw.toDouble())
            val forward = -desiredDirection.x * Math.sin(yaw) + desiredDirection.z * Math.cos(yaw)
            val right = -desiredDirection.x * Math.cos(yaw) - desiredDirection.z * Math.sin(yaw)
            val octant = Math.floorMod(Math.round(Math.atan2(right, forward) / (Math.PI / 4.0)).toInt(), 8)
            return Input(FORWARD[octant], RIGHT[octant])
        }
        @JvmStatic fun direction(input: Input, actualPlayerYaw: Float): Vec3 {
            val yaw = Math.toRadians(actualPlayerYaw.toDouble())
            val direction = Vec3(-input.forward() * Math.sin(yaw) - input.right() * Math.cos(yaw), 0.0, input.forward() * Math.cos(yaw) - input.right() * Math.sin(yaw))
            return if (direction.lengthSqr() > 1.0e-12) direction.normalize() else Vec3.ZERO
        }
        @JvmStatic fun apply(client: Minecraft, input: Input) {
            ClientUtils.setKeyMappingState(client.options.keyUp, input.forward() > 0); ClientUtils.setKeyMappingState(client.options.keyDown, input.forward() < 0)
            ClientUtils.setKeyMappingState(client.options.keyRight, input.right() > 0); ClientUtils.setKeyMappingState(client.options.keyLeft, input.right() < 0)
        }
    }
}
