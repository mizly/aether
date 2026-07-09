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
    private static boolean isRotating = false;
    private static RotationUtils.Rotation startRot;
    private static RotationUtils.Rotation targetRot;
    private static long rotationStartTime;
    private static long rotationDuration;
    private static boolean applyTrackingNoise = false;
    private static double rotationGcd = Double.NaN;

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

        startRot = new RotationUtils.Rotation(mc.player.getYRot(), mc.player.getXRot());
        RotationUtils.Rotation end = RotationUtils.calculateLookAt(mc.player.getEyePosition(), targetPos);
        
        if (humanizeRange > 0) {
            end = RotationUtils.applyImprecision(end, humanizeRange);
        }

        targetRot = RotationUtils.getAdjustedEnd(startRot, end);
        FailsafeManager.expectRotation(targetRot.yaw, targetRot.pitch);

        long configDuration = (long) ConfigHelpers.getRandomizedDelay(AetherConfig.ROTATION_TIME.get());
        long dynamicDuration = computeDynamicDuration(startRot, targetRot);
        rotationDuration = Math.max(150, Math.max(Math.max(configDuration, dynamicDuration), minDuration));
        rotationStartTime = System.currentTimeMillis();
        applyTrackingNoise = false;
        rotationGcd = computeGcd(mc);
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
        startRot = new RotationUtils.Rotation(mc.player.getYRot(), mc.player.getXRot());
        targetRot = RotationUtils.getAdjustedEnd(startRot, new RotationUtils.Rotation(yaw, pitch));
        FailsafeManager.expectRotation(targetRot.yaw, targetRot.pitch);
        rotationDuration = Math.max(100, Math.max(durationMs, computeDynamicDuration(startRot, targetRot)));
        rotationStartTime = System.currentTimeMillis();
        applyTrackingNoise = false;
        rotationGcd = computeGcd(mc);
        isRotating = true;
    }

    /**
     * Like initiateRotation but always overrides the current rotation.
     * Used by pathfinding, which needs to update the target every tick.
     */
    public static void forceRotation(Minecraft mc, Vec3 targetPos, long durationMs) {
        if (mc.player == null) return;
        startRot = new RotationUtils.Rotation(mc.player.getYRot(), mc.player.getXRot());
        targetRot = RotationUtils.calculateLookAt(mc.player.getEyePosition(), targetPos);
        targetRot = RotationUtils.getAdjustedEnd(startRot, targetRot);
        FailsafeManager.expectRotation(targetRot.yaw, targetRot.pitch);
        rotationDuration = Math.max(1, durationMs);
        rotationStartTime = System.currentTimeMillis();
        applyTrackingNoise = true;
        rotationGcd = computeGcd(mc);
        isRotating = true;
    }

    public static void update(Minecraft mc) {
        if (mc.player == null)
            return;

        if (isRotating && startRot != null && targetRot != null) {
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
            FailsafeManager.expectRotation(currentYaw, currentPitch);
        }
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
