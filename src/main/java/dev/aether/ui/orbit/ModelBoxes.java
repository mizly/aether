package dev.aether.ui.orbit;

import org.joml.Matrix4f;
import org.joml.Vector3f;

// textured boxes the way minecraft's entity models define them: a pivot, a rotation, a box in model pixels and a
// texture offset in the box layout. minecraft's model space has y down and faces -z; this turns it into the
// scene's local space (y up, +z ahead, +x to the model's left, one unit one block) as it builds
final class ModelBoxes {
    private ModelBoxes() {
    }

    // one part: pivot (px, py, pz) and rotations (radians) exactly as in minecraft's PartPose, box as in addBox
    static void part(SceneClone.Buffer out, Matrix4f local, float texW, float texH, boolean mirror,
                     float px, float py, float pz, float xRot, float yRot, float zRot,
                     float x, float y, float z, float w, float h, float d, int u, int v, float grow) {
        // y and z both flip, so x rotations keep their sign and y and z rotations swap theirs
        Matrix4f m = new Matrix4f(local).scale(1f / 16f).translate(px, 24f - py, -pz)
                .rotateZ(-zRot).rotateY(-yRot).rotateX(xRot);
        box(out, m, texW, texH, mirror, x - grow, -(y + h) - grow, -(z + d) - grow, x + w + grow, -y + grow, -z + grow,
                u, v, w, h, d);
    }

    // a box between two corners in local pixels, faces laid out like minecraft's skins: front +z, its right at -x
    static void box(SceneClone.Buffer out, Matrix4f m, float texW, float texH, boolean mirror,
                    float x0, float y0, float z0, float x1, float y1, float z1, int u, int v, float w, float h, float d) {
        float right = u, left = u + d + w;
        if (mirror) {
            float swap = right;
            right = left;
            left = swap;
        }
        face(out, m, texW, texH, mirror, x0, y1, z1, x1, y1, z1, x1, y0, z1, x0, y0, z1, u + d, v + d, w, h);
        face(out, m, texW, texH, mirror, x1, y1, z0, x0, y1, z0, x0, y0, z0, x1, y0, z0, u + d + w + d, v + d, w, h);
        face(out, m, texW, texH, mirror, x0, y1, z0, x0, y1, z1, x0, y0, z1, x0, y0, z0, right, v + d, d, h);
        face(out, m, texW, texH, mirror, x1, y1, z1, x1, y1, z0, x1, y0, z0, x1, y0, z1, left, v + d, d, h);
        face(out, m, texW, texH, mirror, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, u + d, v, w, d);
        face(out, m, texW, texH, mirror, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, u + d + w, v, w, d);
    }

    // a quad whose corners run top-left, top-right, bottom-right, bottom-left of its texture rectangle
    private static void face(SceneClone.Buffer out, Matrix4f m, float texW, float texH, boolean mirror,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             float u, float v, float w, float h) {
        Vector3f a = m.transformPosition(ax, ay, az, new Vector3f());
        Vector3f b = m.transformPosition(bx, by, bz, new Vector3f());
        Vector3f c = m.transformPosition(cx, cy, cz, new Vector3f());
        Vector3f d = m.transformPosition(dx, dy, dz, new Vector3f());
        float u0 = u / texW, u1 = (u + w) / texW, v0 = v / texH, v1 = (v + h) / texH;
        if (mirror) {
            float swap = u0;
            u0 = u1;
            u1 = swap;
        }
        int color = 0xFFFFFFFF;
        out.vertex(a.x, a.y, a.z, u0, v0, color);
        out.vertex(b.x, b.y, b.z, u1, v0, color);
        out.vertex(c.x, c.y, c.z, u1, v1, color);
        out.vertex(a.x, a.y, a.z, u0, v0, color);
        out.vertex(c.x, c.y, c.z, u1, v1, color);
        out.vertex(d.x, d.y, d.z, u0, v1, color);
    }
}
