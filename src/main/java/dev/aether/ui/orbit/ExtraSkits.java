package dev.aether.ui.orbit;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.function.Function;

// sweeping a metal detector and digging up treasure, the carnival shootout with a bow, and a human fidgeting for
// humanization
final class ExtraSkits {
    private static final Identifier DETECTOR = mc("textures/item/compass_16.png");
    private static final Identifier DIRT = mc("textures/block/dirt.png");
    private static final Identifier[] TREASURE = {mc("textures/item/diamond.png"), mc("textures/item/gold_nugget.png"),
            mc("textures/item/emerald.png")};
    private static final Identifier[] BOW = {mc("textures/item/bow.png"), mc("textures/item/bow_pulling_0.png"),
            mc("textures/item/bow_pulling_1.png"), mc("textures/item/bow_pulling_2.png")};
    private static final Identifier ARROW = mc("textures/item/arrow.png");
    private static final Identifier TARGET_SIDE = mc("textures/block/target_side.png");
    private static final Identifier TARGET_TOP = mc("textures/block/target_top.png");
    // target blocks on the grid out on the lawn at your left, where the menu camera sees them past the panel
    private static final float[] TARGET_X = {2f, 4f, 6f};
    private static final float TARGET_Z = 6f;

    private final SceneParticles particles;
    private String focus;
    private float scene, time;
    // metal detector
    private float beepTimer;
    private int treasure;
    // shootout
    private int target = 1;
    private float arrow = -1f, targetHit = -1f;
    private final Vector3f bowAt = new Vector3f();
    private float clouds;

    ExtraSkits(SceneParticles particles) {
        this.particles = particles;
    }

    static boolean handles(String focus) {
        return switch (focus == null ? "" : focus) {
            case "Metal Detector", "Auto Carnival (Shootout)", "Humanization" -> true;
            default -> false;
        };
    }

    void update(float dt, float scene, float time, String focus, PlayerFigure.Pose pose) {
        if (focus == null ? this.focus != null : !focus.equals(this.focus)) {
            this.focus = focus;
            arrow = targetHit = -1f;
        }
        this.scene = scene;
        this.time = time;
        if (focus == null) return;
        switch (focus) {
            case "Metal Detector" -> detect(dt, pose);
            case "Auto Carnival (Shootout)" -> shoot(dt, pose);
            case "Humanization" -> fidget(dt, pose);
            default -> {
            }
        }
    }

    // -- metal detector: sweep, the beeps quicken, dig, up comes the treasure ------------------------------------

    private void detect(float dt, PlayerFigure.Pose pose) {
        float c = scene % 6.4f;
        treasure = (int) (scene / 6.4f) % TREASURE.length;
        if (c < 3f) {
            float sweep = (float) Math.sin(c * 3.2f);
            pose.turn = sweep * 28f;
            pose.right = -48f;
            pose.rightWeight = 1f;
            pose.headPitch = 30f;
            pose.look = 0f;
            pose.headYaw = sweep * 20f;
            pose.lean = 10f;
            pose.z = -0.4f + c * 0.15f;
            beepTimer -= dt;
            if (beepTimer <= 0f) {
                // the closer it gets, the faster it beeps
                beepTimer = 0.65f - 0.52f * (c / 3f);
                particles.note(sweep * 0.5f, 0.5f, 1.1f + pose.z);
            }
            if (c - dt < 2.95f && c >= 2.95f) particles.glyph('!', 0xFFFF55, 0f, 2.3f, pose.z, 0f, 0.03f, 20, 0.3f);
        } else if (c < 4.4f) {
            // dig with both hands, dirt flying
            pose.z = 0.05f;
            float k = (c - 3f) * 4f;
            float hit = Math.abs((float) Math.sin(Math.PI * k));
            pose.right = -30f - 90f * hit;
            pose.left = -30f - 90f * Math.abs((float) Math.sin(Math.PI * k + 1.2f));
            pose.rightWeight = pose.leftWeight = 1f;
            pose.lean = 30f;
            pose.squash = 0.12f;
            pose.headPitch = 25f;
            pose.look = 0f;
            if ((int) k != (int) (k - dt * 4f)) {
                for (int i = 0; i < 5; i++) {
                    particles.terrain(DIRT, (float) (Math.random() - 0.5) * 0.5f, 0.1f, 1.05f, (float) (Math.random() - 0.5) * 0.15f,
                            0.18f + (float) Math.random() * 0.1f, (float) Math.random() * 0.08f, 0xFFFFFF);
                }
            }
        } else if (c < 5.3f) {
            // treasure! up it comes, and up you jump
            pose.z = 0.05f;
            float k = (c - 4.4f) / 0.9f;
            if (c - dt < 4.4f) {
                particles.crit(0f, 0.3f, 1.05f, 10, true);
                particles.happy(0f, 0.5f, 1.05f, 6, 0.4f);
            }
            float air = clamp01((k - 0.15f) / 0.6f);
            pose.squash = k < 0.15f ? 0.25f : 0f;
            pose.y = (float) Math.sin(Math.PI * air) * 0.35f;
            pose.right = pose.left = -165f * smooth(k / 0.4f);
            pose.rightWeight = pose.leftWeight = 1f;
            pose.headPitch = -20f;
        } else {
            pose.z = 0.05f * (1f - (c - 5.3f) / 1.1f);
            pose.right = -165f * (1f - smooth((c - 5.3f) / 0.6f));
            pose.rightWeight = 1f;
        }
    }

