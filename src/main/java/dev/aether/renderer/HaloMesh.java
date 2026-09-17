package dev.aether.renderer;

import org.joml.Matrix4f;
import org.joml.Vector3f;

public final class HaloMesh {
    public static final int MAX_VERTICES = 768;
    private static final int SEGMENTS = 64;
    private static final float[] COS = new float[SEGMENTS + 1], SIN = new float[SEGMENTS + 1];
    static {
        for (int i = 0; i < SEGMENTS; i++) {
            COS[i] = (float) Math.cos(i * Math.PI * 2 / SEGMENTS);
            SIN[i] = (float) Math.sin(i * Math.PI * 2 / SEGMENTS);
        }
        COS[SEGMENTS] = COS[0];
        SIN[SEGMENTS] = SIN[0];
    }

    private final Matrix4f ring = new Matrix4f();
    private final Vector3f[] points = new Vector3f[SEGMENTS + 1];
    private final Vector3f[] widths = new Vector3f[SEGMENTS + 1];
    private final Vector3f tangent = new Vector3f();
    private final Vector3f normal = new Vector3f(), center = new Vector3f();

    public HaloMesh() {
        for (int i = 0; i <= SEGMENTS; i++) {
            points[i] = new Vector3f();
            widths[i] = new Vector3f();
        }
    }

    public void append(CosmeticMesh mesh, Matrix4f transform, int style, float phase, int tint, float glow) {
        if (!mesh.hasRoom(MAX_VERTICES) || (tint >>> 24) == 0) return;
        if (style == 1) {
            ring.set(transform).rotateY(phase * 0.25f).rotateX(0.24f);
            appendRing(mesh, 0.38f, 0.045f, tint, 0.85f, glow, false);
            ring.set(transform).rotateY(phase * 0.25f).rotateX(-0.24f);
            appendRing(mesh, 0.32f, 0.038f, tint, 0.7f, glow, false);
        } else {
            ring.set(transform).rotateY(style == 2 ? phase * 0.2f : 0f);
            appendRing(mesh, 0.38f, 0.055f, tint, 0.9f, glow, style == 2);
            if (style == 2) {
                for (int i = 0; i < SEGMENTS; i += SEGMENTS / 4) {
                    ring.transformPosition(tangent.set(COS[i] * 0.45f, 0, SIN[i] * 0.45f));
                    appendGlint(mesh, tangent, 0.055f * transform.getScale(widths[0]).x, tint, glow);
                }
            }
        }
    }

    private void appendRing(CosmeticMesh mesh, float radius, float width, int tint, float opacity,
                            float glow, boolean segmented) {
        float scale = ring.getScale(tangent).x;
        ring.transformDirection(normal.set(0, 1, 0)).normalize();
        ring.getTranslation(center);
        boolean edgeOn = Math.abs(normal.dot(center)) <= center.length() * 0.1f;
        for (int i = 0; i <= SEGMENTS; i++) {
            ring.transformPosition(points[i].set(COS[i] * radius, 0, SIN[i] * radius));
            if (edgeOn) {
                widths[i].set(normal).mul(width * scale);
                continue;
            }
            ring.transformDirection(tangent.set(-SIN[i], 0, COS[i]));
            tangent.cross(points[i], widths[i]);
            if (widths[i].lengthSquared() < 1.0e-8f) {
                tangent.cross(0, 1, 0, widths[i]);
                if (widths[i].lengthSquared() < 1.0e-8f) tangent.cross(1, 0, 0, widths[i]);
            }
            widths[i].normalize(width * scale);
            if (i > 0 && widths[i].dot(widths[i - 1]) < 0) widths[i].negate();
        }
        for (int i = 0; i < SEGMENTS; i++) {
            if (segmented && (i % 16 < 2 || i % 16 >= 14)) continue;
            float start = segmented ? (i % 16 - 2) / 12f : 0.5f;
            float end = segmented ? (i % 16 - 1) / 12f : 0.5f;
            vertex(mesh, i, -1, start, tint, opacity, glow);
            vertex(mesh, i, 1, start, tint, opacity, glow);
            vertex(mesh, i + 1, 1, end, tint, opacity, glow);
            vertex(mesh, i, -1, start, tint, opacity, glow);
            vertex(mesh, i + 1, 1, end, tint, opacity, glow);
            vertex(mesh, i + 1, -1, end, tint, opacity, glow);
        }
    }

    private void vertex(CosmeticMesh mesh, int index, float side, float along, int tint, float opacity, float glow) {
        Vector3f point = points[index], width = widths[index];
        mesh.vertex(null, point.x + width.x * side, point.y + width.y * side, point.z + width.z * side,
                along, side, tint, opacity, 6, 0, glow);
    }

    private void appendGlint(CosmeticMesh mesh, Vector3f center, float radius, int tint, float glow) {
        Vector3f right = widths[1].set(-center.z, 0, center.x);
        if (right.lengthSquared() < 1.0e-8f) right.set(1, 0, 0);
        right.normalize();
        Vector3f up = widths[2].set(center).cross(right);
        if (up.lengthSquared() < 1.0e-8f) up.set(0, 1, 0);
        up.normalize();
        mesh.billboard(center.x, center.y, center.z, right, up, radius, tint, 0.8f, 7, 0, glow);
    }
}
