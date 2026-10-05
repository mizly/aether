package dev.aether.ui.orbit;

import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.function.Function;

// the farming-side skits: the macro hoeing a row of wheat that grows back, rewarp running the row and warping home,
// fishing in the water lane, filling a composter and spraying the crops. same farm space as SceneActors: +z up the
// path, the yard at +x, the potato field's water lane at x -7
final class FarmSkits {
    private static final Identifier FARMLAND = mc("textures/block/farmland_moist.png");
    private static final Identifier[] WHEAT = new Identifier[8];
    private static final Identifier WHEAT_ITEM = mc("textures/item/wheat.png");
    private static final Identifier HOE = mc("textures/item/diamond_hoe.png");
    private static final Identifier ROD = mc("textures/item/fishing_rod.png");
    private static final Identifier ROD_CAST = mc("textures/item/fishing_rod_cast.png");
    private static final Identifier BOBBER = mc("textures/entity/fishing/fishing_hook.png");
    private static final Identifier LINE = mc("textures/block/black_concrete.png");
    private static final Identifier[] FISH = {mc("textures/item/cod.png"), mc("textures/item/salmon.png"),
            mc("textures/item/tropical_fish.png")};
    private static final Identifier START_PAD = mc("textures/block/lime_stained_glass.png");
    private static final Identifier END_PAD = mc("textures/block/red_stained_glass.png");
    private static final Identifier COMPOSTER_SIDE = mc("textures/block/composter_side.png");
    private static final Identifier COMPOSTER_TOP = mc("textures/block/composter_top.png");
    private static final Identifier COMPOSTER_BOTTOM = mc("textures/block/composter_bottom.png");
    private static final Identifier COMPOST = mc("textures/block/composter_compost.png");
    private static final Identifier COMPOST_READY = mc("textures/block/composter_ready.png");
    private static final Identifier[] SCRAPS = {mc("textures/item/wheat.png"), mc("textures/item/carrot.png"),
            mc("textures/item/potato.png"), mc("textures/item/melon_slice.png")};
    private static final Identifier BONE_MEAL = mc("textures/item/bone_meal.png");
    private static final Identifier SPRAYER = mc("textures/item/glass_bottle.png");
    private static final int GRASS_TINT = 0x91BD59;

    static {
        for (int i = 0; i < 8; i++) WHEAT[i] = mc("textures/block/wheat_stage" + i + ".png");
    }

    // the strip of wheat in front of you: five crops across the path, each growing back once cut
    private static final float STRIP_Z = 1.6f;
    private static final float[] CROP_X = {-2f, -1f, 0f, 1f, 2f};
    private final float[] growth = {1f, 1f, 1f, 1f, 1f};
    private final float[] sprayed = new float[5];
    private final SceneParticles particles;

    private String focus;
    private float scene;
    private float time;
    private float lastX;
    private float swing = 9f;
    // fishing
    private final Vector3f bobber = new Vector3f();
    private final Vector3f rodTip = new Vector3f();
    private boolean bobberOut;
    private int fish;
    private float caught = -1f;
    private final Vector3f caughtFrom = new Vector3f();
    // composting
    private int level;
    private float tossed = -1f;
    private int scrap;
    private float ready = -1f;
    private float boneMeal = -1f;
    private float sprayTimer, rodTimer;

    FarmSkits(SceneParticles particles) {
        this.particles = particles;
    }

    static boolean handles(String focus) {
        return switch (focus == null ? "" : focus) {
            case "Farming Macro", "Rewarp", "Strider Fishing", "Auto Composter", "Auto Sprayonator" -> true;
            default -> false;
        };
    }

