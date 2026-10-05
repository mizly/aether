package dev.aether.ui.orbit;

import org.joml.Matrix4f;
import org.joml.Vector3f;

// the player as minecraft draws them, built from the skin's 64x64 layout: head, body, arms and legs with their
// outer layers. local space is the rig's: +z where the figure faces, +x its left, y up, one unit one skin pixel
final class PlayerFigure {
    private static final float PIXEL = 1.8f / 32f;

    private final OrbitSpring headYaw = new OrbitSpring(0f, 40f, 11f);
    private final OrbitSpring headPitch = new OrbitSpring(0f, 40f, 11f);
    private final OrbitSpring panic = new OrbitSpring(0f, 30f, 10f);
    private final OrbitSpring craft = new OrbitSpring(0f, 40f, 12f);
    private final OrbitSpring hold = new OrbitSpring(0f, 40f, 12f);
    private final OrbitSpring lie = new OrbitSpring(0f, 18f, 8f);
    private float waveStart = -10f;
    private float waveX, waveZ;
    private float time;
    private float rightPitch;

    // what the figure is doing this frame, each 0..1; the poses ease in and out on springs
    void pose(float panicLevel, boolean crafting, boolean holding, boolean sleeping, float dt) {
        panic.t = panicLevel;
        craft.t = crafting ? 1f : 0f;
        hold.t = holding ? 1f : 0f;
        lie.t = sleeping ? 1f : 0f;
        panic.step(dt);
        craft.step(dt);
        hold.step(dt);
        lie.step(dt);
    }

    // a wave toward a point in local blocks; the head turns to it for as long as the wave lasts
    void wave(float lx, float lz) {
        waveStart = time;
        waveX = lx;
        waveZ = lz;
    }

    boolean waving() {
        return time - waveStart < 1.8f;
    }

    // turns the head toward a point given in the figure's local space (blocks), limited like a real neck
    void lookAt(float lx, float ly, float lz, float dt) {
        if (waving()) {
            lx = waveX;
            lz = waveZ;
            ly = 1.4f;
        }
        float eye = 28f * PIXEL;
        float yaw = (float) Math.toDegrees(Math.atan2(lx, lz));
        float pitch = (float) -Math.toDegrees(Math.atan2(ly - eye, Math.hypot(lx, lz)));
        float sleepy = OrbitRig.clamp(lie.x, 0f, 1f);
        headYaw.t = OrbitRig.clamp(yaw, -70f, 70f) * (1f - sleepy);
        headPitch.t = OrbitRig.clamp(pitch, -45f, 45f) * (1f - sleepy);
        headYaw.step(dt);
        headPitch.step(dt);
    }

    float lying() {
        return OrbitRig.clamp(lie.x, 0f, 1f);
    }

    // where the right hand is in local blocks, for whatever it holds
    org.joml.Vector3f hand(boolean slim) {
        int arm = slim ? 3 : 4;
        double p = Math.toRadians(rightPitch);
        float sx = -(4 + arm / 2f) * PIXEL, sy = 22f * PIXEL;
        return new org.joml.Vector3f(sx, sy - (float) Math.cos(p) * 10f * PIXEL, -(float) Math.sin(p) * 10f * PIXEL);
    }

