package dev.aether.modules.pathfinding.rotation.strategy

import dev.aether.modules.pathfinding.rotation.AngleUtils
import dev.aether.modules.pathfinding.rotation.IRotationStrategy
import dev.aether.modules.pathfinding.rotation.Rotation
import net.minecraft.client.player.LocalPlayer

class TrackingRotationStrategy(degreesPerSecond: Float) : IRotationStrategy {
    private var degreesPerSecond = 0f
    private var lastUpdate = 0L

    init {
        setSpeed(degreesPerSecond)
    }

    fun setSpeed(degreesPerSecond: Float) {
        this.degreesPerSecond = Math.clamp(degreesPerSecond, 1.0f, 1440.0f)
    }

    override fun onStart() {
        lastUpdate = System.nanoTime()
    }

    override fun onRotate(player: LocalPlayer, targetYaw: Float, targetPitch: Float): Rotation {
        val now = System.nanoTime()
        val seconds = Math.clamp((now - lastUpdate) / 1.0e9, 0.0, 0.1)
        lastUpdate = now
        return step(player.yRot, player.xRot, targetYaw, targetPitch, degreesPerSecond, seconds)
    }

    companion object {
        @JvmStatic
        fun step(yaw: Float, pitch: Float, targetYaw: Float, targetPitch: Float, degreesPerSecond: Float, seconds: Double): Rotation {
            val yawDelta = AngleUtils.getRotationDelta(yaw, targetYaw)
            val pitchDelta = Math.clamp(targetPitch, -90.0f, 90.0f) - pitch
            val magnitude = Math.hypot(yawDelta.toDouble(), pitchDelta.toDouble())
            val response = 1.0 - Math.exp(-10.0 * Math.max(0.0, seconds))
            val scale = if (magnitude < 1.0e-6) 0.0 else Math.min(response, Math.max(0.0, degreesPerSecond * seconds) / magnitude)
            return Rotation(yaw + (yawDelta * scale).toFloat(), Math.clamp(pitch + (pitchDelta * scale).toFloat(), -90.0f, 90.0f))
        }
    }
}