    void update(float dt, float scene, float time, String focus, boolean enabled, PlayerFigure.Pose pose) {
        if (focus == null ? this.focus != null : !focus.equals(this.focus)) {
            this.focus = focus;
            java.util.Arrays.fill(growth, 1f);
            java.util.Arrays.fill(sprayed, 0f);
            bobberOut = false;
            caught = -1f;
            level = 0;
            tossed = ready = boneMeal = -1f;
            swing = 9f;
            lastX = 0f;
        }
        this.scene = scene;
        this.time = time;
        for (int i = 0; i < 5; i++) {
            if (growth[i] < 1f) {
                float before = growth[i];
                growth[i] = Math.min(1f, growth[i] + dt / 2.6f);
                if (before < 1f && growth[i] >= 1f) particles.happy(CROP_X[i], 0.6f, STRIP_Z, 3, 0.25f);
            }
            sprayed[i] = Math.max(0f, sprayed[i] - dt * 0.4f);
        }
        if (focus == null) return;
        switch (focus) {
            case "Farming Macro" -> farm(dt, pose);
            case "Rewarp" -> rewarp(dt, pose);
            case "Strider Fishing" -> fishing(dt, pose);
            case "Auto Composter" -> compost(dt, pose);
            case "Auto Sprayonator" -> spray(dt, pose);
            default -> {
            }
        }
    }

    // -- farming: strafe the row, hoe swinging, cutting every ripe crop you pass ---------------------------------

    private void farm(float dt, PlayerFigure.Pose pose) {
        float x = 2.1f * (float) Math.sin(scene * Math.PI * 2 / 6.5);
        strafe(dt, pose, x);
        harvest(pose, x);
    }

    // sideways steps along the row, leaning into the direction of travel
    private void strafe(float dt, PlayerFigure.Pose pose, float x) {
        float v = dt > 0f ? (x - lastX) / dt : 0f;
        lastX = x;
        pose.x = x;
        pose.facing = 0f;
        float step = x * 5.5f;
        pose.legs = (float) Math.sin(step) * 22f * Math.min(1f, Math.abs(v));
        pose.legsWeight = 1f;
        pose.roll = -v * 5f;
        pose.y = Math.abs((float) Math.sin(step)) * 0.05f * Math.min(1f, Math.abs(v));
        pose.headPitch = 24f;
        pose.look = 0.2f;
        pose.left = -20f;
        pose.leftWeight = 1f;
        swing += dt;
        float k = swing / 0.28f;
        // the hoe lifts and chops down through each crop
        pose.right = -45f - (k < 1f ? 80f * (float) Math.sin(Math.PI * k) : 0f);
        pose.rightWeight = 1f;
    }

    private void harvest(PlayerFigure.Pose pose, float x) {
        for (int i = 0; i < 5; i++) {
            if (growth[i] < 1f || Math.abs(x - CROP_X[i]) > 0.25f) continue;
            growth[i] = 0f;
            swing = 0f;
            for (int k = 0; k < 8; k++) {
                particles.terrain(WHEAT[7], CROP_X[i] + (float) (Math.random() - 0.5) * 0.5f, 0.3f + (float) Math.random() * 0.4f,
                        STRIP_Z + (float) (Math.random() - 0.5) * 0.4f, (float) (Math.random() - 0.5) * 0.12f,
                        0.12f + (float) Math.random() * 0.08f, (float) (Math.random() - 0.5) * 0.12f, 0xFFFFFF);
            }
            particles.item(WHEAT_ITEM, CROP_X[i], 0.5f, STRIP_Z, 0.2f, 18, 0.2f);
        }
    }

    // -- rewarp: farm the row to the red pad, warp out in a swirl of portal, land back on the green one -----------

    private static final float START_X = -2.7f, END_X = 2.8f;
    private static final float RUN = 3.6f;

