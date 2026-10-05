package dev.aether.ui.orbit;

import dev.aether.bootstrap.CameraOverride;

// the pose the orbit menu films from; render-only, the player entity is never moved or turned
public final class OrbitCamera {
    private static volatile CameraOverride pose;

    private OrbitCamera() {
    }

    public static CameraOverride current() {
        return pose;
    }

    static void set(double x, double y, double z, double lookX, double lookY, double lookZ, float fov) {
        double dx = lookX - x, dy = lookY - y, dz = lookZ - z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yRot = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float xRot = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        pose = new CameraOverride(x, y, z, yRot, xRot, fov);
    }

    static void clear() {
        pose = null;
    }
}
