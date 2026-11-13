package dev.aether.modules.pathfinding.execution

import dev.aether.modules.pathfinding.rotation.AngleUtils
import dev.aether.modules.pathfinding.rotation.Rotation

class FlightAim private constructor() {
    class Plan(private val rotation: Rotation, private val durationMs: Long) {
        fun rotation(): Rotation = rotation
        fun durationMs(): Long = durationMs
    }

    companion object {
        const val YAW_THRESHOLD = 1.0f
        const val PITCH_THRESHOLD = 6.0f
        const val MIN_DURATION_MS = 300L

        @JvmStatic
        fun plan(currentYaw: Float, currentPitch: Float, desired: Rotation?, rotating: Boolean, pendingYaw: Float, pendingPitch: Float): Plan? {
            if (desired == null) return null
            val sourceYaw = if (rotating) pendingYaw else currentYaw
            val sourcePitch = if (rotating) pendingPitch else currentPitch
            val yawDrift = Math.abs(AngleUtils.getRotationDelta(sourceYaw, desired.yaw))
            val pitchDrift = Math.abs(AngleUtils.getRotationDelta(sourcePitch, desired.pitch))
            val settled = !rotating || (Math.abs(AngleUtils.getRotationDelta(currentYaw, sourceYaw)) <= YAW_THRESHOLD && Math.abs(currentPitch - sourcePitch) <= PITCH_THRESHOLD)
            if (!((settled && (yawDrift > YAW_THRESHOLD || pitchDrift > PITCH_THRESHOLD)) || yawDrift > 16.0f || pitchDrift > 14.0f)) return null
            val turn = Math.max(Math.abs(AngleUtils.getRotationDelta(currentYaw, desired.yaw)), Math.abs(desired.pitch - currentPitch))
            return Plan(desired, Math.max(MIN_DURATION_MS, (turn * 1000.0f / 180.0f).toLong()))
        }
    }
}