    private void rewarp(float dt, PlayerFigure.Pose pose) {
        float c = scene % 6.2f;
        if (c < RUN) {
            float k = c / RUN;
            float x = START_X + (END_X - START_X) * (k * 0.85f + smooth(k) * 0.15f);
            strafe(dt, pose, x);
            harvest(pose, x);
            if (c < 0.3f) pose.squash = 0.12f * (1f - c / 0.3f);
            return;
        }
        if (c < 4.5f) {
            // on the end pad: a glance up, then the swirl pulls you in
            float k = (c - RUN) / 0.9f;
            lastX = END_X;
            pose.x = END_X;
            pose.headPitch = -20f * smooth(k);
            pose.right = pose.left = -30f - 140f * smooth(clamp01((k - 0.4f) / 0.6f));
            pose.rightWeight = pose.leftWeight = 1f;
            pose.vanish = smooth(clamp01((k - 0.55f) / 0.45f));
            pose.squint = 1f;
            if (Math.random() < 0.8) {
                double a = Math.random() * Math.PI * 2;
                float r = 0.9f + (float) Math.random() * 0.6f;
                particles.portal(END_X + (float) Math.cos(a) * r, 0.2f + (float) Math.random() * 1.8f,
                        (float) Math.sin(a) * r, END_X, 1f, 0f, false);
            }
            if (c - dt < 4.45f && c >= 4.45f) particles.poof(END_X, 1f, 0f, 8, 0.3f);
            return;
        }
        if (c < 4.9f) {
            // gone; the start pad lights up
            pose.vanish = 1f;
            pose.x = START_X;
            lastX = START_X;
            if (Math.random() < 0.6) particles.endRod(START_X + (float) (Math.random() - 0.5) * 0.8f, 0.1f,
                    (float) (Math.random() - 0.5) * 0.8f);
            return;
        }
        // flung back out of a reverse portal onto the start pad, landing with a bounce
        float k = clamp01((c - 4.9f) / 0.5f);
        pose.x = START_X;
        lastX = START_X;
        pose.vanish = 1f - smooth(k);
        pose.y = (float) Math.sin(Math.PI * k) * 0.4f;
        pose.right = pose.left = -150f * (1f - k);
        pose.rightWeight = pose.leftWeight = 1f;
        if (c - dt < 4.9f) {
            for (int i = 0; i < 40; i++) {
                double a = Math.random() * Math.PI * 2;
                float r = 0.8f + (float) Math.random() * 0.8f;
                particles.portal(START_X + (float) Math.cos(a) * r, 0.2f + (float) Math.random() * 1.8f,
                        (float) Math.sin(a) * r, START_X, 1f, 0f, true);
            }
            particles.poof(START_X, 0.8f, 0f, 10, 0.3f);
        }
    }

    // -- fishing: cast into the water lane, wait for the bite, yank a fish out -------------------------------------

    private static final Vector3f WATER = new Vector3f(-6.8f, -0.05f, 0.6f);
    private static final float CAST_AT = 1.0f, LANDS = 2.0f, BITE = 4.6f, CATCH = 5.0f, LOOP = 7.2f;

