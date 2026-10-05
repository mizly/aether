package dev.aether.ui.orbit;

import org.joml.Matrix4f;
import org.joml.Vector3f;

// the player as minecraft draws them, built from the skin's 64x64 layout: head, body, arms and legs with their
// outer layers. local space is the rig's: +z where the figure faces, +x its left, y up, one unit one skin pixel
final class PlayerFigure {
    private static final float PIXEL = 1.8f / 32f;

    private final OrbitSpring headYaw = new OrbitSpring(0f, 40f, 11f);
    private final OrbitSpring headPitch = new OrbitSpring(0f, 40f, 11f);

    // turns the head toward a point given in the figure's local space (blocks), limited like a real neck
    void lookAt(float lx, float ly, float lz, float dt) {
        float eye = 28f * PIXEL;
        float yaw = (float) Math.toDegrees(Math.atan2(lx, lz));
        float pitch = (float) -Math.toDegrees(Math.atan2(ly - eye, Math.hypot(lx, lz)));
        headYaw.t = OrbitRig.clamp(yaw, -70f, 70f);
        headPitch.t = OrbitRig.clamp(pitch, -45f, 45f);
        headYaw.step(dt);
        headPitch.step(dt);
    }

    // appends the figure's triangles; toWorld maps local block coordinates to the buffer's space
    void build(SceneClone.Buffer out, Matrix4f toWorld, boolean slim, float time) {
        float sway = (float) Math.sin(time * 1.3) * 3f;
        float breathe = (float) Math.sin(time * 1.9) * 0.15f;
        int arm = slim ? 3 : 4;
        Matrix4f body = new Matrix4f(toWorld).scale(PIXEL).rotateY((float) Math.toRadians(headYaw.x * 0.15f));
        // legs: hips at y 12
        part(out, body, 0, 12, 0, 0, 0, -4, -12, -2, 4, 12, 4, 0, 16, 0f);
        part(out, body, 0, 12, 0, 0, 0, -4, -12, -2, 4, 12, 4, 0, 32, 0.25f);
        part(out, body, 0, 12, 0, 0, 0, 0, -12, -2, 4, 12, 4, 16, 48, 0f);
        part(out, body, 0, 12, 0, 0, 0, 0, -12, -2, 4, 12, 4, 0, 48, 0.25f);
        // torso
        part(out, body, 0, 12 + breathe, 0, 0, 0, -4, 0, -2, 8, 12, 4, 16, 16, 0f);
        part(out, body, 0, 12 + breathe, 0, 0, 0, -4, 0, -2, 8, 12, 4, 16, 32, 0.25f);
        // arms hang from the shoulders and sway a little
        part(out, body, -4 - arm / 2f, 22 + breathe, 0, sway, 0, -arm / 2f, -10, -2, arm, 12, 4, 40, 16, 0f);
        part(out, body, -4 - arm / 2f, 22 + breathe, 0, sway, 0, -arm / 2f, -10, -2, arm, 12, 4, 40, 32, 0.25f);
        part(out, body, 4 + arm / 2f, 22 + breathe, 0, -sway, 0, -arm / 2f, -10, -2, arm, 12, 4, 32, 48, 0f);
        part(out, body, 4 + arm / 2f, 22 + breathe, 0, -sway, 0, -arm / 2f, -10, -2, arm, 12, 4, 48, 48, 0.25f);
        // head turns about the neck
        float yaw = headYaw.x * 0.85f;
        part(out, body, 0, 24 + breathe, 0, headPitch.x, yaw, -4, 0, -4, 8, 8, 8, 0, 0, 0f);
        part(out, body, 0, 24 + breathe, 0, headPitch.x, yaw, -4, 0, -4, 8, 8, 8, 32, 0, 0.5f);
    }

    // one skin box: pivot, pitch and yaw in degrees about it, box min corner and size, texture offset, inflation
    private static void part(SceneClone.Buffer out, Matrix4f base, float px, float py, float pz, float pitch, float yaw,
                             float x, float y, float z, float w, float h, float d, int u, int v, float grow) {
        Matrix4f m = new Matrix4f(base).translate(px, py, pz).rotateY((float) Math.toRadians(yaw))
                .rotateX((float) Math.toRadians(pitch));
        float x0 = x - grow, y0 = y - grow, z0 = z - grow, x1 = x + w + grow, y1 = y + h + grow, z1 = z + d + grow;
        // the skin's box layout: top and bottom over the four sides, sides right, front, left, back
        face(out, m, x0, y1, z1, x1, y1, z1, x1, y0, z1, x0, y0, z1, u + d, v + d, w, h);
        face(out, m, x1, y1, z0, x0, y1, z0, x0, y0, z0, x1, y0, z0, u + d + w + d, v + d, w, h);
        face(out, m, x0, y1, z0, x0, y1, z1, x0, y0, z1, x0, y0, z0, u, v + d, d, h);
        face(out, m, x1, y1, z1, x1, y1, z0, x1, y0, z0, x1, y0, z1, u + d + w, v + d, d, h);
        face(out, m, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, u + d, v, w, d);
        face(out, m, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, u + d + w, v, w, d);
    }

    // a quad whose corners run top-left, top-right, bottom-right, bottom-left of its skin rectangle
    private static void face(SceneClone.Buffer out, Matrix4f m, float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz, float u, float v, float w, float h) {
        Vector3f a = m.transformPosition(ax, ay, az, new Vector3f());
        Vector3f b = m.transformPosition(bx, by, bz, new Vector3f());
        Vector3f c = m.transformPosition(cx, cy, cz, new Vector3f());
        Vector3f d = m.transformPosition(dx, dy, dz, new Vector3f());
        float u0 = u / 64f, u1 = (u + w) / 64f, v0 = v / 64f, v1 = (v + h) / 64f;
        int color = 0xFFFFFFFF;
        out.vertex(a.x, a.y, a.z, u0, v0, color);
        out.vertex(b.x, b.y, b.z, u1, v0, color);
        out.vertex(c.x, c.y, c.z, u1, v1, color);
        out.vertex(a.x, a.y, a.z, u0, v0, color);
        out.vertex(c.x, c.y, c.z, u1, v1, color);
        out.vertex(d.x, d.y, d.z, u0, v1, color);
    }
}