    // -- carnival shootout: a target pops up down the path, draw, loose, hit ------------------------------------

    private void shoot(float dt, PlayerFigure.Pose pose) {
        float c = scene % 2.4f;
        if (c < dt) {
            target = (target + 1 + (int) (Math.random() * 2)) % TARGET_X.length;
            targetHit = -1f;
            particles.poof(TARGET_X[target], 0.5f, TARGET_Z, 6, 0.4f);
        }
        float tx = TARGET_X[target];
        pose.facing = (float) Math.toDegrees(Math.atan2(tx, TARGET_Z));
        pose.look = 0f;
        if (c < 0.35f) {
            pose.right = -40f;
            pose.rightWeight = 1f;
        } else if (c < 1.2f) {
            // draw: bow arm up and out, the string hand pulling back to the cheek
            float k = (c - 0.35f) / 0.85f;
            pose.right = -90f;
            pose.left = -90f + 30f * smooth(k);
            pose.rightWeight = pose.leftWeight = smooth(k / 0.3f);
            pose.turn = -25f * smooth(k);
            pose.headYaw = 20f * smooth(k);
            pose.squint = k > 0.5f ? 1f : 0f;
            pose.lean = -5f;
        } else {
            if (c - dt < 1.2f) arrow = 0f;
            float k = clamp01((c - 1.2f) / 0.3f);
            pose.right = -90f + 40f * k;
            pose.left = -60f + 30f * k;
            pose.rightWeight = pose.leftWeight = 1f - smooth(clamp01((c - 1.6f) / 0.6f));
            pose.turn = -25f * (1f - k);
            if (targetHit >= 0f && targetHit < 0.5f) {
                // a fist pump for the hit
                pose.left = -160f;
                pose.leftWeight = 1f;
            }
        }
        if (arrow >= 0f) {
            arrow += dt;
            if (arrow >= 0.32f) {
                arrow = -1f;
                targetHit = 0f;
                particles.crit(tx, 0.9f, TARGET_Z - 0.5f, 12, false);
                particles.poof(tx, 0.9f, TARGET_Z - 0.5f, 6, 0.2f);
                particles.happy(tx, 1.5f, TARGET_Z - 0.5f, 5, 0.3f);
            }
        }
        if (targetHit >= 0f) targetHit += dt;
    }

    // -- humanization: someone who isn't a bot, fidgeting --------------------------------------------------------

    private void fidget(float dt, PlayerFigure.Pose pose) {
        float c = scene % 9f;
        if (c < 2f) {
            // a slow look round, pausing on things
            pose.look = 0f;
            pose.headYaw = 55f * (float) Math.sin(c * 1.6f) * smooth(c / 0.4f);
            pose.headPitch = (float) Math.sin(c * 2.3f) * 10f;
        } else if (c < 3.2f) {
            // scratch the back of the head
            pose.right = -150f + (float) Math.sin(c * 22) * 10f;
            pose.rightWeight = smooth((c - 2f) / 0.2f) * (1f - smooth((c - 3f) / 0.2f));
            pose.tilt = 15f;
            pose.squint = 1f;
        } else if (c < 4.6f) {
            // a big stretch and a yawn
            float k = (c - 3.2f) / 1.4f;
            float s = (float) Math.sin(Math.PI * k);
            pose.right = pose.left = -175f;
            pose.rightWeight = pose.leftWeight = s;
            pose.lean = -14f * s;
            pose.headPitch = -25f * s;
            pose.squash = -0.06f * s;
            pose.squint = s > 0.3f ? 1f : 0f;
            clouds -= dt;
            if (s > 0.5f && clouds <= 0f) {
                clouds = 0.08f;
                particles.cloud(0f, 1.62f, 0.3f, 0f, 0.02f, 0.04f, 0.6f);
            }
        } else if (c < 6f) {
            // weight from foot to foot, tapping
            pose.roll = (float) Math.sin(c * 3) * 6f;
            pose.x = (float) Math.sin(c * 3) * 0.04f;
            pose.legs = Math.max(0f, (float) Math.sin(c * 12)) * 12f;
            pose.legsWeight = 1f;
            pose.headPitch = 10f;
        } else if (c < 7.5f) {
            // what time is it
            float k = (c - 6f) / 1.5f;
            float s = smooth(k / 0.2f) * (1f - smooth((k - 0.8f) / 0.2f));
            pose.left = -85f;
            pose.leftWeight = s;
            pose.headPitch = 35f * s;
            pose.headYaw = 20f * s;
            pose.look = 1f - s;
        } else {
            // a shrug
            float s = (float) Math.sin(Math.PI * (c - 7.5f) / 1.5f);
            pose.right = 20f;
            pose.left = 20f;
            pose.rightWeight = pose.leftWeight = s;
            pose.bob = 1.2f * s;
            pose.tilt = 12f * s;
            pose.roll = 4f * s;
        }
    }

