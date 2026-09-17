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
    // A long frame gap (lag spike, alt-tab) must not hand a rotation a whole
    // turn's worth of travel in a single step.
    private static final long MAX_TURN_STEP_MS = 60L;
    private static final float MIN_TRACKING_SMOOTHING_MS = 1.0f;
    private static final float TRACKING_ARRIVAL_DEGREES = 0.25f;

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
    private static float maxDegreesPerSecond = 0.0f;
    private static long lastUpdateAt = 0L;
    private static boolean trackingMode = false;
    private static float trackingSmoothingMs = 0.0f;

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
        maxDegreesPerSecond = 0.0f;
        trackingMode = false;
    }

    public static void initiateRotation(Minecraft mc, Vec3 targetPos, long minDuration) {
        initiateRotation(mc, targetPos, minDuration, 0f);
    }

    public static void initiateRotation(Minecraft mc, Vec3 targetPos, long minDuration, float humanizeRange) {
        initiateRotation(mc, targetPos, minDuration, humanizeRange, 0.0f);
    }

    // turnSpeedLimit caps degrees per second whatever the duration works out to; 0 leaves it uncapped
    public static void initiateRotation(
            Minecraft mc, Vec3 targetPos, long minDuration, float humanizeRange, float turnSpeedLimit) {
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
        rotationGcd = computeGcd(mc);
        hasLastApplied = false;
        maxDegreesPerSecond = Math.max(0.0f, turnSpeedLimit);
        trackingMode = false;
        isRotating = true;
    }

    // does not interrupt a rotation already in progress unless force is set
    public static void rotateToYawPitch(Minecraft mc, float yaw, float pitch, long durationMs) {
        rotateToYawPitch(mc, yaw, pitch, durationMs, false);
    }

    public static void rotateToYawPitch(Minecraft mc, float yaw, float pitch, long durationMs, boolean force) {
        rotateToYawPitch(mc, yaw, pitch, durationMs, force, true);
    }

    public static void rotateToExactYawPitch(Minecraft mc, float yaw, float pitch, long durationMs) {
        rotateToYawPitch(mc, yaw, pitch, durationMs, false, false);
    }

    private static void rotateToYawPitch(Minecraft mc, float yaw, float pitch, long durationMs,
                                         boolean force, boolean quantize) {
        if (mc.player == null) return;
        if (isRotating && !force) return;
        if (FailsafeManager.shouldSuppressPestCleanerRotation(mc)) return;
        startRot = new RotationUtils.Rotation(mc.player.getYRot(), mc.player.getXRot());
        targetRot = RotationUtils.getAdjustedEnd(startRot, new RotationUtils.Rotation(yaw, pitch));
        rotationDuration = Math.max(100, Math.max(durationMs, computeDynamicDuration(startRot, targetRot)));
        rotationStartTime = System.currentTimeMillis();
        applyTrackingNoise = false;
        rotationGcd = quantize ? computeGcd(mc) : 0.0;
        hasLastApplied = false;
        maxDegreesPerSecond = 0.0f;
        trackingMode = false;
        isRotating = true;
    }

    // always overrides the current rotation, for pathfinding, which retargets every tick
    public static void forceRotation(Minecraft mc, Vec3 targetPos, long durationMs) {
        if (mc.player == null) return;
        if (FailsafeManager.shouldSuppressPestCleanerRotation(mc)) return;
        startRot = new RotationUtils.Rotation(mc.player.getYRot(), mc.player.getXRot());
        targetRot = RotationUtils.calculateLookAt(mc.player.getEyePosition(), targetPos);
        targetRot = RotationUtils.getAdjustedEnd(startRot, targetRot);
        rotationDuration = Math.max(1, durationMs);
        rotationStartTime = System.currentTimeMillis();
        applyTrackingNoise = true;
        rotationGcd = computeGcd(mc);
        hasLastApplied = false;
        maxDegreesPerSecond = 0.0f;
        trackingMode = false;
        isRotating = true;
    }

    // closes a fraction of whatever angle is left each update, so the camera leads in, decelerates and settles
    // re-issuing a duration-based rotation every tick instead replays the eased curve from scratch and lands the whole correction in one tick, which is what reads as an aimbot
    public static void trackRotation(
            Minecraft mc, Vec3 targetPos, float smoothingMs, float turnSpeedLimit) {
        if (mc.player == null) return;
        if (FailsafeManager.shouldSuppressPestCleanerRotation(mc)) return;
        startRot = new RotationUtils.Rotation(mc.player.getYRot(), mc.player.getXRot());
        targetRot = RotationUtils.getAdjustedEnd(
                startRot, RotationUtils.calculateLookAt(mc.player.getEyePosition(), targetPos));
        rotationStartTime = System.currentTimeMillis();
        rotationDuration = 1L;
        applyTrackingNoise = true;
        rotationGcd = computeGcd(mc);
        hasLastApplied = false;
        maxDegreesPerSecond = Math.max(0.0f, turnSpeedLimit);
        trackingSmoothingMs = Math.max(MIN_TRACKING_SMOOTHING_MS, smoothingMs);
        trackingMode = true;
        isRotating = true;
    }

    public static void update() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null)
            return;

        long updateAt = System.currentTimeMillis();
        long sinceLastUpdate = lastUpdateAt == 0L ? 0L : updateAt - lastUpdateAt;
        lastUpdateAt = updateAt;

        if (isRotating && startRot != null && targetRot != null) {
            if (hasExternalRotation(mc)) {
                FailsafeManager.reportExternalRotation();
                cancelRotation();
                return;
            }

            long stepMs = Math.min(sinceLastUpdate, MAX_TURN_STEP_MS);
            float currentYaw;
            float currentPitch;

            if (trackingMode) {
                float remainingYaw = Mth.wrapDegrees(targetRot.yaw - mc.player.getYRot());
                float remainingPitch = targetRot.pitch - mc.player.getXRot();
                if (Math.abs(remainingYaw) < TRACKING_ARRIVAL_DEGREES
                        && Math.abs(remainingPitch) < TRACKING_ARRIVAL_DEGREES) {
                    isRotating = false;
                }
                float closed = 1.0f - (float) Math.exp(-stepMs / trackingSmoothingMs);
                currentYaw = mc.player.getYRot() + remainingYaw * closed;
                currentPitch = mc.player.getXRot() + remainingPitch * closed;
            } else {
                long elapsed = updateAt - rotationStartTime;
                float t = (float) elapsed / (float) rotationDuration;

                if (t >= 1.0f) {
                    t = 1.0f;
                    isRotating = false;
                }

                float easedT = applyEasing(t);

                currentYaw = startRot.yaw + (targetRot.yaw - startRot.yaw) * easedT;
                currentPitch = startRot.pitch + (targetRot.pitch - startRot.pitch) * easedT;
            }

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

            if (maxDegreesPerSecond > 0.0f) {
                float budget = maxDegreesPerSecond * stepMs / 1000.0f;
                float yawStep = Mth.wrapDegrees(currentYaw - mc.player.getYRot());
                float pitchStep = currentPitch - mc.player.getXRot();
                float step = (float) Math.sqrt(yawStep * yawStep + pitchStep * pitchStep);
                if (step > budget) {
                    float scale = budget / step;
                    currentYaw = mc.player.getYRot() + yawStep * scale;
                    currentPitch = Mth.clamp(mc.player.getXRot() + pitchStep * scale, -90.0f, 90.0f);
                    // The curve is done but the camera is not there yet;
                    // ending here would leave it short of the target.
                    isRotating = true;
                }
            }

            currentYaw = applyGcd(currentYaw, mc.player.getYRot());
            currentPitch = applyGcd(currentPitch, mc.player.getXRot(), -90.0f, 90.0f);

            // A tracking rotation closes a fraction of what is left, so the last
            // fraction of a degree rounds to nothing on the mouse GCD and the
            // rotation never reports finished. Every isRotating() wait in the mod
            // hangs on that, so treat a step the GCD cannot express as arrival.
            if (trackingMode && stepMs > 0
                    && currentYaw == mc.player.getYRot()
                    && currentPitch == mc.player.getXRot()) {
                isRotating = false;
            }

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

    private static boolean hasExternalRotation(Minecraft mc) {
        if (!hasLastApplied) {
            return false;
        }

        float yawDrift = Math.abs(Mth.wrapDegrees(mc.player.getYRot() - lastAppliedYaw));
        float pitchDrift = Math.abs(mc.player.getXRot() - lastAppliedPitch);
        return yawDrift > EXTERNAL_ROTATION_TOLERANCE_DEGREES
                || pitchDrift > EXTERNAL_ROTATION_TOLERANCE_DEGREES;
    }

    // ease-in is t^factor, ease-out is 1-(1-t)^factor, and both joins them at t=0.5
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
        if (rotationGcd == 0.0) {
            return rotation;
        }
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
