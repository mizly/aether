package dev.aether.ui.orbit;

import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

// minecraft's own particles for the farm's skits: the vanilla sprites, sizes, colours and physics (ticked at 20 a
// second like the game's particle engine), drawn as camera-facing cutout quads. positions are in farm blocks
final class SceneParticles {
    private static final float TICK = 0.05f;
    private static final Identifier[] POOF = frames("generic_", 7, 0);
    private static final Identifier[] SPARK = frames("spark_", 7, 0);
    private static final Identifier[] BUBBLE_POP = frames("bubble_pop_", 0, 4);
    private static final Identifier[] SPLASH = frames("splash_", 0, 3);
    private static final Identifier GLINT = particle("glint");
    private static final Identifier CRIT = particle("critical_hit");
    private static final Identifier MAGIC = particle("enchanted_hit");
    private static final Identifier DRIP = particle("drip_fall");
    private static final Identifier HANG = particle("drip_hang");
    private static final Identifier BUBBLE = particle("bubble");
    private static final Identifier ANGRY = particle("angry");
    private static final Identifier NOTE = particle("note");
    private static final Identifier FONT = Identifier.withDefaultNamespace("textures/font/ascii.png");

    private static final class Particle {
        Identifier[] frames;
        boolean byAge;
        float u0 = 0f, v0 = 0f, u1 = 1f, v1 = 1f;
        float x, y, z, px, py, pz, vx, vy, vz;
        float gravity, friction = 0.98f;
        int age, lifetime;
        float size;
        float r = 1f, g = 1f, b = 1f;
        boolean popIn, ground, shrink;
        // a bubble bursts into the pop animation where it ends; a drop splashes where it lands
        boolean pops, splashes;
        // a bead stuck to a moving point (the side of a head) until it lets go and falls as a drip
        Vector3f anchor;
        float ox, oy, oz;
        int hold;
        float shadow = -1f;
    }

    private final List<Particle> live = new ArrayList<>();
    private final Random random = new Random();
    private float pending;

    // -- the vanilla kinds ------------------------------------------------------------------------------------

    // the poof a mob makes when it appears or dies: grey puffs shrinking through generic_7..0, drifting up
    void poof(float x, float y, float z, int count, float spread) {
        for (int i = 0; i < count; i++) {
            Particle p = add(POOF, true, x + gauss() * spread, y + gauss() * spread * 0.6f, z + gauss() * spread);
            p.vx = gauss() * 0.06f;
            p.vy = random.nextFloat() * 0.05f;
            p.vz = gauss() * 0.06f;
            p.gravity = -0.1f;
            p.friction = 0.9f;
            float col = random.nextFloat() * 0.3f + 0.7f;
            p.r = p.g = p.b = col;
            p.size = 0.1f * (random.nextFloat() * random.nextFloat() * 6f + 1f);
            p.lifetime = (int) (16.0 / (random.nextFloat() * 0.8 + 0.2)) + 2;
        }
    }

    // a softer drift of the same sprites, for breath and suction: travels along v (blocks a tick)
    void cloud(float x, float y, float z, float vx, float vy, float vz, float scale) {
        Particle p = add(POOF, true, x, y, z);
        p.vx = vx;
        p.vy = vy;
        p.vz = vz;
        p.friction = 0.96f;
        float col = 1f - random.nextFloat() * 0.3f;
        p.r = p.g = p.b = col;
        p.size = 0.1f * (random.nextFloat() * 0.5f + 0.5f) * 2f * scale;
        p.lifetime = (int) (8.0 / (random.nextFloat() * 0.8 + 0.3));
    }

    // a happy villager's green sparkles, hanging nearly still
    void happy(float x, float y, float z, int count, float spread) {
        for (int i = 0; i < count; i++) {
            Particle p = add(new Identifier[]{GLINT}, false, x + gauss() * spread, y + gauss() * spread, z + gauss() * spread);
            p.vx = gauss() * 0.004f;
            p.vy = gauss() * 0.004f;
            p.vz = gauss() * 0.004f;
            p.friction = 0.99f;
            p.size = 0.1f * (random.nextFloat() * 0.5f + 0.5f) * 2f * (random.nextFloat() * 0.6f + 0.5f);
            p.lifetime = (int) (20.0 / (random.nextFloat() * 0.8 + 0.2));
        }
    }

    // an angry villager's storm cloud, floating up off the head
    void angry(float x, float y, float z) {
        Particle p = add(new Identifier[]{ANGRY}, false, x, y + 0.5f, z);
        p.vy = 0.1f;
        p.friction = 0.86f;
        p.size = 0.1f * (random.nextFloat() * 0.5f + 0.5f) * 3f;
        p.lifetime = 16;
        p.popIn = true;
    }