    private void fishing(float dt, PlayerFigure.Pose pose) {
        float c = scene % LOOP;
        pose.facing = -90f;
        pose.look = 0.3f;
        pose.headPitch = 8f;
        if (c < 0.6f) {
            bobberOut = false;
            pose.right = -40f;
            pose.rightWeight = 1f;
        } else if (c < CAST_AT) {
            // wind up over the shoulder
            float k = (c - 0.6f) / 0.4f;
            pose.right = -40f - 150f * smooth(k);
            pose.rightWeight = 1f;
            pose.lean = -10f * smooth(k);
            pose.squash = 0.08f * smooth(k);
        } else if (c < LANDS) {
            // the cast: the arm whips forward and the bobber sails out
            float k = clamp01((c - CAST_AT) / 0.18f);
            pose.right = -190f + 150f * smooth(k);
            pose.rightWeight = 1f;
            pose.lean = 12f * smooth(k);
            float f = clamp01((c - CAST_AT) / (LANDS - CAST_AT));
            bobberOut = true;
            bobber.set(rodTip).lerp(WATER, f);
            bobber.y += (float) Math.sin(Math.PI * f) * 2.2f;
            if (f >= 1f) bobber.set(WATER);
        } else if (c < BITE) {
            if (c - dt < LANDS) {
                particles.splash(WATER.x, 0f, WATER.z, 8, 0.15f);
                fish = (int) (Math.random() * FISH.length);
            }
            pose.right = -55f;
            pose.rightWeight = 1f;
            bobber.set(WATER).add(0f, (float) Math.sin(time * 3) * 0.03f, 0f);
            // a fish's wake curling in toward the bobber
            if (c > BITE - 1.6f) {
                float k = (c - (BITE - 1.6f)) / 1.6f;
                double a = 1.2 + k * 2.0;
                float r = 1.8f * (1f - k);
                rodTimer -= dt;
                if (rodTimer <= 0f) {
                    rodTimer = 0.05f;
                    particles.splash(WATER.x + (float) Math.cos(a) * r, 0f, WATER.z + (float) Math.sin(a) * r, 1, 0.02f);
                }
            }
        } else if (c < CATCH) {
            // the bite: the bobber plunges and you yank
            float k = (c - BITE) / (CATCH - BITE);
            if (c - dt < BITE) {
                particles.splash(WATER.x, 0f, WATER.z, 14, 0.2f);
                particles.bubble(WATER.x, 0.05f, WATER.z, 1.5f);
                particles.glyph('!', 0xFF5555, 0f, 2.3f, 0f, 0f, 0.03f, 20, 0.3f);
            }
            bobber.set(WATER).add(0f, -0.25f * (float) Math.sin(Math.PI * Math.min(1f, k * 2f)), 0f);
            pose.right = -55f - 115f * smooth(clamp01((k - 0.3f) / 0.7f));
            pose.rightWeight = 1f;
            pose.lean = -16f * smooth(k);
            pose.squash = k < 0.3f ? 0.15f : 0f;
        } else {
            // the fish arcs up out of the water to your hands; you hold it up, then stow it
            if (c - dt < CATCH) {
                caught = 0f;
                caughtFrom.set(WATER);
                bobberOut = false;
                particles.splash(WATER.x, 0f, WATER.z, 10, 0.2f);
            }
            float k = clamp01((c - CATCH) / 0.7f);
            pose.right = -170f + 60f * k;
            pose.rightWeight = 1f;
            pose.left = -150f * smooth(clamp01((c - CATCH - 0.6f) / 0.3f)) * (1f - smooth(clamp01((c - 6.6f) / 0.4f)));
            pose.leftWeight = 1f;
            pose.headPitch = -15f;
            if (c - dt < CATCH + 0.7f && c >= CATCH + 0.7f) particles.happy(0f, 2f, 0f, 6, 0.4f);
            if (c - dt < 6.8f && c >= 6.8f) {
                particles.poof(-0.3f, 1.9f, 0f, 4, 0.1f);
                caught = -1f;
            }
        }
        if (caught >= 0f) caught += dt;
    }

    // -- composting: a composter drops in, you toss scraps in until it fills, bone meal pops out ------------------

    private static final Vector3f BIN = new Vector3f(1.7f, 0f, 1.5f);

