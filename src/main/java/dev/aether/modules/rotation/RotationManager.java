package dev.aether.modules.rotation;

import dev.aether.config.ConfigHelpers;
import dev.aether.config.AetherConfig;

import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.util.RotationUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ThreadLocalRandom;

public class RotationManager {
    private static final float EXTERNAL_ROTATION_TOLERANCE_DEGREES = 5.0f;

    private static boolean isRotating = false;
    private static RotationUtils.Rotation startRot;
    private static RotationUtils.Rotation targetRot;
    private static long rotationStartTime;
    private static long rotationDuration;
    private static boolean applyTrackingNoise = false;
    private static double rotationGcd = Double.NaN;
    private static float lastAppliedYaw = 0.0f;
    private static float lastAppliedPitch = 0.0f;
    private static boolean hasLastApplied = false;
    private static boolean smoothTrackingMode = false;
    private static float trackingYawVelocity = 0.0f;
    private static float trackingPitchVelocity = 0.0f;
    private static long trackingLastUpdateAt = 0L;

    public static boolean isRotating() {
        return isRotating;
    }

    public static void cancelRotation() {
        isRotating = false;
        startRot = null;
        targetRot = null;
        rotationStartTime = 0L;
        rotationDuration = 0L;
        applyTrackingNoise = false;
        rotationGcd = Double.NaN;
        hasLastApplied = false;
        resetTrackingMotion();
    }

    public static void initiateRotation(Minecraft mc, Vec3 targetPos, long minDuration) {
        initiateRotation(mc, targetPos, minDuration, 0f);
    }

    public static void initiateRotation(Minecraft mc, Vec3 targetPos, long minDuration, float humanizeRange) {
        if (mc.player == null)
            return;

        // Never interrupt a rotation that is already in progress.
        if (isRotating)
            return;

        if (FailsafeManager.shouldSuppressPestCleanerRotation(mc))
            return;

        startRot = new RotationUtils.Rotation(mc.player.getYRot(), mc.player.getXRot());
        RotationUtils.Rotation end = RotationUtils.calculateLookAt(mc.player.getEyePosition(), targetPos);
        
        if (humanizeRange > 0) {
            end = RotationUtils.applyImprecision(end, humanizeRange);
        }

        targetRot = RotationUtils.getAdjustedEnd(startRot, end);

        long configDuration = (long) ConfigHelpers.getRandomizedDelay(AetherConfig.ROTATION_TIME.get());
        long dynamicDuration = computeDynamicDuration(startRot, targetRot);
        rotationDuration = Math.max(150, Math.max(Math.max(configDuration, dynamicDuration), minDuration));
        rotationStartTime = System.currentTimeMillis();
        applyTrackingNoise = false;
        resetTrackingMotion();
        rotationGcd = computeGcd(mc);
        hasLastApplied = false;
        isRotating = true;
    }

    /** Start a pest-target rotation using the dedicated pest speed setting. */
    public static void initiatePestRotation(
            Minecraft mc, Vec3 targetPos, long minDuration, float humanizeRange) {
        if (mc.player == null || isRotating) return;
        if (FailsafeManager.shouldSuppressPestCleanerRotation(mc)) return;

        startRot = new RotationUtils.Rotation(mc.player.getYRot(), mc.player.getXRot());
        RotationUtils.Rotation end = RotationUtils.calculateLookAt(mc.player.getEyePosition(), targetPos);
        if (humanizeRange > 0) {
            end = RotationUtils.applyImprecision(end, humanizeRange);
        }
        targetRot = RotationUtils.getAdjustedEnd(startRot, end);

        float speed = pestRotationSpeed();
        long configDuration = Math.round(
                ConfigHelpers.getRandomizedDelay(AetherConfig.ROTATION_TIME.get()) / speed);
        long dynamicDuration = Math.round(computeDynamicDuration(startRot, targetRot) / speed);
        long scaledMinimum = Math.round(Math.max(150L, minDuration) / speed);
        rotationDuration = Math.max(45L,
                Math.max(scaledMinimum, Math.max(configDuration, dynamicDuration)));
        rotationStartTime = System.currentTimeMillis();
        applyTrackingNoise = false;
        resetTrackingMotion();
        rotationGcd = computeGcd(mc);
        hasLastApplied = false;
        isRotating = true;
    }

    /**
     * Rotate to a specific yaw and pitch over the given duration.
     * Does not interrupt a rotation that is already in progress unless
     * {@code force} is true.
     */
    public static void rotateToYawPitch(Minecraft mc, float yaw, float pitch, long durationMs) {
        rotateToYawPitch(mc, yaw, pitch, durationMs, false);
    }

