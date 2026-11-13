package dev.aether.modules.rotation

import dev.aether.config.AetherConfig
import dev.aether.config.ConfigHelpers
import dev.aether.modules.failsafe.FailsafeManager
import dev.aether.util.RotationUtils
import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ThreadLocalRandom

class RotationManager private constructor() {
    companion object {
        private const val EXTERNAL_ROTATION_TOLERANCE_DEGREES = 5.0f
        // A long frame gap (lag spike, alt-tab) must not hand a rotation a whole
        // turn's worth of travel in a single step.
        private const val MAX_TURN_STEP_MS = 60L
        private const val MIN_TRACKING_SMOOTHING_MS = 1.0f
        private const val TRACKING_ARRIVAL_DEGREES = 0.25f

        private var isRotating = false
        private var startRot: RotationUtils.Rotation? = null
        private var targetRot: RotationUtils.Rotation? = null
        private var rotationStartTime = 0L
        private var rotationDuration = 0L
        private var applyTrackingNoise = false
        private var rotationGcd = Double.NaN
        private var lastAppliedYaw = 0.0f
        private var lastAppliedPitch = 0.0f
        private var hasLastApplied = false
        private var maxDegreesPerSecond = 0.0f
        private var lastUpdateAt = 0L
        private var trackingMode = false
        private var trackingSmoothingMs = 0.0f

        @JvmStatic
        fun isRotating(): Boolean = isRotating

        @JvmStatic
        fun cancelRotation() {
            isRotating = false
            startRot = null
            targetRot = null
            rotationStartTime = 0L
            rotationDuration = 0L
            applyTrackingNoise = false
            rotationGcd = Double.NaN
            hasLastApplied = false
            maxDegreesPerSecond = 0.0f
            trackingMode = false
        }

        @JvmStatic
        fun initiateRotation(mc: Minecraft, targetPos: Vec3, minDuration: Long) {
            initiateRotation(mc, targetPos, minDuration, 0f)
        }

        @JvmStatic
        fun initiateRotation(mc: Minecraft, targetPos: Vec3, minDuration: Long, humanizeRange: Float) {
            initiateRotation(mc, targetPos, minDuration, humanizeRange, 0.0f)
        }

        // turnSpeedLimit caps degrees per second whatever the duration works out to; 0 leaves it uncapped
        @JvmStatic
        fun initiateRotation(
            mc: Minecraft, targetPos: Vec3, minDuration: Long, humanizeRange: Float, turnSpeedLimit: Float
        ) {
            if (mc.player == null) return

            // Never interrupt a rotation that is already in progress.
            if (isRotating) return

            if (FailsafeManager.shouldSuppressPestCleanerRotation(mc)) return

            val p = mc.player ?: return
            val activeStart = RotationUtils.Rotation(p.yRot, p.xRot)
            startRot = activeStart
            var end = RotationUtils.calculateLookAt(p.eyePosition, targetPos)

            if (humanizeRange > 0) {
                end = RotationUtils.applyImprecision(end, humanizeRange)
            }

            val newTarget = RotationUtils.getAdjustedEnd(activeStart, end)
            targetRot = newTarget

            val configDuration = ConfigHelpers.getRandomizedDelay(AetherConfig.ROTATION_TIME.get()).toLong()
            val dynamicDuration = computeDynamicDuration(activeStart, newTarget)
            rotationDuration = Math.max(150L, Math.max(Math.max(configDuration, dynamicDuration), minDuration))
            rotationStartTime = System.currentTimeMillis()
            applyTrackingNoise = false
            rotationGcd = computeGcd(mc)
            hasLastApplied = false
            maxDegreesPerSecond = Math.max(0.0f, turnSpeedLimit)
            trackingMode = false
            isRotating = true
        }

        // does not interrupt a rotation already in progress unless force is set
        @JvmStatic
        fun rotateToYawPitch(mc: Minecraft, yaw: Float, pitch: Float, durationMs: Long) {
            rotateToYawPitch(mc, yaw, pitch, durationMs, false)
        }

        @JvmStatic
        fun rotateToYawPitch(mc: Minecraft, yaw: Float, pitch: Float, durationMs: Long, force: Boolean) {
            rotateToYawPitch(mc, yaw, pitch, durationMs, force, true)
        }

        @JvmStatic
        fun rotateToExactYawPitch(mc: Minecraft, yaw: Float, pitch: Float, durationMs: Long) {
            rotateToYawPitch(mc, yaw, pitch, durationMs, false, false)
        }

        private fun rotateToYawPitch(
            mc: Minecraft, yaw: Float, pitch: Float, durationMs: Long, force: Boolean, quantize: Boolean
        ) {
            if (mc.player == null) return
            if (isRotating && !force) return
            if (FailsafeManager.shouldSuppressPestCleanerRotation(mc)) return
            val p = mc.player ?: return
            val newStart = RotationUtils.Rotation(p.yRot, p.xRot)
            startRot = newStart
            val newTarget = RotationUtils.getAdjustedEnd(newStart, RotationUtils.Rotation(yaw, pitch))
            targetRot = newTarget
            rotationDuration = Math.max(100L, Math.max(durationMs, computeDynamicDuration(newStart, newTarget)))
            rotationStartTime = System.currentTimeMillis()
            applyTrackingNoise = false
            rotationGcd = if (quantize) computeGcd(mc) else 0.0
            hasLastApplied = false
            maxDegreesPerSecond = 0.0f
            trackingMode = false
            isRotating = true
        }

        // always overrides the current rotation, for pathfinding, which retargets every tick
        @JvmStatic
        fun forceRotation(mc: Minecraft, targetPos: Vec3, durationMs: Long) {
            if (mc.player == null) return
            if (FailsafeManager.shouldSuppressPestCleanerRotation(mc)) return
            val p = mc.player ?: return
            val newStart = RotationUtils.Rotation(p.yRot, p.xRot)
            startRot = newStart
            val looked = RotationUtils.calculateLookAt(p.eyePosition, targetPos)
            val newTarget = RotationUtils.getAdjustedEnd(newStart, looked)
            targetRot = newTarget
            rotationDuration = Math.max(1L, durationMs)
            rotationStartTime = System.currentTimeMillis()
            applyTrackingNoise = true
            rotationGcd = computeGcd(mc)
            hasLastApplied = false
            maxDegreesPerSecond = 0.0f
            trackingMode = false
            isRotating = true
        }

        // closes a fraction of whatever angle is left each update, so the camera leads in, decelerates and settles
        // re-issuing a duration-based rotation every tick instead replays the eased curve from scratch and lands the whole correction in one tick, which is what reads as an aimbot
        @JvmStatic
        fun trackRotation(mc: Minecraft, targetPos: Vec3, smoothingMs: Float, turnSpeedLimit: Float) {
            if (mc.player == null) return
            if (FailsafeManager.shouldSuppressPestCleanerRotation(mc)) return
            val p = mc.player ?: return
            val newStart = RotationUtils.Rotation(p.yRot, p.xRot)
            startRot = newStart
            val newTarget = RotationUtils.getAdjustedEnd(
                newStart, RotationUtils.calculateLookAt(p.eyePosition, targetPos)
            )
            targetRot = newTarget
            rotationStartTime = System.currentTimeMillis()
            rotationDuration = 1L
            applyTrackingNoise = true
            rotationGcd = computeGcd(mc)
            hasLastApplied = false
            maxDegreesPerSecond = Math.max(0.0f, turnSpeedLimit)
            trackingSmoothingMs = Math.max(MIN_TRACKING_SMOOTHING_MS, smoothingMs)
            trackingMode = true
            isRotating = true
        }

        @JvmStatic
        fun update() {
            val mc = Minecraft.getInstance()
            if (mc.player == null) return

            val updateAt = System.currentTimeMillis()
            val sinceLastUpdate = if (lastUpdateAt == 0L) 0L else updateAt - lastUpdateAt
            lastUpdateAt = updateAt

            val activeStart = startRot
            val activeTarget = targetRot
            if (isRotating && activeStart != null && activeTarget != null) {
                val p = mc.player ?: return
                if (hasExternalRotation(mc)) {
                    FailsafeManager.reportExternalRotation()
                    cancelRotation()
                    return
                }

                val stepMs = Math.min(sinceLastUpdate, MAX_TURN_STEP_MS)
                var currentYaw: Float
                var currentPitch: Float

                if (trackingMode) {
                    val remainingYaw = Mth.wrapDegrees(activeTarget.yaw - p.yRot)
                    val remainingPitch = activeTarget.pitch - p.xRot
                    if (Math.abs(remainingYaw) < TRACKING_ARRIVAL_DEGREES
                        && Math.abs(remainingPitch) < TRACKING_ARRIVAL_DEGREES
                    ) {
                        isRotating = false
                    }
                    val closed = 1.0f - Math.exp(-(stepMs.toDouble() / trackingSmoothingMs)).toFloat()
                    currentYaw = p.yRot + remainingYaw * closed
                    currentPitch = p.xRot + remainingPitch * closed
                } else {
                    val elapsed = updateAt - rotationStartTime
                    var t = elapsed.toFloat() / rotationDuration.toFloat()

                    if (t >= 1.0f) {
                        t = 1.0f
                        isRotating = false
                    }

                    val easedT = applyEasing(t)
                    currentYaw = activeStart.yaw + (activeTarget.yaw - activeStart.yaw) * easedT
                    currentPitch = activeStart.pitch + (activeTarget.pitch - activeStart.pitch) * easedT
                }

                if (applyTrackingNoise) {
                    var noiseMin = AetherConfig.ROTATION_TRACKING_NOISE_MIN.get()
                    var noiseMax = AetherConfig.ROTATION_TRACKING_NOISE_MAX.get()
                    if (noiseMax < noiseMin) {
                        val swap = noiseMin
                        noiseMin = noiseMax
                        noiseMax = swap
                    }

                    if (noiseMax > 0.0f) {
                        val noiseScale = ThreadLocalRandom.current().nextFloat(noiseMin, noiseMax + 0.0001f) / 100.0f
                        val yawFactor = 1.0f + ThreadLocalRandom.current().nextFloat(-noiseScale, noiseScale)
                        val pitchFactor = 1.0f + ThreadLocalRandom.current().nextFloat(-noiseScale, noiseScale)
                        currentYaw = p.yRot + (currentYaw - p.yRot) * yawFactor
                        currentPitch = p.xRot + (currentPitch - p.xRot) * pitchFactor
                    }
                }

                currentPitch = Mth.clamp(currentPitch, -90.0f, 90.0f)

                if (maxDegreesPerSecond > 0.0f) {
                    val budget = maxDegreesPerSecond * stepMs / 1000.0f
                    val yawStep = Mth.wrapDegrees(currentYaw - p.yRot)
                    val pitchStep = currentPitch - p.xRot
                    val step = Math.sqrt((yawStep * yawStep + pitchStep * pitchStep).toDouble()).toFloat()
                    if (step > budget) {
                        val scale = budget / step
                        currentYaw = p.yRot + yawStep * scale
                        currentPitch = Mth.clamp(p.xRot + pitchStep * scale, -90.0f, 90.0f)
                        // The curve is done but the camera is not there yet;
                        // ending here would leave it short of the target.
                        isRotating = true
                    }
                }

                currentYaw = applyGcd(currentYaw, p.yRot)
                currentPitch = applyGcd(currentPitch, p.xRot, -90.0f, 90.0f)

                // A tracking rotation closes a fraction of what is left, so the last
                // fraction of a degree rounds to nothing on the mouse GCD and the
                // rotation never reports finished. Every isRotating() wait in the mod
                // hangs on that, so treat a step the GCD cannot express as arrival.
                if (trackingMode && stepMs > 0
                    && currentYaw == p.yRot
                    && currentPitch == p.xRot
                ) {
                    isRotating = false
                }

                p.yRot = currentYaw
                p.xRot = currentPitch
                p.yRotO = currentYaw
                p.xRotO = currentPitch
                lastAppliedYaw = currentYaw
                lastAppliedPitch = currentPitch
                hasLastApplied = true
                FailsafeManager.expectRotation(currentYaw, currentPitch)
            }
        }

        private fun hasExternalRotation(mc: Minecraft): Boolean {
            if (!hasLastApplied) {
                return false
            }

            val p = mc.player ?: return false
            val yawDrift = Math.abs(Mth.wrapDegrees(p.yRot - lastAppliedYaw))
            val pitchDrift = Math.abs(p.xRot - lastAppliedPitch)
            return yawDrift > EXTERNAL_ROTATION_TOLERANCE_DEGREES
                || pitchDrift > EXTERNAL_ROTATION_TOLERANCE_DEGREES
        }

        // ease-in is t^factor, ease-out is 1-(1-t)^factor, and both joins them at t=0.5
        private fun applyEasing(t: Float): Float {
            val easeIn = AetherConfig.ROTATION_EASE_IN.get()
            val easeOut = AetherConfig.ROTATION_EASE_OUT.get()

            if (!easeIn && !easeOut) return t

            val inFactor = AetherConfig.ROTATION_EASE_IN_FACTOR.get()
            val outFactor = AetherConfig.ROTATION_EASE_OUT_FACTOR.get()

            if (easeIn && easeOut) {
                // Split at 0.5: ease-in governs [0, 0.5), ease-out governs [0.5, 1].
                if (t < 0.5f) {
                    // Map [0,0.5) -> [0,1), apply ease-in, then map back to [0,0.5)
                    val tMapped = t * 2f
                    return Math.pow(tMapped.toDouble(), inFactor.toDouble()).toFloat() * 0.5f
                } else {
                    // Map [0.5,1] -> [0,1], apply ease-out, then map back to [0.5,1]
                    val tMapped = (t - 0.5f) * 2f
                    return (1.0 - Math.pow(1.0 - tMapped, outFactor.toDouble())).toFloat() * 0.5f + 0.5f
                }
            } else if (easeIn) {
                return Math.pow(t.toDouble(), inFactor.toDouble()).toFloat()
            } else { // easeOut only
                return (1.0 - Math.pow(1.0 - t, outFactor.toDouble())).toFloat()
            }
        }

        private fun computeDynamicDuration(start: RotationUtils.Rotation, end: RotationUtils.Rotation): Long {
            val msPerDegree = AetherConfig.ROTATION_DYNAMIC_DURATION_MS_PER_DEGREE.get()
            if (msPerDegree <= 0.0f) {
                return 0L
            }

            val yawDiff = Math.abs(Mth.wrapDegrees(end.yaw - start.yaw))
            val pitchDiff = Math.abs(end.pitch - start.pitch)
            val angularDistance = Math.max(yawDiff, pitchDiff)
            return Math.round(angularDistance * msPerDegree).toLong()
        }

        private fun applyGcd(rotation: Float, previousRotation: Float): Float {
            return applyGcd(rotation, previousRotation, null, null)
        }

        private fun applyGcd(rotation: Float, previousRotation: Float, min: Float?, max: Float?): Float {
            if (rotationGcd == 0.0) {
                return rotation
            }
            val gcd = if (java.lang.Double.isNaN(rotationGcd)) computeGcd(Minecraft.getInstance()) else rotationGcd
            val delta = Mth.wrapDegrees(rotation - previousRotation)
            val roundedDelta = Math.round(delta / gcd) * gcd
            var result = (previousRotation + roundedDelta).toFloat()

            if (max != null && result > max) {
                result -= gcd.toFloat()
            }
            if (min != null && result < min) {
                result += gcd.toFloat()
            }

            return result
        }

        private fun computeGcd(mc: Minecraft): Double {
            val sensitivity = mc.options.sensitivity().get()
            val multiplier = sensitivity * 0.6 + 0.2
            return multiplier * multiplier * multiplier * 1.2
        }
    }
}