    private void compost(float dt, PlayerFigure.Pose pose) {
        float face = (float) Math.toDegrees(Math.atan2(BIN.x, BIN.z));
        pose.facing = face;
        pose.look = 0.3f;
        pose.headPitch = 15f;
        if (scene - dt < 0.35f && scene >= 0.35f) particles.poof(BIN.x, 0.3f, BIN.z, 10, 0.5f);
        if (scene < 0.7f) return;
        if (ready >= 0f) {
            ready += dt;
            if (ready > 0.6f && boneMeal < 0f) {
                boneMeal = 0f;
                particles.poof(BIN.x, 1.1f, BIN.z, 6, 0.2f);
                level = 0;
            }
            if (boneMeal >= 0f) {
                boneMeal += dt;
                float k = clamp01(boneMeal / 0.6f);
                // catch it and cheer
                pose.right = pose.left = -150f * smooth(k);
                pose.rightWeight = pose.leftWeight = 1f;
                if (boneMeal > 0.6f) {
                    float h = clamp01((boneMeal - 0.6f) / 0.5f);
                    pose.y = (float) Math.sin(Math.PI * h) * 0.3f;
                    pose.squash = boneMeal < 0.7f ? 0.2f : 0f;
                }
                if (boneMeal > 1.3f) {
                    ready = boneMeal = -1f;
                    particles.happy(0f, 1.8f, 0f, 6, 0.4f);
                }
            }
            return;
        }
        float beat = (scene - 0.7f) % 0.9f;
        float k = beat / 0.9f;
        // wind back, then lob the scrap underarm
        pose.right = k < 0.35f ? 30f * smooth(k / 0.35f) : 30f - 120f * smooth(clamp01((k - 0.35f) / 0.25f));
        pose.rightWeight = 1f;
        pose.lean = k < 0.35f ? -4f : 8f * (1f - k);
        if (tossed < 0f && k >= 0.5f) {
            tossed = 0f;
            scrap = (int) (Math.random() * SCRAPS.length);
        }
        if (tossed >= 0f) {
            tossed += dt;
            if (tossed >= 0.45f) {
                tossed = -1f;
                level++;
                particles.happy(BIN.x, 1f, BIN.z, 2, 0.25f);
                for (int i = 0; i < 4; i++) {
                    particles.terrain(COMPOST, BIN.x, 1f, BIN.z, (float) (Math.random() - 0.5) * 0.1f, 0.12f,
                            (float) (Math.random() - 0.5) * 0.1f, 0xFFFFFF);
                }
                if (level >= 7) {
                    ready = 0f;
                    particles.happy(BIN.x, 1.2f, BIN.z, 7, 0.5f);
                }
            }
        }
    }

    // -- spraying: walk the row with the sprayer out, misting each crop green -----------------------------------

    private void spray(float dt, PlayerFigure.Pose pose) {
        float x = 2.1f * (float) Math.sin(scene * Math.PI * 2 / 8);
        strafe(dt, pose, x);
        pose.right = -70f + (float) Math.sin(time * 18) * 3f;
        pose.rightWeight = 1f;
        sprayTimer -= dt;
        if (sprayTimer <= 0f) {
            sprayTimer = 0.03f;
            float hx = x - 0.34f, hy = 1.05f, hz = 0.8f;
            particles.spell(hx, hy, hz, (float) (Math.random() - 0.5) * 0.04f, -0.02f - (float) Math.random() * 0.03f,
                    0.05f + (float) Math.random() * 0.04f, 0x7FE36A);
        }
        for (int i = 0; i < 5; i++) {
            if (Math.abs(x - 0.34f - CROP_X[i]) < 0.3f && sprayed[i] < 0.6f) {
                sprayed[i] = 1f;
                particles.happy(CROP_X[i], 0.7f, STRIP_Z, 5, 0.3f);
            }
        }
    }

    // -- geometry -----------------------------------------------------------------------------------------------

