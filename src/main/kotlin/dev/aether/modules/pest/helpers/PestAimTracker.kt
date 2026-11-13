package dev.aether.modules.pest.helpers

import dev.aether.config.AetherConfig
import dev.aether.util.RotationUtils
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ThreadLocalRandom

internal class PestAimTracker private constructor() {
    companion object {
        private const val SAMPLE_MIN_MS = 40L
        private const val SAMPLE_STALE_MS = 400L
        private const val VELOCITY_BLEND = 0.4
        private const val MAX_LEAD_BLOCKS = 2.5
        private const val DRIFT_HOLD_MIN_MS = 240L
        private const val DRIFT_HOLD_MAX_MS = 720L
        private const val DRIFT_SMOOTHING_MS = 280.0
        private const val MAX_DRIFT_STEP_MS = 200L
        private const val DRIFT_SILHOUETTE_FRACTION = 0.45
        private const val MIN_DRIFT_DEGREES = 0.12
        private const val MAX_DRIFT_DEGREES = 2.5
        private const val DRIFT_SETTLED_DEGREES = 1.0e-3

        private var trackedId = -1
        private var lastPosition: Vec3? = null
        private var lastSampleAt = 0L
        private var velocity = Vec3.ZERO
        private var driftYaw = 0.0
        private var driftPitch = 0.0
        private var driftGoalYaw = 0.0
        private var driftGoalPitch = 0.0
        private var driftGoalUntil = 0L
        private var lastDriftAt = 0L

        @JvmStatic fun reset() {
            trackedId = -1
            lastPosition = null
            lastSampleAt = 0L
            velocity = Vec3.ZERO
            driftYaw = 0.0
            driftPitch = 0.0
            driftGoalYaw = 0.0
            driftGoalPitch = 0.0
            driftGoalUntil = 0L
            lastDriftAt = 0L
        }

        @JvmStatic fun trackingAim(client: Minecraft, target: Entity): Vec3 {
            val now = System.currentTimeMillis()
            if (trackedId != target.id) {
                reset()
                trackedId = target.id
            }
            sampleVelocity(target, now)
            val predictedEye = target.position().add(lead()).add(0.0, target.getEyeHeight(target.pose).toDouble(), 0.0)
            val aim = PestCombatCoordinator.buildVacuumAimTarget(client, target, predictedEye)
            return applyDrift(client, target, aim, now)
        }

        private fun sampleVelocity(target: Entity, now: Long) {
            val position = target.position()
            val previous = lastPosition
            if (previous == null || now - lastSampleAt > SAMPLE_STALE_MS) {
                lastPosition = position
                lastSampleAt = now
                velocity = Vec3.ZERO
                return
            }
            val elapsed = now - lastSampleAt
            if (elapsed < SAMPLE_MIN_MS) return
            val sample = position.subtract(previous).scale(1000.0 / elapsed)
            velocity = velocity.add(sample.subtract(velocity).scale(VELOCITY_BLEND))
            lastPosition = position
            lastSampleAt = now
        }

        private fun lead(): Vec3 {
            val lead = velocity.scale(AetherConfig.PEST_TRACKING_SMOOTHING_MS.get() / 1000.0)
            val length = lead.length()
            return if (length > MAX_LEAD_BLOCKS) lead.scale(MAX_LEAD_BLOCKS / length) else lead
        }

        private fun applyDrift(client: Minecraft, target: Entity, aim: Vec3, now: Long): Vec3 {
            advanceDrift(now, driftAmplitudeDegrees(client, target, aim))
            if (Math.abs(driftYaw) < DRIFT_SETTLED_DEGREES && Math.abs(driftPitch) < DRIFT_SETTLED_DEGREES) return aim
            val eye = client.player?.eyePosition ?: return aim
            val distance = aim.distanceTo(eye)
            if (distance < 1.0e-4) return aim
            val look = RotationUtils.calculateLookAt(eye, aim)
            return eye.add(direction(look.yaw + driftYaw, look.pitch + driftPitch).scale(distance))
        }

        private fun driftAmplitudeDegrees(client: Minecraft, target: Entity, aim: Vec3): Double {
            val strength = AetherConfig.PEST_AIM_DRIFT.get()
            if (strength <= 0.0f) return 0.0
            val distance = Math.max(0.5, aim.distanceTo(client.player?.eyePosition ?: return 0.0))
            val silhouette = Math.toDegrees(Math.atan2(target.bbWidth * 0.5, distance))
            val amplitude = silhouette * DRIFT_SILHOUETTE_FRACTION * strength
            return Math.min(MAX_DRIFT_DEGREES, Math.max(MIN_DRIFT_DEGREES, amplitude))
        }

        private fun advanceDrift(now: Long, amplitude: Double) {
            if (lastDriftAt == 0L) lastDriftAt = now
            if (now >= driftGoalUntil) {
                driftGoalUntil = now + ThreadLocalRandom.current().nextLong(DRIFT_HOLD_MIN_MS, DRIFT_HOLD_MAX_MS + 1)
                driftGoalYaw = ThreadLocalRandom.current().nextDouble(-amplitude, amplitude + 1.0e-9)
                driftGoalPitch = ThreadLocalRandom.current().nextDouble(-amplitude, amplitude + 1.0e-9)
            }
            val elapsed = Math.min(now - lastDriftAt, MAX_DRIFT_STEP_MS)
            lastDriftAt = now
            val closed = 1.0 - Math.exp(-elapsed / DRIFT_SMOOTHING_MS)
            driftYaw += (driftGoalYaw - driftYaw) * closed
            driftPitch += (driftGoalPitch - driftPitch) * closed
        }

        private fun direction(yaw: Double, pitch: Double): Vec3 {
            val yawRad = Math.toRadians(yaw)
            val pitchRad = Math.toRadians(pitch)
            val cosPitch = Math.cos(pitchRad)
            return Vec3(-Math.sin(yawRad) * cosPitch, -Math.sin(pitchRad), Math.cos(yawRad) * cosPitch)
        }
    }
}