    // a note block's note in one of its rainbow colours
    void note(float x, float y, float z) {
        Particle p = add(new Identifier[]{NOTE}, false, x, y, z);
        p.vy = 0.2f;
        p.friction = 0.66f;
        float hue = random.nextInt(25) / 24f;
        p.r = Math.max(0f, (float) Math.sin((hue + 0f) * Math.PI * 2) * 0.65f + 0.35f);
        p.g = Math.max(0f, (float) Math.sin((hue + 1f / 3f) * Math.PI * 2) * 0.65f + 0.35f);
        p.b = Math.max(0f, (float) Math.sin((hue + 2f / 3f) * Math.PI * 2) * 0.65f + 0.35f);
        p.size = 0.1f * (random.nextFloat() * 0.5f + 0.5f) * 3f;
        p.lifetime = 6;
        p.popIn = true;
    }

    // critical hit sparks fanning out along v; magic turns them into the cyan enchanted hit
    void crit(float x, float y, float z, int count, boolean magic) {
        for (int i = 0; i < count; i++) {
            Particle p = add(new Identifier[]{magic ? MAGIC : CRIT}, false, x, y, z);
            float a = random.nextFloat() * (float) Math.PI * 2, up = random.nextFloat() * 0.6f - 0.1f;
            p.vx = (float) Math.cos(a) * 0.25f;
            p.vy = up * 0.4f;
            p.vz = (float) Math.sin(a) * 0.25f;
            p.friction = 0.7f;
            p.gravity = 0.5f;
            float col = random.nextFloat() * 0.3f + 0.6f;
            p.r = p.g = p.b = col;
            if (magic) {
                p.r *= 0.3f;
                p.g *= 0.8f;
            }
            p.size = 0.1f * (random.nextFloat() * 0.5f + 0.5f) * 2f * 0.75f;
            p.lifetime = Math.max((int) (6.0 / (random.nextFloat() * 0.8 + 0.6)), 1) + 4;
            p.popIn = true;
        }
    }

    // a firework spark in one colour, animating spark_7..0 as it falls
    void spark(float x, float y, float z, float vx, float vy, float vz, int rgb) {
        Particle p = add(SPARK, true, x, y, z);
        p.vx = vx;
        p.vy = vy;
        p.vz = vz;
        p.friction = 0.91f;
        p.gravity = 0.1f;
        p.r = (rgb >> 16 & 255) / 255f;
        p.g = (rgb >> 8 & 255) / 255f;
        p.b = (rgb & 255) / 255f;
        p.size = 0.1f * (random.nextFloat() * 0.5f + 0.5f) * 2f * 0.75f;
        p.lifetime = 24 + random.nextInt(12);
    }

    // a totem of undying burst: green and gold sparks thrown up and out around a point
    void totem(float x, float y, float z, int count) {
        for (int i = 0; i < count; i++) {
            float a = random.nextFloat() * (float) Math.PI * 2, s = 0.15f + random.nextFloat() * 0.25f;
            Particle p = add(SPARK, true, x + gauss() * 0.2f, y + gauss() * 0.4f, z + gauss() * 0.2f);
            p.vx = (float) Math.cos(a) * s;
            p.vy = 0.1f + random.nextFloat() * 0.35f;
            p.vz = (float) Math.sin(a) * s;
            p.friction = 0.6f;
            p.gravity = 1.25f;
            if (random.nextInt(4) == 0) {
                p.r = 0.6f + random.nextFloat() * 0.2f;
                p.g = 0.6f + random.nextFloat() * 0.3f;
                p.b = random.nextFloat() * 0.2f;
            } else {
                p.r = 0.1f + random.nextFloat() * 0.2f;
                p.g = 0.4f + random.nextFloat() * 0.3f;
                p.b = random.nextFloat() * 0.2f;
            }
            p.size = 0.1f * (random.nextFloat() * 0.5f + 0.5f) * 2f * 0.75f;
            p.lifetime = 20 + random.nextInt(12);
        }
    }

    // a bead of sweat that forms on the side of a moving head, runs down it, then drips off and splashes; side is
    // the way it gets flung, in blocks a tick
    void sweat(Vector3f anchor, float sx, float sz) {
        Particle p = add(new Identifier[]{HANG}, false, anchor.x, anchor.y, anchor.z);
        p.anchor = anchor;
        p.hold = 8 + random.nextInt(6);
        p.vx = sx;
        p.vz = sz;
        p.gravity = 0.06f * 10f;
        p.r = 0.2f;
        p.g = 0.3f;
        p.b = 1f;
        p.size = 0.045f + random.nextFloat() * 0.015f;
        p.lifetime = 60;
        p.splashes = true;
        p.popIn = true;
    }

