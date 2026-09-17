package dev.aether.renderer;

import org.joml.Matrix4f;
import org.joml.Vector3f;

public final class DragonWingMesh {
    private static final float[][] REST = {
            {0, 0, 0}, {0.62f, 0.30f, -0.03f}, {1.13f, 0.51f, -0.13f},
            {2.45f, 0.50f, -0.30f}, {2.08f, -0.10f, -0.48f},
            {1.63f, -0.66f, -0.55f}, {0.95f, -0.83f, -0.48f}, {0.04f, -0.54f, -0.02f}
    };
    private static final int EDGE_STEPS = 8;
    private static final int BONE_SIDES = 6;
    private static final float[] COS = new float[BONE_SIDES + 1], SIN = new float[BONE_SIDES + 1];
    static {
        for (int i = 0; i <= BONE_SIDES; i++) {
            COS[i] = (float) Math.cos(i * Math.PI * 2 / BONE_SIDES);
            SIN[i] = (float) Math.sin(i * Math.PI * 2 / BONE_SIDES);
        }
    }
    private final Vector3f[] joints = new Vector3f[REST.length];
    private final Matrix4f wing = new Matrix4f();
    private final Vector3f edgeA = new Vector3f(), edgeB = new Vector3f();
    private final Vector3f along = new Vector3f(), normal = new Vector3f(), tangent = new Vector3f();

    public DragonWingMesh() {
        for (int i = 0; i < joints.length; i++) joints[i] = new Vector3f();
    }

    public void append(CosmeticMesh mesh, Matrix4f body, float phase, float amplitude,
                       float fold, int tint, boolean glow) {
        append(mesh, body, phase, amplitude, fold, tint, glow, false);
    }

    public void append(CosmeticMesh mesh, Matrix4f body, float phase, float amplitude,
                       float fold, int tint, boolean glow, boolean wireframe) {
        if (!mesh.hasRoom(3100)) return;
        pose(phase, fold);
        for (int side = -1; side <= 1; side += 2) {
            wing.set(body).translate(side * 0.19f, 1.38f, -0.18f)
                    .scale(side, 1, 1).rotateZ(0.12f + (float) Math.sin(phase) * amplitude)
                    .rotateY(fold * 0.35f);
            // Skin lies behind the raised bones, giving the wing a readable silhouette on both sides.
            if (!wireframe) {
                skinVertex(mesh, joints[0], 0, 0, tint, phase, glow);
                skinVertex(mesh, joints[2], 1, 0, tint, phase, glow);
                skinVertex(mesh, joints[7], 0, 1, tint, phase, glow);
            }
            for (int panel = 3; panel < 7; panel++) {
                scallop(panel, 0, edgeA, phase);
                for (int step = 1; step <= EDGE_STEPS; step++) {
                    float t = step / (float) EDGE_STEPS;
                    scallop(panel, t, edgeB, phase);
                    if (!wireframe) {
                        skinVertex(mesh, joints[2], 0.5f, 0, tint, phase, glow);
                        skinVertex(mesh, edgeA, (step - 1f) / EDGE_STEPS, 1, tint, phase, glow);
                        skinVertex(mesh, edgeB, t, 1, tint, phase, glow);
                    }
                    bone(mesh, edgeA, edgeB, 0.009f, 0.009f, tint, wireframe ? 1f : 0.5f);
                    edgeA.set(edgeB);
                }
            }
            int boneColor = wireframe ? tint : 0xFF756777;
            bone(mesh, joints[0], joints[1], wireframe ? 0.018f : 0.075f, wireframe ? 0.014f : 0.055f, boneColor, 1f);
            bone(mesh, joints[1], joints[2], wireframe ? 0.014f : 0.055f, wireframe ? 0.012f : 0.041f, boneColor, 1f);
            for (int finger = 3; finger <= 7; finger++) {
                bone(mesh, joints[2], joints[finger], wireframe ? 0.012f : finger == 3 ? 0.036f : 0.026f,
                        0.006f, boneColor, 1f);
            }
            if (wireframe) {
                bone(mesh, joints[7], joints[0], 0.009f, 0.009f, tint, 1f);
            }
        }
    }

    private void pose(float phase, float fold) {
        float wristAngle = fold + 0.10f * (float) Math.sin(phase - 0.65f);
        float c = (float) Math.cos(wristAngle), s = (float) Math.sin(wristAngle);
        for (int i = 0; i < REST.length; i++) {
            float[] point = REST[i];
            joints[i].set(point[0], point[1], point[2]);
            if (i >= 2 && i < 7) {
                float dx = point[0] - REST[1][0], dz = point[2] - REST[1][2];
                joints[i].x = REST[1][0] + c * dx + s * dz;
                joints[i].z = REST[1][2] - s * dx + c * dz;
            }
        }
    }

    private void scallop(int panel, float t, Vector3f result, float phase) {
        float notch = (float) Math.sin(t * Math.PI);
        result.set(joints[panel]).lerp(joints[panel + 1], t).lerp(joints[2], notch * 0.24f);
        result.z -= notch * (0.035f + 0.018f * (float) Math.sin(phase - panel * 0.45f));
    }

    private void skinVertex(CosmeticMesh mesh, Vector3f point, float u, float v, int tint, float phase, boolean glow) {
        mesh.vertex(wing, point.x, point.y, point.z - 0.012f, u, v, tint | 0xFF000000, 1, 0, phase, glow ? 1 : 0);
    }

    private void bone(CosmeticMesh mesh, Vector3f a, Vector3f b, float start, float end, int tint, float brightness) {
        along.set(b).sub(a).normalize();
        normal.set(0, 0, 1).cross(along).normalize();
        tangent.set(along).cross(normal);
        for (int side = 0; side < BONE_SIDES; side++) {
            float light = 0.52f + 0.48f * Math.max(0, COS[side] * 0.765f + SIN[side] * 0.644f);
            int color = shade(tint, light * brightness);
            boneVertex(mesh, a, start, side, color);
            boneVertex(mesh, b, end, side, color);
            boneVertex(mesh, b, end, side + 1, color);
            boneVertex(mesh, a, start, side, color);
            boneVertex(mesh, b, end, side + 1, color);
            boneVertex(mesh, a, start, side + 1, color);
        }
    }

    private void boneVertex(CosmeticMesh mesh, Vector3f center, float radius, int side, int color) {
        float c = COS[side] * radius, s = SIN[side] * radius;
        mesh.vertex(wing, center.x + normal.x * c + tangent.x * s,
                center.y + normal.y * c + tangent.y * s, center.z + normal.z * c + tangent.z * s,
                0, 0, color, 1, 1, 0, 0);
    }

    private static int shade(int color, float factor) {
        return 0xFF000000 | (int) (((color >>> 16) & 255) * factor) << 16
                | (int) (((color >>> 8) & 255) * factor) << 8 | (int) ((color & 255) * factor);
    }
}