    public static void rotateToYawPitch(Minecraft mc, float yaw, float pitch, long durationMs, boolean force) {
        if (mc.player == null) return;
        if (isRotating && !force) return;
        if (FailsafeManager.shouldSuppressPestCleanerRotation(mc)) return;
        startRot = new RotationUtils.Rotation(mc.player.getYRot(), mc.player.getXRot());
        targetRot = RotationUtils.getAdjustedEnd(startRot, new RotationUtils.Rotation(yaw, pitch));
        rotationDuration = Math.max(100, Math.max(durationMs, computeDynamicDuration(startRot, targetRot)));
        rotationStartTime = System.currentTimeMillis();
        applyTrackingNoise = false;
        resetTrackingMotion();
        rotationGcd = computeGcd(mc);
        hasLastApplied = false;
        isRotating = true;
    }

    /**
     * Like initiateRotation but always overrides the current rotation.
     * Used by pathfinding, which needs to update the target every tick.
     */
    public static void forceRotation(Minecraft mc, Vec3 targetPos, long durationMs) {
        if (mc.player == null) return;
        if (FailsafeManager.shouldSuppressPestCleanerRotation(mc)) return;
        startRot = new RotationUtils.Rotation(mc.player.getYRot(), mc.player.getXRot());
        targetRot = RotationUtils.calculateLookAt(mc.player.getEyePosition(), targetPos);
        targetRot = RotationUtils.getAdjustedEnd(startRot, targetRot);
        rotationDuration = Math.max(1, durationMs);
        rotationStartTime = System.currentTimeMillis();
        applyTrackingNoise = true;
        resetTrackingMotion();
        rotationGcd = computeGcd(mc);
        hasLastApplied = false;
        isRotating = true;
    }

    /** Retarget a moving entity with an eased, minimum-length turn. */
    public static void smoothForceRotation(Minecraft mc, Vec3 targetPos, long durationMs) {
        if (mc.player == null) return;
        if (FailsafeManager.shouldSuppressPestCleanerRotation(mc)) return;

        RotationUtils.Rotation current = new RotationUtils.Rotation(
                mc.player.getYRot(), mc.player.getXRot());
        RotationUtils.Rotation end = RotationUtils.calculateLookAt(mc.player.getEyePosition(), targetPos);
        targetRot = RotationUtils.getAdjustedEnd(current, end);

        // Retarget without restarting the turn. Preserving angular velocity is
        // what prevents a moving pest from producing a visible step every tick.
        if (!smoothTrackingMode) {
            startRot = current;
            trackingYawVelocity = 0.0f;
            trackingPitchVelocity = 0.0f;
            trackingLastUpdateAt = System.currentTimeMillis();
            hasLastApplied = false;
        }
        rotationDuration = Math.max(1L, durationMs);
        rotationStartTime = System.currentTimeMillis();
        applyTrackingNoise = false;
        smoothTrackingMode = true;
        if (Double.isNaN(rotationGcd)) {
            rotationGcd = computeGcd(mc);
        }
        isRotating = true;
    }

    private static float pestRotationSpeed() {
        return Mth.clamp(AetherConfig.PEST_ROTATION_SPEED.get(), 0.5f, 4.0f);
    }

