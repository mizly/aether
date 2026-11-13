package dev.aether.modules.pathfinding.rotation.strategy

import dev.aether.modules.pathfinding.rotation.AngleUtils
import dev.aether.modules.pathfinding.rotation.EasingType
import dev.aether.modules.pathfinding.rotation.IRotationStrategy
import dev.aether.modules.pathfinding.rotation.Rotation
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer

class TimedEaseStrategy(
    private val yawEasing: EasingType,
    private val pitchEasing: EasingType,
    private val duration: Long
) : IRotationStrategy {
    private var startYaw = 0f
    private var startPitch = 0f
    private var endTime = 0L

    constructor(easing: EasingType, durationMs: Long) : this(easing, easing, durationMs)

    override fun onStart() {
        val player = Minecraft.getInstance().player
        if (player == null) throw IllegalStateException("Player is null during rotation start")
        startYaw = player.yRot
        startPitch = player.xRot
        endTime = System.currentTimeMillis() + duration
    }

    override fun onRotate(player: LocalPlayer, targetYaw: Float, targetPitch: Float): Rotation {
        val now = System.currentTimeMillis()
        if (now >= endTime) return Rotation(targetYaw, targetPitch)
        val progress = 1f - ((endTime - now).toFloat() / duration.toFloat())
        val t = Math.max(0f, Math.min(1f, progress))
        val yawDelta = AngleUtils.normalizeAngle(targetYaw - startYaw)
        val yaw = yawEasing.apply(startYaw, startYaw + yawDelta, t)
        val pitch = clampPitch(pitchEasing.apply(startPitch, clampPitch(targetPitch), t))
        return Rotation(yaw, pitch)
    }

    companion object {
        private fun clampPitch(pitch: Float): Float = Math.max(-90f, Math.min(90f, pitch))
    }
}
