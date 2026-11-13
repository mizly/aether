package dev.aether.modules.pathfinding.rotation

import dev.aether.modules.failsafe.FailsafeManager
import dev.aether.modules.pathfinding.rotation.strategy.TrackingRotationStrategy
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer

class RotationExecutor private constructor() {
    companion object {
        private val mc = Minecraft.getInstance()
        private const val EXTERNAL_ROTATION_TOLERANCE_DEGREES = 5.0f
        private var targetYaw = 0f
        private var targetPitch = 0f
        private var currStrat: IRotationStrategy? = null
        private var isRotating = false
        private var cachedGcd = Double.NaN
        private var lastAppliedYaw = 0f
        private var lastAppliedPitch = 0f
        private var hasLastApplied = false

        @JvmStatic fun rotateTo(endRot: Rotation, strategy: IRotationStrategy) {
            if (FailsafeManager.shouldSuppressPestCleanerRotation(mc)) return
            stopRotating()
            targetYaw = endRot.yaw
            targetPitch = endRot.pitch
            currStrat = strategy
            val sens = mc.options.sensitivity().get()
            val f = sens * 0.6 + 0.2
            cachedGcd = f * f * f * 1.2
            strategy.onStart()
            hasLastApplied = false
            isRotating = true
        }

        @JvmStatic fun stopRotating() {
            currStrat?.onStop()
            currStrat = null
            isRotating = false
            hasLastApplied = false
        }

        @JvmStatic fun track(target: Rotation, degreesPerSecond: Float) {
            if (FailsafeManager.shouldSuppressPestCleanerRotation(mc)) return
            val current = currStrat
            if (isRotating && current is TrackingRotationStrategy) {
                targetYaw = target.yaw
                targetPitch = target.pitch
                current.setSpeed(degreesPerSecond)
            } else {
                rotateTo(target, TrackingRotationStrategy(degreesPerSecond))
            }
        }

        @JvmStatic fun isRotating() = isRotating
        @JvmStatic fun getTargetYaw() = targetYaw
        @JvmStatic fun getTargetPitch() = targetPitch

        @JvmStatic fun update() {
            val client = Minecraft.getInstance()
            val player = client.player ?: return
            if (!isRotating) return
            if (hasExternalRotation(player)) {
                FailsafeManager.reportExternalRotation()
                stopRotating()
                return
            }
            val strategy = currStrat
            if (strategy != null) {
                val result = strategy.onRotate(player, targetYaw, targetPitch)
                if (result == null) {
                    stopRotating()
                } else {
                    val newYaw = applyGCD(result.yaw, player.yRot)
                    val newPitch = Math.max(-90f, Math.min(90f, applyGCD(result.pitch, player.xRot, -90f, 90f)))
                    player.setYRot(newYaw)
                    player.setXRot(newPitch)
                    player.yRotO = newYaw
                    player.xRotO = newPitch
                    lastAppliedYaw = player.yRot
                    lastAppliedPitch = player.xRot
                    hasLastApplied = true
                    FailsafeManager.expectRotation(player.yRot, player.xRot)
                }
            }
        }

        private fun hasExternalRotation(player: LocalPlayer): Boolean {
            if (!hasLastApplied) return false
            val yawDrift = Math.abs(AngleUtils.getRotationDelta(lastAppliedYaw, player.yRot))
            val pitchDrift = Math.abs(player.xRot - lastAppliedPitch)
            return yawDrift > EXTERNAL_ROTATION_TOLERANCE_DEGREES || pitchDrift > EXTERNAL_ROTATION_TOLERANCE_DEGREES
        }

        private fun applyGCD(rotation: Float, prevRotation: Float): Float = applyGCD(rotation, prevRotation, null, null)

        private fun applyGCD(rotation: Float, prevRotation: Float, min: Float?, max: Float?): Float {
            val gcd = if (java.lang.Double.isNaN(cachedGcd)) computeGcd() else cachedGcd
            val delta = AngleUtils.getRotationDelta(prevRotation, rotation)
            val roundedDelta = Math.round(delta / gcd) * gcd
            var result = (prevRotation + roundedDelta).toFloat()
            if (max != null && result > max) result -= gcd.toFloat()
            if (min != null && result < min) result += gcd.toFloat()
            return result
        }

        private fun computeGcd(): Double {
            val f = mc.options.sensitivity().get() * 0.6 + 0.2
            return f * f * f * 1.2
        }
    }
}