    public static void update() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null)
            return;

        if (isRotating && startRot != null && targetRot != null) {
            if (hasExternalRotation(mc)) {
                FailsafeManager.reportExternalRotation();
                cancelRotation();
                return;
            }

            if (smoothTrackingMode) {
                updateSmoothTracking(mc);
                return;
            }

            long currentTime = System.currentTimeMillis();
            long elapsed = currentTime - rotationStartTime;
            float t = (float) elapsed / (float) rotationDuration;

            if (t >= 1.0f) {
                t = 1.0f;
                isRotating = false;
            }

            float easedT = applyEasing(t);

            float currentYaw = startRot.yaw + (targetRot.yaw - startRot.yaw) * easedT;
            float currentPitch = startRot.pitch + (targetRot.pitch - startRot.pitch) * easedT;

            if (applyTrackingNoise) {
                float noiseMin = AetherConfig.ROTATION_TRACKING_NOISE_MIN.get();
                float noiseMax = AetherConfig.ROTATION_TRACKING_NOISE_MAX.get();
                if (noiseMax < noiseMin) {
                    float swap = noiseMin;
                    noiseMin = noiseMax;
                    noiseMax = swap;
                }

                if (noiseMax > 0.0f) {
                    float noiseScale = ThreadLocalRandom.current().nextFloat(noiseMin, noiseMax + 0.0001f) / 100.0f;
                    float yawFactor = 1.0f + ThreadLocalRandom.current().nextFloat(-noiseScale, noiseScale);
                    float pitchFactor = 1.0f + ThreadLocalRandom.current().nextFloat(-noiseScale, noiseScale);
                    currentYaw = mc.player.getYRot() + (currentYaw - mc.player.getYRot()) * yawFactor;
                    currentPitch = mc.player.getXRot() + (currentPitch - mc.player.getXRot()) * pitchFactor;
                }
            }

            currentPitch = Mth.clamp(currentPitch, -90.0f, 90.0f);
            currentYaw = applyGcd(currentYaw, mc.player.getYRot());
            currentPitch = applyGcd(currentPitch, mc.player.getXRot(), -90.0f, 90.0f);

            mc.player.setYRot(currentYaw);
            mc.player.setXRot(currentPitch);
            mc.player.yRotO = currentYaw;
            mc.player.xRotO = currentPitch;
            lastAppliedYaw = currentYaw;
            lastAppliedPitch = currentPitch;
            hasLastApplied = true;
            FailsafeManager.expectRotation(currentYaw, currentPitch);
        }
    }

    /**
     * Acceleration-limited pest tracking. It behaves like a hand moving a
     * mouse: accelerate into a large turn, carry momentum while the target
     * moves, then brake before reaching the target.
     */
    private static void updateSmoothTracking(Minecraft mc) {
        long now = System.currentTimeMillis();
        float deltaSeconds = trackingLastUpdateAt == 0L
                ? 0.05f
                : Mth.clamp((now - trackingLastUpdateAt) / 1000.0f, 0.005f, 0.05f);
        trackingLastUpdateAt = now;

        float currentYaw = mc.player.getYRot();
        float currentPitch = mc.player.getXRot();
        float yawError = Mth.wrapDegrees(targetRot.yaw - currentYaw);
        float pitchError = targetRot.pitch - currentPitch;
        float speed = pestRotationSpeed();

        float maxAngularSpeed = 120.0f * speed;
        float acceleration = 720.0f * speed;
        float response = 9.0f;
        float desiredYawVelocity = Mth.clamp(
                yawError * response, -maxAngularSpeed, maxAngularSpeed);
        float desiredPitchVelocity = Mth.clamp(
                pitchError * response, -maxAngularSpeed, maxAngularSpeed);

        trackingYawVelocity = approach(
                trackingYawVelocity, desiredYawVelocity, acceleration * deltaSeconds);
        trackingPitchVelocity = approach(
                trackingPitchVelocity, desiredPitchVelocity, acceleration * deltaSeconds);

        float yawStep = trackingYawVelocity * deltaSeconds;
        float pitchStep = trackingPitchVelocity * deltaSeconds;
        if (Math.abs(yawStep) > Math.abs(yawError)) {
            yawStep = yawError;
            trackingYawVelocity = 0.0f;
        }
        if (Math.abs(pitchStep) > Math.abs(pitchError)) {
            pitchStep = pitchError;
            trackingPitchVelocity = 0.0f;
        }

        float nextYaw = applyGcd(currentYaw + yawStep, currentYaw);
        float nextPitch = applyGcd(
                Mth.clamp(currentPitch + pitchStep, -90.0f, 90.0f),
                currentPitch,
                -90.0f,
                90.0f);

        mc.player.setYRot(nextYaw);
        mc.player.setXRot(nextPitch);
        mc.player.yRotO = nextYaw;
        mc.player.xRotO = nextPitch;
        lastAppliedYaw = nextYaw;
        lastAppliedPitch = nextPitch;
        hasLastApplied = true;
        FailsafeManager.expectRotation(nextYaw, nextPitch);

        double gcd = Double.isNaN(rotationGcd) ? computeGcd(mc) : rotationGcd;
        float finishTolerance = (float) Math.max(0.35, gcd);
        if (Math.abs(yawError) <= finishTolerance
                && Math.abs(pitchError) <= finishTolerance
                && Math.abs(trackingYawVelocity) < 4.0f
                && Math.abs(trackingPitchVelocity) < 4.0f) {
            isRotating = false;
            resetTrackingMotion();
        }
    }

    private static float approach(float current, float target, float maxDelta) {
        if (current < target) {
            return Math.min(current + maxDelta, target);
        }
        return Math.max(current - maxDelta, target);
    }

    private static void resetTrackingMotion() {
        smoothTrackingMode = false;
        trackingYawVelocity = 0.0f;
        trackingPitchVelocity = 0.0f;
        trackingLastUpdateAt = 0L;
    }

    private static boolean hasExternalRotation(Minecraft mc) {
        if (!hasLastApplied) {
            return false;
        }

        // Pathfinding rotates through its own executor. Measuring drift against only
        // our own last write makes the two systems report each other as external.
        float baseYaw = lastAppliedYaw;
        float basePitch = lastAppliedPitch;
        if (FailsafeManager.isExpectedRotationSet()) {
            baseYaw = FailsafeManager.getExpectedYaw();
            basePitch = FailsafeManager.getExpectedPitch();
        }
        float yawDrift = Math.abs(Mth.wrapDegrees(mc.player.getYRot() - baseYaw));
        float pitchDrift = Math.abs(mc.player.getXRot() - basePitch);
        return yawDrift > EXTERNAL_ROTATION_TOLERANCE_DEGREES
                || pitchDrift > EXTERNAL_ROTATION_TOLERANCE_DEGREES;
    }

    /**
     * Applies the configured ease-in / ease-out curve to a linear [0,1] progress value.
     *
     * <ul>
     *   <li>Linear only  -> t unchanged</li>
     *   <li>Ease-in only -> t^factor  (starts slow, ends fast)</li>
     *   <li>Ease-out only -> 1-(1-t)^factor  (starts fast, ends slow)</li>
     *   <li>Both          -> first half uses ease-in, second half uses ease-out,
     *                       joined seamlessly at t=0.5 (classic "ease" S-curve)</li>
     * </ul>
     */
    private static float applyEasing(float t) {
        boolean easeIn  = AetherConfig.ROTATION_EASE_IN.get();
        boolean easeOut = AetherConfig.ROTATION_EASE_OUT.get();

        if (!easeIn && !easeOut) return t;

        float inFactor  = AetherConfig.ROTATION_EASE_IN_FACTOR.get();
        float outFactor = AetherConfig.ROTATION_EASE_OUT_FACTOR.get();

        if (easeIn && easeOut) {
            // Split at 0.5: ease-in governs [0, 0.5), ease-out governs [0.5, 1].
            if (t < 0.5f) {
                // Map [0,0.5) -> [0,1), apply ease-in, then map back to [0,0.5)
                float tMapped = t * 2f;
                return (float) Math.pow(tMapped, inFactor) * 0.5f;
            } else {
                // Map [0.5,1] -> [0,1], apply ease-out, then map back to [0.5,1]
                float tMapped = (t - 0.5f) * 2f;
                return (float)(1.0 - Math.pow(1.0 - tMapped, outFactor)) * 0.5f + 0.5f;
            }
        } else if (easeIn) {
            return (float) Math.pow(t, inFactor);
        } else { // easeOut only
            return (float)(1.0 - Math.pow(1.0 - t, outFactor));
        }
    }

    private static long computeDynamicDuration(RotationUtils.Rotation start, RotationUtils.Rotation end) {
        float msPerDegree = AetherConfig.ROTATION_DYNAMIC_DURATION_MS_PER_DEGREE.get();
        if (msPerDegree <= 0.0f) {
            return 0L;
        }

        float yawDiff = Math.abs(Mth.wrapDegrees(end.yaw - start.yaw));
        float pitchDiff = Math.abs(end.pitch - start.pitch);
        float angularDistance = Math.max(yawDiff, pitchDiff);
        return Math.round(angularDistance * msPerDegree);
    }

    private static float applyGcd(float rotation, float previousRotation) {
        return applyGcd(rotation, previousRotation, null, null);
    }

    private static float applyGcd(float rotation, float previousRotation, Float min, Float max) {
        double gcd = Double.isNaN(rotationGcd) ? computeGcd(Minecraft.getInstance()) : rotationGcd;
        double delta = Mth.wrapDegrees(rotation - previousRotation);
        double roundedDelta = Math.round(delta / gcd) * gcd;
        float result = (float) (previousRotation + roundedDelta);

        if (max != null && result > max) {
            result -= (float) gcd;
        }
        if (min != null && result < min) {
            result += (float) gcd;
        }

        return result;
    }

    private static double computeGcd(Minecraft mc) {
        double sensitivity = mc.options.sensitivity().get();
        double multiplier = sensitivity * 0.6 + 0.2;
        return multiplier * multiplier * multiplier * 1.2;
    }
}