    void build(Matrix4f local, PlayerFigure figure, Function<Identifier, SceneClone.Buffer> buffer, Vector3f right,
               Vector3f up) {
        if (!handles(focus)) return;
        Matrix4f toFarm = new Matrix4f(local).invert();
        boolean strip = "Farming Macro".equals(focus) || "Rewarp".equals(focus) || "Auto Sprayonator".equals(focus);
        if (strip) {
            for (int i = 0; i < 5; i++) crop(buffer, local, CROP_X[i], STRIP_Z, growth[i]);
        }
        if ("Rewarp".equals(focus)) {
            pad(buffer.apply(START_PAD), local, START_X);
            pad(buffer.apply(END_PAD), local, END_X);
        }
        Matrix4f arm = figure.rightArmFrame();
        switch (focus) {
            case "Farming Macro", "Rewarp" -> held(buffer.apply(HOE), arm, 1f);
            case "Auto Sprayonator" -> held(buffer.apply(SPRAYER), arm, 0.7f);
            case "Strider Fishing" -> {
                held(buffer.apply(bobberOut ? ROD_CAST : ROD), arm, 1.1f);
                toFarm.transformPosition(arm.transformPosition(0f, -2f, 10.5f, rodTip));
                if (bobberOut) {
                    line(buffer.apply(LINE), local, rodTip, bobber, right, up);
                    billboard(buffer.apply(BOBBER), local, bobber.x, bobber.y + 0.08f, bobber.z, 0.12f, right, up);
                }
                if (caught >= 0f) {
                    float k = clamp01(caught / 0.7f);
                    Vector3f hand = toFarm.transformPosition(arm.transformPosition(0f, -11f, 0f, new Vector3f()));
                    Vector3f at = new Vector3f(caughtFrom).lerp(hand, smooth(k));
                    at.y += (float) Math.sin(Math.PI * k) * 1.6f + 0.15f;
                    float wiggle = (float) Math.sin(time * 30) * 0.06f * (1f - k * 0.5f);
                    billboard(buffer.apply(FISH[fish]), local, at.x + wiggle, at.y, at.z, 0.28f, right, up);
                }
            }
            case "Auto Composter" -> {
                composter(buffer, local);
                if (tossed >= 0f) {
                    float k = clamp01(tossed / 0.45f);
                    Vector3f hand = toFarm.transformPosition(arm.transformPosition(0f, -11f, 0f, new Vector3f()));
                    Vector3f at = new Vector3f(hand).lerp(new Vector3f(BIN.x, 1.0f, BIN.z), k);
                    at.y += (float) Math.sin(Math.PI * k) * 0.7f;
                    billboard(buffer.apply(SCRAPS[scrap]), local, at.x, at.y, at.z, 0.2f, right, up);
                }
                if (boneMeal >= 0f && boneMeal < 0.6f) {
                    float k = clamp01(boneMeal / 0.6f);
                    Vector3f at = new Vector3f(BIN.x, 1.1f, BIN.z).lerp(new Vector3f(0f, 1.9f, 0.2f), k);
                    at.y += (float) Math.sin(Math.PI * k) * 0.8f;
                    billboard(buffer.apply(BONE_MEAL), local, at.x, at.y, at.z, 0.24f, right, up);
                }
            }
            default -> {
            }
        }
    }

    // a crop on its farmland: the moist soil flat on the ground, the wheat at its stage as two crossed planes
    private void crop(Function<Identifier, SceneClone.Buffer> buffer, Matrix4f local, float x, float z, float grown) {
        Matrix4f m = new Matrix4f(local).translate(x - 0.5f, 0.01f, z - 0.5f);
        SceneActors.face(buffer.apply(FARMLAND), m, 0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1, 1f, 1f, 1f);
        int stage = Math.min(7, (int) (grown * 7.999f));
        float pop = grown >= 1f ? 1f : 0.9f + 0.1f * (float) Math.sin(time * 6 + x);
        Matrix4f c = new Matrix4f(m).translate(0.5f, 0f, 0.5f).scale(1f, pop, 1f).translate(-0.5f, 0f, -0.5f);
        SceneClone.Buffer out = buffer.apply(WHEAT[stage]);
        // vanilla crops are four planes in a hash; two crossed ones read the same from this far
        SceneActors.face(out, c, 0.1f, 1, 0.1f, 0.9f, 1, 0.9f, 0.9f, 0, 0.9f, 0.1f, 0, 0.1f, 1f, 1f, 1f);
        SceneActors.face(out, c, 0.9f, 1, 0.1f, 0.1f, 1, 0.9f, 0.1f, 0, 0.9f, 0.9f, 0, 0.1f, 1f, 1f, 0.9f);
    }

