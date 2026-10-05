package dev.aether.ui.orbit;

import org.joml.Vector3d;

// the orbit layout in rig space: player feet at the origin, +z where the player faced on open, +x to their left
// (minecraft's own axes when facing south), y up; one unit is one block
final class OrbitRig {
    static final double RADIUS = 15.0;
    static final double HEIGHT = 4.9;

    static final Vector3d FP_POS = new Vector3d(0, 1.62, 0);
    static final Vector3d TP_POS = new Vector3d(-6.0, 2.2, -6.0);
    static final Vector3d TP_LOOK = new Vector3d(2.8, 2.5, 10.0);
    static final Vector3d OV_POS = new Vector3d(0, 27.0, -37.0);
    static final Vector3d OV_LOOK = new Vector3d(0, 2.0, -3.6);
    static final float FOV = 46f;
    static final float OV_FOV = 30f;

    private OrbitRig() {
    }

    // ring slot for an offset o from the front (0 = front, ±1 = neighbours), zoom z and module expansion e
    static Vector3d slot(double o, double z, double e, double step, Vector3d out) {
        double ao = Math.abs(o);
        double phi = o * step;
        double r = lerp(RADIUS + 3.2 * Math.min(ao, 1.6) - e * RADIUS * 0.17, 12.0, z);
        return out.set(-r * Math.sin(phi), HEIGHT + (1 - z) * 0.35 * Math.min(ao, 2) - z * 2.5, r * Math.cos(phi));
    }

    // camera lean per category so the shot drifts toward what that category affects
    static double[][] lean(String categoryId) {
        return switch (categoryId) {
            case "farming" -> new double[][]{{0.3, -0.35, 0.5}, {-0.3, -0.45, 0}};
            case "pests" -> new double[][]{{0, 0.9, -0.3}, {0.8, 0.4, 0}};
            case "garden" -> new double[][]{{0.6, 0.3, 0}, {2.6, -0.2, 0}};
            case "macros", "other" -> new double[][]{{-0.4, 0, 0}, {-1.6, 0.3, 0}};
            case "safety" -> new double[][]{{0.6, 0, 1.6}, {0.3, -0.1, 0}};
            case "display" -> new double[][]{{0, -0.3, -0.2}, {0, 0.75, 0}};
            case "client" -> new double[][]{{0, 0, 0}, {-0.8, 0.6, 0}};
            default -> new double[][]{{0, 0, 0}, {0, 0, 0}};
        };
    }

    static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static float easeInOut(float t) {
        return t < 0.5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2;
    }

    static float smooth(float t) {
        return t * t * (3 - 2 * t);
    }
}
