package dev.aether.modules.pathfinding.rotation;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public final class AngleUtils {
    private static final Minecraft mc = Minecraft.getInstance();

    private AngleUtils() {}

    public static float normalizeAngle(float angle) {
        float a = angle % 360f;
        if (a >= 180f)  a -= 360f;
        if (a < -180f)  a += 360f;
        return a;
    }

    public static float getRotationDelta(float from, float to) {
        float delta = normalizeAngle(to) - normalizeAngle(from);
        if (delta >  180f) delta -= 360f;
        if (delta < -180f) delta += 360f;
        return delta;
    }

    public static Rotation getRotation(Vec3 from, Vec3 to) {
        double xDiff = to.x - from.x;
        double yDiff = to.y - from.y;
        double zDiff = to.z - from.z;
        double dist  = Math.sqrt(xDiff * xDiff + zDiff * zDiff);
        float  yaw   = (float) Math.toDegrees(Math.atan2(zDiff, xDiff)) - 90f;
        float  pitch = (float)-Math.toDegrees(Math.atan2(yDiff, dist));
        return new Rotation(yaw, pitch);
    }

    public static Rotation getRotation(Vec3 to) {
        if (mc.player == null) throw new IllegalStateException("Player is null");
        return getRotation(mc.player.getEyePosition(), to);
    }

    public static Rotation getRotation(Entity to) {
        if (mc.player == null) throw new IllegalStateException("Player is null");
        double height = Math.min((to.getBbHeight() * 0.85) + (Math.random() * 0.3 - 0.15), 1.7);
        return getRotation(mc.player.getEyePosition(), to.position().add(0, height, 0));
    }
}
