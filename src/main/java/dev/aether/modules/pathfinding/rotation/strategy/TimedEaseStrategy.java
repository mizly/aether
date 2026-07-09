package dev.aether.modules.pathfinding.rotation.strategy;

import dev.aether.modules.pathfinding.rotation.AngleUtils;
import dev.aether.modules.pathfinding.rotation.EasingType;
import dev.aether.modules.pathfinding.rotation.IRotationStrategy;
import dev.aether.modules.pathfinding.rotation.Rotation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Time-based rotation strategy with configurable easing functions.
 * Interpolates smoothly from start rotation to target over the given duration.
 */
public final class TimedEaseStrategy implements IRotationStrategy {

    private final EasingType yawEasing;
    private final EasingType pitchEasing;
    private final long       duration;

    private float startYaw;
    private float startPitch;
    private long  endTime;

    public TimedEaseStrategy(EasingType yawEasing, EasingType pitchEasing, long durationMs) {
        this.yawEasing   = yawEasing;
        this.pitchEasing = pitchEasing;
        this.duration    = durationMs;
    }

    /** Convenience: same easing for yaw and pitch. */
    public TimedEaseStrategy(EasingType easing, long durationMs) {
        this(easing, easing, durationMs);
    }

    @Override
    public void onStart() {
        var player = Minecraft.getInstance().player;
        if (player == null) throw new IllegalStateException("Player is null during rotation start");
        startYaw   = player.getYRot();
        startPitch = player.getXRot();
        endTime    = System.currentTimeMillis() + duration;
    }

    @Override
    public Rotation onRotate(LocalPlayer player, float targetYaw, float targetPitch) {
        long now = System.currentTimeMillis();
        if (now >= endTime) return new Rotation(targetYaw, targetPitch);  // hold at target

        float progress = 1f - ((float)(endTime - now) / (float) duration);
        float t        = Math.max(0f, Math.min(1f, progress));

        float yawDelta = AngleUtils.normalizeAngle(targetYaw - startYaw);
        float yaw      = yawEasing.apply(startYaw, startYaw + yawDelta, t);
        float pitch    = clampPitch(pitchEasing.apply(startPitch, clampPitch(targetPitch), t));

        return new Rotation(yaw, pitch);
    }

    private static float clampPitch(float pitch) {
        return Math.max(-90f, Math.min(90f, pitch));
    }
}
