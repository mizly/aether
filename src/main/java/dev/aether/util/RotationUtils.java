package dev.aether.util;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

public class RotationUtils {
    public static final Random RANDOM = new Random();

    public static class Rotation {
        public float yaw;
        public float pitch;

        public Rotation(float yaw, float pitch) {
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    /**
     * Calculates the yaw and pitch needed to look from 'from' to 'to'.
     */
    public static Rotation calculateLookAt(Vec3 from, Vec3 to) {
        double d0 = to.x - from.x;
        double d1 = to.y - from.y;
        double d2 = to.z - from.z;
        double d3 = Math.sqrt(d0 * d0 + d2 * d2);

        float f = (float) (Mth.atan2(d2, d0) * (double) (180F / (float) Math.PI)) - 90F;
        float f1 = (float) (-(Mth.atan2(d1, d3) * (double) (180F / (float) Math.PI)));

        return new Rotation(f, f1);
    }

    /**
     * Calculates the rotation at a specific point 't' (0.0 to 1.0) on a Quadratic
     * Bezier curve.
     */
    public static Rotation calculateBezierPoint(float t, Rotation start, Rotation end, Rotation control) {
        // Apply cubic easing for smoother start/end (Ease-In-Out)
        // Formula: f(t) = t^2 * (3 - 2t)
        float easedT = t * t * (3 - 2 * t);
        float oneMinusT = 1 - easedT;

        // Quadratic Bezier Formula: B(t) = (1-t)^2 * P0 + 2(1-t)t * P1 + t^2 * P2

        float yaw = (oneMinusT * oneMinusT * start.yaw) +
                (2 * oneMinusT * easedT * control.yaw) +
                (easedT * easedT * end.yaw);

        float pitch = (oneMinusT * oneMinusT * start.pitch) +
                (2 * oneMinusT * easedT * control.pitch) +
                (easedT * easedT * end.pitch);

        return new Rotation(yaw, pitch);
    }

    /**
     * Generates a random control point for the Bezier curve to simulate a human
     * arc.
     * Ensures the control point follows the shortest path logic.
     */
    public static Rotation generateControlPoint(Rotation start, Rotation end) {
        // Normalize yaw difference to shortest path
        float yawDiff = Mth.wrapDegrees(end.yaw - start.yaw);
        float targetYaw = start.yaw + yawDiff;

        // Random offset
        float controlYaw = start.yaw + yawDiff * 0.5f + (RANDOM.nextFloat() - 0.5f) * 20.0f; // +/- 10 degrees
        float controlPitch = start.pitch + (end.pitch - start.pitch) * 0.5f + (RANDOM.nextFloat() - 0.5f) * 10.0f; // +/- 5 degrees

        // Return a rotation object. Note: targetYaw might be outside -180/180, distinct
        // from end.yaw's normalized value
        // But for the calculation it's fine.
        return new Rotation(controlYaw, controlPitch);
    }

    // Helper to get the 'unwrapped' end rotation so interpolation works linearly
    public static Rotation getAdjustedEnd(Rotation start, Rotation end) {
        float yawDiff = Mth.wrapDegrees(end.yaw - start.yaw);
        return new Rotation(start.yaw + yawDiff, end.pitch);
    }

    /**
     * Checks if the given yaw/pitch is looking at the target position within the specified tolerance.
     */
    public static boolean isLookingAt(float currentYaw, float currentPitch, Vec3 eyePos, Vec3 targetPos, float tolerance) {
        Rotation target = calculateLookAt(eyePos, targetPos);
        float yawDiff = Math.abs(Mth.wrapDegrees(currentYaw - target.yaw));
        float pitchDiff = Math.abs(Mth.wrapDegrees(currentPitch - target.pitch));
        return yawDiff <= tolerance && pitchDiff <= tolerance;
    }

    /**
     * Applies a random offset to a rotation within specified range (degree).
     */
    public static Rotation applyImprecision(Rotation rot, float range) {
        if (range <= 0) return rot;
        float yawOffset = (RANDOM.nextFloat() - 0.5f) * range;
        float pitchOffset = (RANDOM.nextFloat() - 0.5f) * range;
        return new Rotation(rot.yaw + yawOffset, rot.pitch + pitchOffset);
    }
}
