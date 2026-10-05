package dev.aether.ui.orbit;

import org.joml.Matrix4f;
import org.joml.Vector3f;

// the player as minecraft draws them, built from the skin's 64x64 layout: head, body, arms and legs with their
// outer layers. local space is the rig's: +z where the figure faces, +x its left, y up, one unit one skin pixel.
// it stands idle by itself; the farm's actors drive everything else through pose, a fresh set of channels a frame
final class PlayerFigure {
    static final float PIXEL = 1.8f / 32f;

    // what the actors want this frame. angles in degrees, bob in skin pixels, x/y/z/facing in farm blocks/degrees;
    // an arm or the legs only move when their weight is above 0
    static final class Pose {
        float x, y, z, facing;
        float bob, lean, roll, turn;
        float legs, legsWeight;
        float right, rightWeight, left, leftWeight;
        float headYaw, headPitch, tilt, look = 1f;
        float lie;

        void reset() {
            x = y = z = facing = bob = lean = roll = turn = legs = legsWeight = 0f;
            right = rightWeight = left = leftWeight = headYaw = headPitch = tilt = lie = 0f;
            look = 1f;
        }
    }

    final Pose pose = new Pose();
    private final OrbitSpring headYaw = new OrbitSpring(0f, 40f, 11f);
    private final OrbitSpring headPitch = new OrbitSpring(0f, 40f, 11f);
    private float waveStart = -10f;
    private float waveX, waveZ;
    private float time;
    private final Matrix4f body = new Matrix4f();
    private final Matrix4f head = new Matrix4f();
    private final Matrix4f rightArm = new Matrix4f();
    private final Matrix4f leftArm = new Matrix4f();
    private final Matrix4f hips = new Matrix4f();
    private final Matrix4f rightLeg = new Matrix4f();
    private final Matrix4f leftLeg = new Matrix4f();

    // a wave toward a point in farm blocks; the head turns to it for as long as the wave lasts
    void wave(float lx, float lz) {
        waveStart = time;
        waveX = lx;
        waveZ = lz;
    }

    boolean waving() {
        return time - waveStart < 1.8f;
    }

    // turns the head toward a point in farm blocks, limited like a real neck
    void lookAt(float lx, float ly, float lz, float dt) {
        lx -= pose.x;
        lz -= pose.z;
        if (waving()) {
            lx = waveX - pose.x;
            lz = waveZ - pose.z;
            ly = 1.4f;
        }
        double facing = Math.toRadians(pose.facing);
        float rx = (float) (lx * Math.cos(facing) - lz * Math.sin(facing));
        float rz = (float) (lx * Math.sin(facing) + lz * Math.cos(facing));
        float eye = 28f * PIXEL + pose.y;
        float yaw = (float) Math.toDegrees(Math.atan2(rx, rz));
        float pitch = (float) -Math.toDegrees(Math.atan2(ly - eye, Math.hypot(rx, rz)));
        float free = OrbitRig.clamp(pose.look, 0f, 1f) * (1f - OrbitRig.clamp(pose.lie, 0f, 1f));
        headYaw.t = OrbitRig.clamp(yaw, -70f, 70f) * free;
        headPitch.t = OrbitRig.clamp(pitch, -45f, 45f) * free;
        headYaw.step(dt);
        headPitch.step(dt);
    }

    // where the right hand is in farm blocks, for whatever it holds
    Vector3f hand() {
        return rightArm.transformPosition(0f, -10f, 0f, new Vector3f());
    }

    // the head, body and arm frames of the last build in skin pixels, for things worn or carried
    Matrix4f headFrame() {
        return new Matrix4f(head);
    }

    Matrix4f bodyFrame() {
        return new Matrix4f(body);
    }

    Matrix4f rightArmFrame() {
        return new Matrix4f(rightArm);
    }

    Matrix4f leftArmFrame() {
        return new Matrix4f(leftArm);
    }

    Matrix4f rightLegFrame() {
        return new Matrix4f(rightLeg);
    }

    Matrix4f leftLegFrame() {
        return new Matrix4f(leftLeg);
    }

