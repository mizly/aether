package dev.aether.modules.pathfinding.rotation

import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3

class AngleUtils private constructor() {
    companion object {
        private val mc: Minecraft get() = Minecraft.getInstance()

        @JvmStatic
        fun normalizeAngle(angle: Float): Float {
            var a = angle % 360f
            if (a >= 180f) a -= 360f
            if (a < -180f) a += 360f
            return a
        }

        @JvmStatic
        fun getRotationDelta(from: Float, to: Float): Float {
            var delta = normalizeAngle(to) - normalizeAngle(from)
            if (delta > 180f) delta -= 360f
            if (delta < -180f) delta += 360f
            return delta
        }

        @JvmStatic
        fun getRotation(from: Vec3, to: Vec3): Rotation {
            val xDiff = to.x - from.x
            val yDiff = to.y - from.y
            val zDiff = to.z - from.z
            val dist = Math.sqrt(xDiff * xDiff + zDiff * zDiff)
            val yaw = Math.toDegrees(Math.atan2(zDiff, xDiff)).toFloat() - 90f
            val pitch = -Math.toDegrees(Math.atan2(yDiff, dist)).toFloat()
            return Rotation(yaw, pitch)
        }

        @JvmStatic
        fun getRotation(to: Vec3): Rotation {
            val player = mc.player ?: throw IllegalStateException("Player is null")
            return getRotation(player.getEyePosition(), to)
        }

        @JvmStatic
        fun getRotation(to: Entity): Rotation {
            val player = mc.player ?: throw IllegalStateException("Player is null")
            val height = Math.min((to.getBbHeight() * 0.85) + (Math.random() * 0.3 - 0.15), 1.7)
            return getRotation(player.getEyePosition(), to.position().add(0.0, height, 0.0))
        }
    }
}
