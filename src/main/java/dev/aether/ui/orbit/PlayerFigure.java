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
        // squash is a crouch (positive) or stretch (negative) of the whole body; squint shuts the eyes
        float squash, squint;
        // vanish shrinks and spins the figure away to nothing, for a warp
        float vanish;
        // snap jumps straight to x / y / z this frame instead of gliding there, for a teleport
        boolean snap;

        void reset() {
            x = y = z = facing = bob = lean = roll = turn = legs = legsWeight = 0f;
            right = rightWeight = left = leftWeight = headYaw = headPitch = tilt = lie = squash = squint = vanish = 0f;
            snap = false;
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

    // every channel the skits drive goes through a spring, so a change of pose blends with a little overshoot
    // and follow-through instead of snapping; arms and head are looser than the body
    private static final class Channel {
        final float k, c;
        float x, v;

        Channel(float k, float zeta) {
            this.k = k;
            this.c = 2f * zeta * (float) Math.sqrt(k);
        }

        float to(float target, float dt) {
            // small steps keep the stiff springs stable at low frame rates
            int n = Math.max(1, (int) Math.ceil(dt / (1f / 240f)));
            float h = dt / n;
            for (int i = 0; i < n; i++) {
                v += (-k * (x - target) - c * v) * h;
                x += v * h;
            }
            return x;
        }
    }

    private final Channel facingS = new Channel(300f, 0.85f);
    private final Channel leanS = new Channel(260f, 0.62f);
    private final Channel rollS = new Channel(260f, 0.62f);
    private final Channel turnS = new Channel(220f, 0.65f);
    private final Channel tiltS = new Channel(200f, 0.55f);
    private final Channel bobS = new Channel(500f, 0.6f);
    private final Channel legsS = new Channel(900f, 0.75f);
    private final Channel rightS = new Channel(650f, 0.5f);
    private final Channel leftS = new Channel(600f, 0.5f);
    private final Channel headYawS = new Channel(240f, 0.55f);
    private final Channel headPitchS = new Channel(260f, 0.55f);
    private final Channel lieS = new Channel(110f, 0.8f);
    private final Channel squashS = new Channel(420f, 0.35f);
    // position glides too, so a skit starting or stopping never teleports the figure
    private final Channel xS = new Channel(240f, 0.9f);
    private final Channel yS = new Channel(900f, 0.85f);
    private final Channel zS = new Channel(240f, 0.9f);
    private boolean primed;
    private float lastTime, lastY, lastVy, lastFacing;
    private float blinkAt = 2f;

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
        float dt = primed ? OrbitRig.clamp(time - lastTime, 0f, 0.1f) : 0f;
        this.time = time;
        lastTime = time;
        Pose p = pose;
        if (!primed || p.snap) {
            xS.x = p.x;
            yS.x = p.y;
            zS.x = p.z;
            xS.v = yS.v = zS.v = 0f;
        }
        if (!primed) {
            primed = true;
            lastY = p.y;
            lastFacing = facingS.x = p.facing;
        }
        float px = xS.to(p.x, dt), py = yS.to(p.y, dt), pz = zS.to(p.z, dt);
        float sleep = OrbitRig.clamp(lieS.to(p.lie, dt), 0f, 1.05f);
        float awake = 1f - OrbitRig.clamp(sleep, 0f, 1f);
        float waving = time - waveStart;
        float waveK = waving < 1.8f ? (float) Math.sin(Math.min(1f, waving / 1.8f) * Math.PI) : 0f;
        float breathe = (float) Math.sin(time * 1.9) * 0.15f + (1f - awake) * (float) Math.sin(time * 1.2) * 0.45f;

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
        float flat = OrbitRig.clamp(sleep, 0f, 1f);
        right = lerp(right, 0f, flat);
        left = lerp(left, 0f, flat);

        float facing = facingS.to(p.facing, dt);
        // the head lags a quick turn of the body and catches up, the way a real one does
        float spin = dt > 0f ? (facing - lastFacing) / dt : 0f;
        lastFacing = facing;
        float drag = OrbitRig.clamp(-spin * 0.09f, -40f, 40f);
        lean = leanS.to(lean, dt);
        roll = rollS.to(roll, dt);
        turn = turnS.to(turn, dt);
        bob = bobS.to(bob, dt);
        legs = legsS.to(legs, dt);
        right = rightS.to(right, dt);
        left = leftS.to(left, dt);
        float headYawNow = headYawS.to(headYaw.x * 0.85f + p.headYaw + drag, dt);
        float headPitchNow = headPitchS.to(headPitch.x + p.headPitch, dt);
        float tilt = tiltS.to(p.tilt + (float) Math.sin(waving * 5) * 12f * waveK, dt) + (float) Math.sin(time * 1.3) * 3f * awake;

        // squash and stretch: stretched while flying up or down, squashed by the landing, plus any crouch asked for
        float vy = dt > 0f ? (p.y - lastY) / dt : 0f;
        if (lastVy < -1.2f && vy > lastVy * 0.3f) squashS.v += Math.min(-lastVy, 7f) * 0.55f;
        lastVy = vy;
        lastY = p.y;
        float squash = squashS.to(p.squash, dt);
        float stretch = OrbitRig.clamp(Math.abs(vy) * 0.03f, 0f, 0.1f) * awake;
        float sy = 1f - squash + stretch, sxz = 1f + squash * 0.5f - stretch * 0.4f;

        int arm = slim ? 3 : 4;
        // lying down tips the figure onto its back, head toward -z, half a block up on the bed
        float gone = OrbitRig.clamp(p.vanish, 0f, 1f);
        sxz *= 1f - gone;
        sy *= 1f - gone * gone;
        hips.set(toWorld).translate(px, py, pz).rotateY((float) Math.toRadians(facing + gone * gone * 720f))
                .translate(0f, sleep * 0.68f, 0f).rotateX((float) Math.toRadians(-90f * sleep))
                .scale(PIXEL * sxz, PIXEL * sy, PIXEL * sxz).translate(0f, bob, 0f)
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
        // blinks every few seconds, squints when asked and sleeps with them shut: the brow row of the skin is drawn
        // down over the eye row
        if (time > blinkAt + 0.13f) {
            double r = Math.sin(time * 12.9898) * 43758.5453;
            blinkAt = time + 2.2f + (float) (r - Math.floor(r)) * 3f;
        }
        boolean shut = (time >= blinkAt && time < blinkAt + 0.13f) || sleep > 0.5f || p.squint > 0.5f;
        if (shut) face(out, head, -4f, 4f, 4.02f, 4f, 4.02f, 4f, 4f, 3f, 4.02f, -4f, 3f, 4.02f, 8, 11, 8, 1);
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