    // -- geometry -----------------------------------------------------------------------------------------------

    void build(Matrix4f local, PlayerFigure figure, Function<Identifier, SceneClone.Buffer> buffer, Vector3f right,
               Vector3f up) {
        if (focus == null) return;
        Matrix4f arm = figure.rightArmFrame();
        Matrix4f toFarm = new Matrix4f(local).invert();
        switch (focus) {
            case "Metal Detector" -> {
                float c = scene % 6.4f;
                if (c < 3f || c >= 5.3f) FarmSkits.item(buffer.apply(DETECTOR), arm, 1f);
                if (c >= 4.4f && c < 5.3f) {
                    float k = clamp01((c - 4.4f) / 0.6f);
                    Vector3f at = new Vector3f(0f, 0.2f + 2.2f * (float) Math.sin(Math.PI * 0.5 * k), 1.05f * (1f - k * 0.7f));
                    FarmSkits.billboard(buffer.apply(TREASURE[treasure]), local, at.x, at.y, at.z, 0.22f, right, up);
                } else if (c >= 5.3f && c < 6f) {
                    Vector3f hand = toFarm.transformPosition(arm.transformPosition(0f, -12f, 0f, new Vector3f()));
                    FarmSkits.billboard(buffer.apply(TREASURE[treasure]), local, hand.x, hand.y + 0.15f, hand.z, 0.2f, right, up);
                }
            }
            case "Auto Carnival (Shootout)" -> {
                float c = scene % 2.4f;
                int stage = c < 0.35f || c >= 1.2f ? 0 : 1 + Math.min(2, (int) ((c - 0.35f) / 0.3f));
                FarmSkits.bow(buffer.apply(BOW[stage]), arm, 1f);
                toFarm.transformPosition(arm.transformPosition(0f, -11f, 1f, bowAt));
                for (int i = 0; i < TARGET_X.length; i++) {
                    if (i != target) continue;
                    float pop = Math.min(1f, (scene % 2.4f) / 0.3f);
                    float hit = targetHit >= 0f && targetHit < 0.25f ? (float) Math.sin(Math.PI * targetHit / 0.25f) * 0.2f : 0f;
                    Matrix4f m = new Matrix4f(local).translate(TARGET_X[i], 0f, TARGET_Z).scale(pop * (1f + hit * 0.5f),
                            pop * (1f - hit), pop * (1f + hit * 0.5f)).translate(-0.5f, 0f, -0.5f);
                    if (BlockProps.draw(buffer, m, () -> Blocks.TARGET.defaultBlockState())) continue;
                    SceneClone.Buffer side = buffer.apply(TARGET_SIDE);
                    SceneActors.face(side, m, 1, 1, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 1f, 1f, 0.8f);
                    SceneActors.face(side, m, 0, 1, 1, 1, 1, 1, 1, 0, 1, 0, 0, 1, 1f, 1f, 0.8f);
                    SceneActors.face(side, m, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0, 0, 1f, 1f, 0.6f);
                    SceneActors.face(side, m, 1, 1, 1, 1, 1, 0, 1, 0, 0, 1, 0, 1, 1f, 1f, 0.6f);
                    SceneActors.face(buffer.apply(TARGET_TOP), m, 0, 1, 0, 1, 1, 0, 1, 1, 1, 0, 1, 1, 1f, 1f, 1f);
                }
                if (arrow >= 0f) {
                    float k = clamp01(arrow / 0.32f);
                    Vector3f at = new Vector3f(bowAt).lerp(new Vector3f(TARGET_X[target], 0.9f, TARGET_Z - 0.5f), k);
                    at.y += (float) Math.sin(Math.PI * k) * 0.3f;
                    FarmSkits.billboard(buffer.apply(ARROW), local, at.x, at.y, at.z, 0.22f, right, up);
                    particles.crit(at.x, at.y, at.z, 1, false);
                }
            }
            default -> {
            }
        }
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