    // appends the figure's triangles; toWorld maps local block coordinates to the buffer's space. every pose is
    // played big, the way minecraft live animates mobs: the whole body leans, bobs, turns and squashes with it
    void build(SceneClone.Buffer out, Matrix4f toWorld, boolean slim, float time) {
        this.time = time;
        float scared = OrbitRig.clamp(panic.x, 0f, 1f);
        float sleep = lying();
        float crafting = OrbitRig.clamp(craft.x, 0f, 1f);
        float holding = OrbitRig.clamp(hold.x, 0f, 1f);
        float waving = time - waveStart;
        float waveK = waving < 1.8f ? (float) Math.sin(Math.min(1f, waving / 1.8f) * Math.PI) : 0f;
        float breathe = (float) Math.sin(time * 1.9) * 0.15f + sleep * (float) Math.sin(time * 1.2) * 0.4f;

        // the body: idle weight shift, panic running in place with hops, a dip into each crafting strike, a proud
        // bounce while holding gold, and a turn and lean back toward whoever is being waved at
        float bob = 0f, lean = 0f, roll = (float) Math.sin(time * 0.9) * 2.5f, turn = 0f;
        float run = time * 15f;
        bob += Math.abs((float) Math.sin(run)) * 1.8f * scared;
        bob += (float) Math.pow(Math.max(0, Math.sin(time * 3.1)), 4) * 7f * scared * scared;
        float legs = (float) Math.sin(run) * 45f * scared;
        roll += (float) Math.sin(time * 23) * 6f * scared;
        lean += 8f * scared;
        bob -= (float) Math.max(0, Math.sin(time * 9)) * 0.9f * crafting;
        lean += 12f * crafting;
        bob += Math.abs((float) Math.sin(time * 3)) * 0.6f * holding;
        float toward = OrbitRig.clamp((float) Math.toDegrees(Math.atan2(waveX, waveZ)), -70f, 70f);
        turn += toward * 0.6f * waveK;
        lean -= 7f * waveK;
        bob += Math.abs((float) Math.sin(waving * 7)) * 1.2f * waveK;
        roll += (float) Math.sin(waving * 7) * 4f * waveK;
        float awake = 1f - sleep;
        bob *= awake;
        lean *= awake;
        roll *= awake;
        turn *= awake;
        legs *= awake;

        // the arms: flailing in a panic, held out with the gold, striking at the table, waving big
        float sway = (float) Math.sin(time * 1.3) * 3f;
        float flailR = -150f + (float) Math.sin(time * 17) * 35f;
        float flailL = -150f + (float) Math.sin(time * 17 + 2.1) * 35f;
        float right = lerp(sway, flailR, scared);
        float left = lerp(-sway, flailL, scared);
        right = lerp(right, -35f + (float) Math.sin(time * 3) * 4f, holding);
        right = lerp(right, -70f + (float) Math.sin(time * 9) * 38f, crafting);
        left = lerp(left, -20f, crafting * 0.6f);
        if (waveK > 0f) right = lerp(right, -165f + (float) Math.sin(waving * 14) * 22f, waveK);
        left = lerp(left, 18f, waveK * 0.5f);
        right = lerp(right, 0f, sleep);
        left = lerp(left, 0f, sleep);
        rightPitch = right;

        // the head: the look it was given, plus a whip between threats, a glance down at the work or the gold and a
        // happy tilt while waving
        int beat = (int) (time / 0.32f);
        float whip = ((beat * 2654435761L >>> 16) & 0xFF) / 255f * 120f - 60f;
        float headYawNow = headYaw.x * 0.85f + whip * scared * 0.7f + (float) Math.sin(time * 15) * 12f * scared
                - 22f * holding;
        float headPitchNow = headPitch.x + 28f * crafting + 20f * holding - 10f * scared;
        float tilt = (float) Math.sin(waving * 5) * 12f * waveK + (float) Math.sin(time * 1.3) * 3f * awake;
        int arm = slim ? 3 : 4;
        // lying down tips the figure onto its back, head toward -z, on a bed half a block high
        Matrix4f body = new Matrix4f(toWorld).translate(0f, sleep * 0.68f, 0f).rotateX((float) Math.toRadians(-90f * sleep))
                .scale(PIXEL).translate(0f, bob, 0f)
                .rotateY((float) Math.toRadians(turn + headYaw.x * 0.15f))
                .rotateX((float) Math.toRadians(lean)).rotateZ((float) Math.toRadians(roll));
        // legs: hips at y 12
        part(out, body, 0, 12, 0, legs, 0, -4, -12, -2, 4, 12, 4, 0, 16, 0f);
        part(out, body, 0, 12, 0, legs, 0, -4, -12, -2, 4, 12, 4, 0, 32, 0.25f);
        part(out, body, 0, 12, 0, -legs, 0, 0, -12, -2, 4, 12, 4, 16, 48, 0f);
        part(out, body, 0, 12, 0, -legs, 0, 0, -12, -2, 4, 12, 4, 0, 48, 0.25f);
        // torso
        part(out, body, 0, 12 + breathe, 0, 0, 0, -4, 0, -2, 8, 12, 4, 16, 16, 0f);
        part(out, body, 0, 12 + breathe, 0, 0, 0, -4, 0, -2, 8, 12, 4, 16, 32, 0.25f);
        // arms hang from the shoulders
        part(out, body, -4 - arm / 2f, 22 + breathe, 0, right, 0, -arm / 2f, -10, -2, arm, 12, 4, 40, 16, 0f);
        part(out, body, -4 - arm / 2f, 22 + breathe, 0, right, 0, -arm / 2f, -10, -2, arm, 12, 4, 40, 32, 0.25f);
        part(out, body, 4 + arm / 2f, 22 + breathe, 0, left, 0, -arm / 2f, -10, -2, arm, 12, 4, 32, 48, 0f);
        part(out, body, 4 + arm / 2f, 22 + breathe, 0, left, 0, -arm / 2f, -10, -2, arm, 12, 4, 48, 48, 0.25f);
        // head turns about the neck
        Matrix4f neck = new Matrix4f(body).translate(0, 24 + breathe, 0).rotateZ((float) Math.toRadians(tilt));
        part(out, neck, 0, 0, 0, headPitchNow, headYawNow, -4, 0, -4, 8, 8, 8, 0, 0, 0f);
        part(out, neck, 0, 0, 0, headPitchNow, headYawNow, -4, 0, -4, 8, 8, 8, 32, 0, 0.5f);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
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
