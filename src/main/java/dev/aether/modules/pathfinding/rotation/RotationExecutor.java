package dev.aether.modules.pathfinding.rotation;

import dev.aether.modules.failsafe.FailsafeManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Handles smooth player rotations with GCD (game cursor distance) simulation.
 * Call {@link #update(Minecraft)} every client tick.
 */
public final class RotationExecutor {

    private static final Minecraft mc = Minecraft.getInstance();

    private static final float EXTERNAL_ROTATION_TOLERANCE_DEGREES = 5.0f;

    private static float             targetYaw;
    private static float             targetPitch;
    private static IRotationStrategy currStrat;
    private static boolean           isRotating = false;
    /** GCD cached per navigation start - recomputed in rotateTo() when strategy changes. */
    private static double            cachedGcd  = Double.NaN;
    private static float             lastAppliedYaw;
    private static float             lastAppliedPitch;
    private static boolean           hasLastApplied = false;

    private RotationExecutor() {}

    public static void rotateTo(Rotation endRot, IRotationStrategy strategy) {
        if (FailsafeManager.shouldSuppressPestCleanerRotation(mc)) return;
        stopRotating();
        targetYaw   = endRot.yaw;
        targetPitch = endRot.pitch;
        currStrat   = strategy;
        // Cache GCD once per navigation start instead of recomputing every tick
        double sens = mc.options.sensitivity().get();
        double f    = sens * 0.6 + 0.2;
        cachedGcd   = f * f * f * 1.2;
        strategy.onStart();
        hasLastApplied = false;
        isRotating  = true;
    }

    public static void stopRotating() {
        if (currStrat != null) currStrat.onStop();
        currStrat  = null;
        isRotating = false;
        hasLastApplied = false;
    }

    public static boolean isRotating()  { return isRotating; }
    public static float   getTargetYaw() { return targetYaw; }
    public static float   getTargetPitch() { return targetPitch; }

    /** Called every client tick from AetherClient. */
    public static void update() {
        Minecraft mc = Minecraft.getInstance();
        var player = mc.player;
        if (player == null || !isRotating) return;

        if (hasExternalRotation(player)) {
            FailsafeManager.reportExternalRotation();
            stopRotating();
            return;
        }

        if (currStrat != null) {
            Rotation result = currStrat.onRotate(player, targetYaw, targetPitch);
            if (result == null) {
                stopRotating();
            } else {
                float newYaw   = applyGCD(result.yaw,   player.getYRot());
                float newPitch = Math.max(-90f, Math.min(90f,
                        applyGCD(result.pitch, player.getXRot(), -90f, 90f)));
                player.setYRot(newYaw);
                player.setXRot(newPitch);
                player.yRotO = newYaw;
                player.xRotO = newPitch;
                lastAppliedYaw = player.getYRot();
                lastAppliedPitch = player.getXRot();
                hasLastApplied = true;
                FailsafeManager.expectRotation(player.getYRot(), player.getXRot());
            }
        }
    }

    private static boolean hasExternalRotation(LocalPlayer player) {
        if (!hasLastApplied) return false;

        float yawDrift   = Math.abs(AngleUtils.getRotationDelta(lastAppliedYaw, player.getYRot()));
        float pitchDrift = Math.abs(player.getXRot() - lastAppliedPitch);
        return yawDrift > EXTERNAL_ROTATION_TOLERANCE_DEGREES
                || pitchDrift > EXTERNAL_ROTATION_TOLERANCE_DEGREES;
    }

    /** Simulates Minecraft's GCD (game cursor distance) rounding. */
    private static float applyGCD(float rotation, float prevRotation) {
        return applyGCD(rotation, prevRotation, null, null);
    }

    private static float applyGCD(float rotation, float prevRotation, Float min, Float max) {
        // Use cached GCD (set once per rotateTo call) instead of recomputing every tick
        double gcd = Double.isNaN(cachedGcd) ? computeGcd() : cachedGcd;

        double delta        = AngleUtils.getRotationDelta(prevRotation, rotation);
        double roundedDelta = Math.round(delta / gcd) * gcd;
        float  result       = (float)(prevRotation + roundedDelta);

        if (max != null && result > max) result -= (float) gcd;
        if (min != null && result < min) result += (float) gcd;

        return result;
    }

    private static double computeGcd() {
        double f = mc.options.sensitivity().get() * 0.6 + 0.2;
        return f * f * f * 1.2;
    }
}
