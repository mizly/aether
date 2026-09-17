package dev.aether.modules.pathfinding.execution;

import dev.aether.modules.pathfinding.rotation.AngleUtils;
import dev.aether.modules.pathfinding.rotation.Rotation;

// decides whether a new smoothed turn is worth starting, shared by FlyExecutor and the flight simulator
public final class FlightAim {
    public static final float YAW_THRESHOLD = 1.0f;
    public static final float PITCH_THRESHOLD = 6.0f;
    public static final long MIN_DURATION_MS = 300L;

    private FlightAim() {
    }

    public record Plan(Rotation rotation, long durationMs) {
    }

    public static Plan plan(float currentYaw, float currentPitch, Rotation desired,
                            boolean rotating, float pendingYaw, float pendingPitch) {
        if (desired == null) {
            return null;
        }
        float sourceYaw = rotating ? pendingYaw : currentYaw;
        float sourcePitch = rotating ? pendingPitch : currentPitch;
        float yawDrift = Math.abs(AngleUtils.getRotationDelta(sourceYaw, desired.yaw));
        float pitchDrift = Math.abs(AngleUtils.getRotationDelta(sourcePitch, desired.pitch));
        boolean settled = !rotating
                || (Math.abs(AngleUtils.getRotationDelta(currentYaw, sourceYaw)) <= YAW_THRESHOLD
                        && Math.abs(currentPitch - sourcePitch) <= PITCH_THRESHOLD);

        if (!((settled && (yawDrift > YAW_THRESHOLD || pitchDrift > PITCH_THRESHOLD))
                || yawDrift > 16.0f
                || pitchDrift > 14.0f)) {
            return null;
        }

        float turn = Math.max(Math.abs(AngleUtils.getRotationDelta(currentYaw, desired.yaw)),
                Math.abs(desired.pitch - currentPitch));
        return new Plan(desired, Math.max(MIN_DURATION_MS, (long) (turn * 1000.0f / 180.0f)));
    }
}