    // a bubble rising slowly and bursting at the end
    void bubble(float x, float y, float z, float scale) {
        Particle p = add(new Identifier[]{BUBBLE}, false, x, y, z);
        p.vy = 0.012f;
        p.vx = gauss() * 0.004f;
        p.friction = 0.97f;
        p.gravity = -0.02f;
        p.size = 0.1f * scale;
        p.lifetime = 30 + random.nextInt(10);
        p.pops = true;
    }

    // a crumb of a block, a quarter of its texture, the way breaking and sprinting throw them
    void terrain(Identifier texture, float x, float y, float z, float vx, float vy, float vz, int tint) {
        Particle p = add(new Identifier[]{texture}, false, x, y, z);
        float uo = random.nextFloat() * 3f, vo = random.nextFloat() * 3f;
        p.u0 = (uo + 1f) / 4f;
        p.u1 = uo / 4f;
        p.v0 = vo / 4f;
        p.v1 = (vo + 1f) / 4f;
        p.vx = vx;
        p.vy = vy;
        p.vz = vz;
        p.gravity = 1f;
        p.r = 0.6f * (tint >> 16 & 255) / 255f;
        p.g = 0.6f * (tint >> 8 & 255) / 255f;
        p.b = 0.6f * (tint & 255) / 255f;
        p.size = 0.1f * (random.nextFloat() * 0.5f + 0.5f);
        p.lifetime = (int) (4.0f / (random.nextFloat() * 0.9f + 0.1f)) + 8;
        p.ground = true;
    }

    // an item sprite tossed up and caught by gravity, like a dropped emerald
    void item(Identifier texture, float x, float y, float z, float vy, int lifetime, float size) {
        Particle p = add(new Identifier[]{texture}, false, x, y, z);
        p.vy = vy;
        p.gravity = 0.4f;
        p.friction = 0.95f;
        p.size = size;
        p.lifetime = lifetime;
        p.popIn = true;
        p.shrink = true;
    }

    // one character of minecraft's font with its drop shadow, floating up: !, ? and the sleeper's z
    void glyph(char c, int rgb, float x, float y, float z, float vx, float vy, int lifetime, float size) {
        Particle p = add(new Identifier[]{FONT}, false, x, y, z);
        int col = c % 16, row = c / 16;
        // glyphs sit at the left of their 8 pixel cell; shift the window so the character is centred
        float shift = (8 - (c == '!' ? 1 : 5)) / 2f / 128f;
        p.u0 = col / 16f - shift;
        p.v0 = row / 16f;
        p.u1 = (col + 1) / 16f - shift;
        p.v1 = (row + 1) / 16f;
        p.vx = vx;
        p.vy = vy;
        p.friction = 0.92f;
        p.r = (rgb >> 16 & 255) / 255f;
        p.g = (rgb >> 8 & 255) / 255f;
        p.b = (rgb & 255) / 255f;
        p.size = size;
        p.lifetime = lifetime;
        p.popIn = true;
        p.shrink = true;
        p.shadow = 0.25f;
    }

    // -- engine ----------------------------------------------------------------------------------------------

    void step(float dt) {
        pending += Math.min(dt, 0.25f);
        while (pending >= TICK) {
            pending -= TICK;
            tick();
        }
    }

    void clear() {
        live.clear();
    }

    private void tick() {
        List<Particle> born = null;
        for (int i = live.size() - 1; i >= 0; i--) {
            Particle p = live.get(i);
            p.px = p.x;
            p.py = p.y;
            p.pz = p.z;
            if (p.age++ >= p.lifetime) {
                live.remove(i);
                if (p.pops) {
                    if (born == null) born = new ArrayList<>();
                    Particle pop = new Particle();
                    pop.frames = BUBBLE_POP;
                    pop.byAge = true;
                    pop.x = pop.px = p.x;
                    pop.y = pop.py = p.y;
                    pop.z = pop.pz = p.z;
                    pop.size = p.size;
                    pop.lifetime = 4;
                    born.add(pop);
                }
                continue;
            }
            if (p.anchor != null) {
                // clinging: slide down the head a little each tick, then let go
                p.oy -= 0.008f;
                p.x = p.anchor.x + p.ox;
                p.y = p.anchor.y + p.oy;
                p.z = p.anchor.z + p.oz;
                if (--p.hold <= 0) {
                    p.px = p.x;
                    p.py = p.y;
                    p.pz = p.z;
                    p.anchor = null;
                    p.frames = new Identifier[]{DRIP};
                }
                continue;
            }
            p.vy -= 0.04f * p.gravity;
            p.x += p.vx;
            p.y += p.vy;
            p.z += p.vz;
            if (p.splashes && p.y < 0.02f) {
                live.remove(i);
                if (born == null) born = new ArrayList<>();
                Particle splash = new Particle();
                splash.frames = SPLASH;
                splash.byAge = true;
                splash.x = splash.px = p.x;
                splash.y = splash.py = 0.05f;
                splash.z = splash.pz = p.z;
                splash.size = p.size;
                splash.lifetime = 6;
                born.add(splash);
                continue;
            }
            if (p.ground && p.y < 0.02f) {
                p.y = 0.02f;
                p.vy = 0f;
                p.vx *= 0.7f;
                p.vz *= 0.7f;
            }
            p.vx *= p.friction;
            p.vy *= p.friction;
            p.vz *= p.friction;
        }
        if (born != null) live.addAll(born);
    }

