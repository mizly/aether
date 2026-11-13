package dev.aether.util

import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import java.util.Random

class RotationUtils {
    class Rotation(var yaw: Float, var pitch: Float)

    companion object {
        @JvmField
        val RANDOM = Random()

        @JvmStatic
        fun calculateLookAt(from: Vec3, to: Vec3): Rotation {
            val d0 = to.x - from.x
            val d1 = to.y - from.y
            val d2 = to.z - from.z
            val d3 = Math.sqrt(d0 * d0 + d2 * d2)
            val f = (Mth.atan2(d2, d0) * (180F / Math.PI.toFloat()).toDouble()).toFloat() - 90F
            val f1 = (-(Mth.atan2(d1, d3) * (180F / Math.PI.toFloat()).toDouble())).toFloat()
            return Rotation(f, f1)
        }

        // point t (0.0 to 1.0) along a quadratic bezier
        @JvmStatic
        fun calculateBezierPoint(t: Float, start: Rotation, end: Rotation, control: Rotation): Rotation {
            // Apply cubic easing for smoother start/end (Ease-In-Out)
            // Formula: f(t) = t^2 * (3 - 2t)
            val easedT = t * t * (3 - 2 * t)
            val oneMinusT = 1 - easedT
            // Quadratic Bezier Formula: B(t) = (1-t)^2 * P0 + 2(1-t)t * P1 + t^2 * P2
            val yaw = oneMinusT * oneMinusT * start.yaw + 2 * oneMinusT * easedT * control.yaw + easedT * easedT * end.yaw
            val pitch = oneMinusT * oneMinusT * start.pitch + 2 * oneMinusT * easedT * control.pitch + easedT * easedT * end.pitch
            return Rotation(yaw, pitch)
        }

        // random control point that fakes a human arc, following the shortest-path direction
        @JvmStatic
        fun generateControlPoint(start: Rotation, end: Rotation): Rotation {
            // Normalize yaw difference to shortest path
            val yawDiff = Mth.wrapDegrees(end.yaw - start.yaw)
            val targetYaw = start.yaw + yawDiff
            // Random offset
            val controlYaw = start.yaw + yawDiff * 0.5f + (RANDOM.nextFloat() - 0.5f) * 20.0f // +/- 10 degrees
            val controlPitch = start.pitch + (end.pitch - start.pitch) * 0.5f + (RANDOM.nextFloat() - 0.5f) * 10.0f // +/- 5 degrees
            // Return a rotation object. Note: targetYaw might be outside -180/180, distinct
            // from end.yaw's normalized value
            // But for the calculation it's fine.
            return Rotation(controlYaw, controlPitch)
        }

        // Helper to get the 'unwrapped' end rotation so interpolation works linearly
        @JvmStatic
        fun getAdjustedEnd(start: Rotation, end: Rotation): Rotation {
            val yawDiff = Mth.wrapDegrees(end.yaw - start.yaw)
            return Rotation(start.yaw + yawDiff, end.pitch)
        }

        @JvmStatic
        fun isLookingAt(currentYaw: Float, currentPitch: Float, eyePos: Vec3, targetPos: Vec3, tolerance: Float): Boolean {
            val target = calculateLookAt(eyePos, targetPos)
            val yawDiff = Math.abs(Mth.wrapDegrees(currentYaw - target.yaw))
            val pitchDiff = Math.abs(Mth.wrapDegrees(currentPitch - target.pitch))
            return yawDiff <= tolerance && pitchDiff <= tolerance
        }

        // range is in degrees
        @JvmStatic
        fun applyImprecision(rot: Rotation, range: Float): Rotation {
            if (range <= 0) return rot
            val yawOffset = (RANDOM.nextFloat() - 0.5f) * range
            val pitchOffset = (RANDOM.nextFloat() - 0.5f) * range
            return Rotation(rot.yaw + yawOffset, rot.pitch + pitchOffset)
        }
    }
}