    // a glowing pad under a rewarp point, pulsing gently
    private void pad(SceneClone.Buffer out, Matrix4f local, float x) {
        float pulse = 1f + (float) Math.sin(time * 4) * 0.04f;
        Matrix4f m = new Matrix4f(local).translate(x, 0.02f, 0f).scale(pulse, 1f, pulse).translate(-0.5f, 0f, -0.5f);
        SceneActors.face(out, m, 0, 0.06f, 0, 1, 0.06f, 0, 1, 0.06f, 1, 0, 0.06f, 1, 1f, 1f, 1f);
        SceneActors.face(out, m, 0, 0.06f, 0, 1, 0.06f, 0, 1, 0, 0, 0, 0, 0, 1f, 0.06f, 0.8f);
        SceneActors.face(out, m, 1, 0.06f, 1, 0, 0.06f, 1, 0, 0, 1, 1, 0, 1, 1f, 0.06f, 0.8f);
        SceneActors.face(out, m, 0, 0.06f, 1, 0, 0.06f, 0, 0, 0, 0, 0, 0, 1, 1f, 0.06f, 0.6f);
        SceneActors.face(out, m, 1, 0.06f, 0, 1, 0.06f, 1, 1, 0, 1, 1, 0, 0, 1f, 0.06f, 0.6f);
    }

    // an item sprite in the right hand the way third person holds tools: handle in the fist, head up and forward
    static void held(SceneClone.Buffer out, Matrix4f arm, float scale) {
        Matrix4f m = new Matrix4f(arm).translate(0f, -11f, -1.5f).scale(scale).translate(0f, 11f, 1.5f);
        SceneActors.face(out, m, 0f, -1f, -1.5f, 0f, -1f, 9.5f, 0f, -12f, 9.5f, 0f, -12f, -1.5f, 1f, 1f, 1f);
    }

    // the fishing line, sagging from the rod tip to the bobber as a thin strip facing the camera
    private static void line(SceneClone.Buffer out, Matrix4f local, Vector3f from, Vector3f to, Vector3f right, Vector3f up) {
        Vector3f toCam = new Vector3f(right).cross(up).normalize();
        Vector3f prev = null;
        int n = 16;
        float sag = Math.min(1.2f, from.distance(to) * 0.12f);
        for (int i = 0; i <= n; i++) {
            float k = i / (float) n;
            Vector3f p = new Vector3f(from).lerp(to, k);
            p.y -= (float) Math.sin(Math.PI * k) * sag;
            Vector3f w = local.transformPosition(p, new Vector3f());
            if (prev != null) {
                Vector3f dir = new Vector3f(w).sub(prev);
                Vector3f side = dir.cross(toCam, new Vector3f());
                if (side.lengthSquared() < 1e-9f) side.set(right);
                side.normalize(0.012f);
                quad(out, new Vector3f(prev).add(side), new Vector3f(w).add(side), new Vector3f(w).sub(side),
                        new Vector3f(prev).sub(side));
            }
            prev = w;
        }
    }

    private static void quad(SceneClone.Buffer out, Vector3f a, Vector3f b, Vector3f c, Vector3f d) {
        int color = 0xFFFFFFFF;
        out.vertex(a.x, a.y, a.z, 0f, 0f, color);
        out.vertex(b.x, b.y, b.z, 0.1f, 0f, color);
        out.vertex(c.x, c.y, c.z, 0.1f, 0.1f, color);
        out.vertex(a.x, a.y, a.z, 0f, 0f, color);
        out.vertex(c.x, c.y, c.z, 0.1f, 0.1f, color);
        out.vertex(d.x, d.y, d.z, 0f, 0.1f, color);
    }