    // appends the figure's triangles; toWorld maps farm blocks to the buffer's space
    void build(SceneClone.Buffer out, Matrix4f toWorld, boolean slim, float time) {
        this.time = time;
        Pose p = pose;
        float sleep = OrbitRig.clamp(p.lie, 0f, 1f);
        float awake = 1f - sleep;
        float waving = time - waveStart;
        float waveK = waving < 1.8f ? (float) Math.sin(Math.min(1f, waving / 1.8f) * Math.PI) : 0f;
        float breathe = (float) Math.sin(time * 1.9) * 0.15f + sleep * (float) Math.sin(time * 1.2) * 0.45f;

        // idle weight shift and a wave that turns the whole body toward whoever is greeted
        float bob = p.bob, lean = p.lean, roll = p.roll + (float) Math.sin(time * 0.9) * 2.5f * awake, turn = p.turn;
        float toward = OrbitRig.clamp((float) Math.toDegrees(Math.atan2(waveX - p.x, waveZ - p.z)) - p.facing, -70f, 70f);
        turn += toward * 0.6f * waveK;
        lean -= 7f * waveK;
        bob += Math.abs((float) Math.sin(waving * 7)) * 1.2f * waveK;
        roll += (float) Math.sin(waving * 7) * 4f * waveK;
        float legs = p.legs * OrbitRig.clamp(p.legsWeight, 0f, 1f);

        float sway = (float) Math.sin(time * 1.3) * 3f;
        float right = lerp(sway, p.right, OrbitRig.clamp(p.rightWeight, 0f, 1f));
        float left = lerp(-sway, p.left, OrbitRig.clamp(p.leftWeight, 0f, 1f));
        if (waveK > 0f) right = lerp(right, -165f + (float) Math.sin(waving * 14) * 22f, waveK);
        left = lerp(left, 18f, waveK * 0.5f);
        right = lerp(right, 0f, sleep);
        left = lerp(left, 0f, sleep);

        float headYawNow = headYaw.x * 0.85f + p.headYaw;
        float headPitchNow = headPitch.x + p.headPitch;
        float tilt = p.tilt + (float) Math.sin(waving * 5) * 12f * waveK + (float) Math.sin(time * 1.3) * 3f * awake;
        int arm = slim ? 3 : 4;
        // lying down tips the figure onto its back, head toward -z, half a block up on the bed
        hips.set(toWorld).translate(p.x, p.y, p.z).rotateY((float) Math.toRadians(p.facing))
                .translate(0f, sleep * 0.68f, 0f).rotateX((float) Math.toRadians(-90f * sleep))
                .scale(PIXEL).translate(0f, bob, 0f)
                .rotateY((float) Math.toRadians(turn + headYaw.x * 0.15f)).rotateZ((float) Math.toRadians(roll));
        // legs hang from the hips at y 12 and stay planted; the upper body bends forward and back at the waist
        rightLeg.set(hips).translate(-2f, 12f, 0f).rotateX((float) Math.toRadians(legs));
        leftLeg.set(hips).translate(2f, 12f, 0f).rotateX((float) Math.toRadians(-legs));
        limb(out, rightLeg, -2, -12, -2, 4, 12, 4, 0, 16, 0, 32);
        limb(out, leftLeg, -2, -12, -2, 4, 12, 4, 16, 48, 0, 48);
        body.set(hips).translate(0f, 12f, 0f).rotateX((float) Math.toRadians(lean)).translate(0f, -12f, 0f);
        // torso
        part(out, body, 0, 12 + breathe, 0, 0, 0, -4, 0, -2, 8, 12, 4, 16, 16, 0f);
        part(out, body, 0, 12 + breathe, 0, 0, 0, -4, 0, -2, 8, 12, 4, 16, 32, 0.25f);
        // arms hang from the shoulders
        rightArm.set(body).translate(-4 - arm / 2f, 22 + breathe, 0).rotateX((float) Math.toRadians(right));
        leftArm.set(body).translate(4 + arm / 2f, 22 + breathe, 0).rotateX((float) Math.toRadians(left));
        limb(out, rightArm, -arm / 2f, -10, -2, arm, 12, 4, 40, 16, 40, 32);
        limb(out, leftArm, -arm / 2f, -10, -2, arm, 12, 4, 32, 48, 48, 48);
        // head turns about the neck
        head.set(body).translate(0, 24 + breathe, 0).rotateZ((float) Math.toRadians(tilt))
                .rotateY((float) Math.toRadians(headYawNow)).rotateX((float) Math.toRadians(headPitchNow));
        part(out, head, 0, 0, 0, 0, 0, -4, 0, -4, 8, 8, 8, 0, 0, 0f);
        part(out, head, 0, 0, 0, 0, 0, -4, 0, -4, 8, 8, 8, 32, 0, 0.5f);
    }

    // a limb box with its outer layer a quarter pixel proud
    private static void limb(SceneClone.Buffer out, Matrix4f frame, float x, float y, float z, float w, float h, float d,
                             int u, int v, int outerU, int outerV) {
        part(out, frame, 0, 0, 0, 0, 0, x, y, z, w, h, d, u, v, 0f);
        part(out, frame, 0, 0, 0, 0, 0, x, y, z, w, h, d, outerU, outerV, 0.25f);
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