    // camera-facing quads into each sprite's buffer; local maps farm blocks to the buffer's space and right / up
    // are the camera's axes in that space
    void build(Function<Identifier, SceneClone.Buffer> buffers, Matrix4f local, Vector3f right, Vector3f up) {
        float partial = pending / TICK;
        Vector3f c = new Vector3f();
        Vector3f toward = new Vector3f(right).cross(up).normalize(0.01f);
        for (Particle p : live) {
            float t = (p.age + partial) / Math.max(1, p.lifetime);
            float size = p.size;
            if (p.popIn) size *= Math.min(1f, t * 32f);
            if (p.shrink && t > 0.75f) size *= Math.max(0f, (1f - t) / 0.25f);
            if (size <= 0.002f) continue;
            Identifier frame = p.byAge ? p.frames[Math.min(p.frames.length - 1, (int) (t * p.frames.length))] : p.frames[0];
            if (p.anchor != null) local.transformPosition(p.anchor.x + p.ox, p.anchor.y + p.oy, p.anchor.z + p.oz, c);
            else local.transformPosition(lerp(p.px, p.x, partial), lerp(p.py, p.y, partial), lerp(p.pz, p.z, partial), c);
            SceneClone.Buffer out = buffers.apply(frame);
            if (p.shadow >= 0f) {
                Vector3f s = new Vector3f(c).fma(size / 8f, right).fma(-size / 8f, up).sub(toward);
                quad(out, s, right, up, size, p, p.r * p.shadow, p.g * p.shadow, p.b * p.shadow);
            }
            quad(out, c, right, up, size, p, p.r, p.g, p.b);
        }
    }

    private static void quad(SceneClone.Buffer out, Vector3f c, Vector3f right, Vector3f up, float s, Particle p,
                             float r, float g, float b) {
        int rgb = Math.round(Math.min(1f, r) * 255) << 16 | Math.round(Math.min(1f, g) * 255) << 8
                | Math.round(Math.min(1f, b) * 255);
        int color = SceneClone.rgba(rgb, 1f, 255);
        float rx = right.x * s, ry = right.y * s, rz = right.z * s, ux = up.x * s, uy = up.y * s, uz = up.z * s;
        out.vertex(c.x - rx + ux, c.y - ry + uy, c.z - rz + uz, p.u0, p.v0, color);
        out.vertex(c.x + rx + ux, c.y + ry + uy, c.z + rz + uz, p.u1, p.v0, color);
        out.vertex(c.x + rx - ux, c.y + ry - uy, c.z + rz - uz, p.u1, p.v1, color);
        out.vertex(c.x - rx + ux, c.y - ry + uy, c.z - rz + uz, p.u0, p.v0, color);
        out.vertex(c.x + rx - ux, c.y + ry - uy, c.z + rz - uz, p.u1, p.v1, color);
        out.vertex(c.x - rx - ux, c.y - ry - uy, c.z - rz - uz, p.u0, p.v1, color);
    }

    private Particle add(Identifier[] frames, boolean byAge, float x, float y, float z) {
        Particle p = new Particle();
        p.frames = frames;
        p.byAge = byAge;
        p.x = p.px = x;
        p.y = p.py = y;
        p.z = p.pz = z;
        live.add(p);
        return p;
    }

    private float gauss() {
        return (random.nextFloat() - 0.5f) * 2f;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static Identifier particle(String name) {
        return Identifier.withDefaultNamespace("textures/particle/" + name + ".png");
    }

    private static Identifier[] frames(String prefix, int from, int to) {
        int n = Math.abs(to - from) + 1, step = to >= from ? 1 : -1;
        Identifier[] out = new Identifier[n];
        for (int i = 0; i < n; i++) out[i] = particle(prefix + (from + i * step));
        return out;
    }
}