    // a camera-facing sprite at a farm point
    static void billboard(SceneClone.Buffer out, Matrix4f local, float x, float y, float z, float half,
                                  Vector3f right, Vector3f up) {
        Vector3f c = local.transformPosition(x, y, z, new Vector3f());
        float rx = right.x * half, ry = right.y * half, rz = right.z * half, ux = up.x * half, uy = up.y * half, uz = up.z * half;
        int color = 0xFFFFFFFF;
        out.vertex(c.x - rx + ux, c.y - ry + uy, c.z - rz + uz, 0f, 0f, color);
        out.vertex(c.x + rx + ux, c.y + ry + uy, c.z + rz + uz, 1f, 0f, color);
        out.vertex(c.x + rx - ux, c.y + ry - uy, c.z + rz - uz, 1f, 1f, color);
        out.vertex(c.x - rx + ux, c.y - ry + uy, c.z - rz + uz, 0f, 0f, color);
        out.vertex(c.x + rx - ux, c.y + ry - uy, c.z + rz - uz, 1f, 1f, color);
        out.vertex(c.x - rx - ux, c.y - ry - uy, c.z - rz - uz, 0f, 1f, color);
    }

    // the composter: its slatted sides, a rim with the hole, inner walls and the compost risen to its level
    private void composter(Function<Identifier, SceneClone.Buffer> buffer, Matrix4f local) {
        float drop = Math.min(1f, scene / 0.35f);
        float land = scene > 0.35f && scene < 0.65f ? (float) Math.sin(Math.PI * (scene - 0.35f) / 0.3f) * 0.2f : 0f;
        Matrix4f m = new Matrix4f(local).translate(BIN.x, 3f * (1f - drop * drop), BIN.z)
                .scale(1f + land * 0.5f, 1f - land, 1f + land * 0.5f).translate(-0.5f, 0f, -0.5f);
        SceneClone.Buffer side = buffer.apply(COMPOSTER_SIDE);
        SceneActors.face(side, m, 1, 1, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 1f, 1f, 0.8f);
        SceneActors.face(side, m, 0, 1, 1, 1, 1, 1, 1, 0, 1, 0, 0, 1, 1f, 1f, 0.8f);
        SceneActors.face(side, m, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0, 0, 1f, 1f, 0.6f);
        SceneActors.face(side, m, 1, 1, 1, 1, 1, 0, 1, 0, 0, 1, 0, 1, 1f, 1f, 0.6f);
        float t = 2f / 16f;
        SceneActors.face(side, m, t, 1, 1 - t, 1 - t, 1, 1 - t, 1 - t, t, 1 - t, t, t, 1 - t, 1f, 1f, 0.5f);
        SceneActors.face(side, m, 1 - t, 1, t, t, 1, t, t, t, t, 1 - t, t, t, 1f, 1f, 0.5f);
        SceneActors.face(side, m, t, 1, t, t, 1, 1 - t, t, t, 1 - t, t, t, t, 1f, 1f, 0.45f);
        SceneActors.face(side, m, 1 - t, 1, 1 - t, 1 - t, 1, t, 1 - t, t, t, 1 - t, t, 1 - t, 1f, 1f, 0.45f);
        SceneActors.face(buffer.apply(COMPOSTER_BOTTOM), m, 0, 0, 1, 1, 0, 1, 1, 0, 0, 0, 0, 0, 1f, 1f, 0.5f);
        SceneActors.face(buffer.apply(COMPOSTER_TOP), m, 0, 1, 0, 1, 1, 0, 1, 1, 1, 0, 1, 1, 1f, 1f, 1f);
        float fill = (2f + Math.min(7, level) * 2f) / 16f;
        SceneActors.face(buffer.apply(ready >= 0f ? COMPOST_READY : COMPOST), m, t, fill, t, 1 - t, fill, t, 1 - t, fill,
                1 - t, t, fill, 1 - t, 1f, 1f, 0.9f);
    }

    private static float smooth(float t) {
        t = clamp01(t);
        return t * t * (3 - 2 * t);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static Identifier mc(String path) {
        return Identifier.withDefaultNamespace(path);
    }
}
