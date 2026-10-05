package dev.aether.ui.orbit;

import dev.aether.renderer.McIcons;
import dev.aether.ui.util.Fonts;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntFunction;

// the farm acts out the module you are looking at, each as a little looping skit in the spirit of minecraft live:
// visitors queue up the path and trade, gold bars weigh you down, a crafting table drops in and you craft, flying
// pests chase you around, a bed lands and you hop in with a nightcap, armour stands show themselves off.
// local space is the farm's, in blocks around your feet, +z up the path
final class SceneActors implements AutoCloseable {
    record Draw(Identifier texture, SceneClone.Buffer buffer) {
    }

    // what the skits read each frame: the focused module by raw page name, whether it is on, and its numbers
    record Inputs(String focus, boolean enabled, int visitors, int pests, double money) {
        static final Inputs NONE = new Inputs(null, false, 0, 0, 0);
    }

    private static final Identifier VILLAGER = mc("textures/entity/villager/villager.png");
    private static final Identifier VILLAGER_PLAINS = mc("textures/entity/villager/type/plains.png");
    private static final Identifier VILLAGER_FARMER = mc("textures/entity/villager/profession/farmer.png");
    private static final Identifier STAND = mc("textures/entity/armorstand/armorstand.png");
    private static final Identifier BED = mc("textures/entity/bed/red.png");
    private static final Identifier TABLE_TOP = mc("textures/block/crafting_table_top.png");
    private static final Identifier TABLE_FRONT = mc("textures/block/crafting_table_front.png");
    private static final Identifier TABLE_SIDE = mc("textures/block/crafting_table_side.png");
    private static final Identifier PLANKS = mc("textures/block/oak_planks.png");
    private static final Identifier GOLD = mc("textures/block/gold_block.png");
    private static final Identifier HAY_TOP = mc("textures/block/hay_block_top.png");
    private static final Identifier HAY_SIDE = mc("textures/block/hay_block_side.png");
    private static final Identifier BEE = mc("textures/entity/bee/bee.png");
    private static final Identifier RED_WOOL = mc("textures/block/red_wool.png");
    private static final Identifier WHITE_WOOL = mc("textures/block/white_wool.png");
    private static final String[] METALS = {"gold", "diamond", "netherite"};

    // drop-ins fall for DROP seconds, then squash on landing for LAND
    private static final float DROP = 0.35f;
    private static final float LAND = 0.3f;

    private enum Kind { PUFF, ZEE, SPARKLE, GLINT, BANG, QUESTION, YAWN, SWEAT, CHIP, HAPPY, BUBBLE, EMERALD, DUST }

    private static final class Particle {
        Kind kind;
        float x, y, z, vx, vy, vz, gravity, drag, age, life, size;
    }

    private enum Step { LOOKING, WALKING, WAITING, LEAVING }

    // a visitor on the conveyor up the path: place 0 trades with you, then walks off and the rest shuffle up
    private static final class Villager {
        int place;
        Step step = Step.LOOKING;
        float age, stepAge, poof = -1f, trade, stride, landed = 9f;
        float x, z, yaw = (float) Math.PI;
    }

    private static final class Pest {
        final int index;
        final Vector3f at = new Vector3f();
        final Vector3f last = new Vector3f();
        boolean shown;
        float shownFor;

        Pest(int index) {
            this.index = index;
        }
    }

    // an enchanted hay bale crafted at the table: it arcs onto the pile, then rests there
    private static final class Crafted {
        float age;
        final float x, z, y;

        Crafted(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private final IntFunction<Identifier> heads;
    private final Map<Identifier, SceneClone.Buffer> buffers = new LinkedHashMap<>();
    private final List<Villager> villagers = new ArrayList<>();
    private final List<Pest> pests = new ArrayList<>();
    private final List<Particle> particles = new ArrayList<>();
    private final List<Crafted> crafted = new ArrayList<>();
    private final Map<Kind, PanelSurface> sprites = new LinkedHashMap<>();
    private boolean drawn;

    private String focus;
    private float scene;
    private float time;
    private Inputs in = Inputs.NONE;
    private float table = -1f;
    private float tableHit;
    private float bed = -1f;
    private float cap;
    private int worn = -1;
    private float spawnCooldown;
    private float[] stands = new float[3];
    private int bars;
    private float strikes;
    private float zeeTimer, sweatTimer, dustTimer, sparkleTimer, snoreTimer, questionTimer, glintTimer;

    SceneActors(IntFunction<Identifier> heads) {
        this.heads = heads;
    }

    // -- the skits ----------------------------------------------------------------------------------------------

    // advances every skit for the focused module and poses the player for it
    void update(float dt, float time, Inputs inputs, PlayerFigure figure) {
        this.time = time;
        this.in = inputs == null ? Inputs.NONE : inputs;
        String now = in.focus();
        if (now == null ? focus != null : !now.equals(focus)) {
            focus = now;
            scene = 0f;
            crafted.clear();
            cap = 0f;
            worn = -1;
        } else {
            scene += dt;
        }
        PlayerFigure.Pose pose = figure.pose;
        pose.reset();
        boolean visitors = "Auto Visitor".equals(focus);
        boolean crafting = "Auto Supercraft".equals(focus);
        boolean pestsOn = "Pest Manager".equals(focus);
        boolean resting = "Dynamic Rest".equals(focus);
        boolean loadout = "Auto Loadout".equals(focus);

        updateVillagers(dt, visitors ? clamp(in.visitors(), 0, 5) : 0, figure);
        bars = visitors && in.money() > 0.05 ? clamp((int) Math.ceil(in.money() / 2.0), 1, 10) : 0;
        if (visitors) gold(dt, pose);

        float shownFor = DROP + LAND;
        table = crafting ? (table < 0f ? 0f : table + dt) : (table < 0f ? -1f : Math.min(table, shownFor) - dt * 3f);
        if (!crafting && table < 0f) table = -1f;
        if (crafting && table - dt < DROP && table >= DROP) burst(0f, 0.2f, 1.3f, 14, Kind.PUFF);
        if (crafting) craft(dt, pose);

        bed = resting ? (bed < 0f ? 0f : bed + dt) : (bed < 0f ? -1f : Math.min(bed, shownFor) - dt * 3f);
        if (!resting && bed < 0f) bed = -1f;
        if (resting && bed - dt < DROP && bed >= DROP) burst(1.8f, 0.2f, -0.6f, 14, Kind.PUFF);
        if (resting) sleep(dt, pose);

        pests(dt, pestsOn ? clamp(in.pests(), 0, 8) : 0, pose);

        if (loadout) {
            if (scene - dt <= 0f) for (int i = 0; i < 3; i++) stands[i] = -0.2f * i;
            for (int i = 0; i < 3; i++) {
                float before = stands[i];
                stands[i] += dt;
                if (before < DROP && stands[i] >= DROP) burst(standSpot(i).x, 0.2f, standSpot(i).z, 12, Kind.PUFF);
            }
            loadout(dt, pose);
        } else {
            for (int i = 0; i < 3; i++) stands[i] = Math.max(0f, Math.min(stands[i], shownFor) - dt * 3f);
        }

        for (int i = particles.size() - 1; i >= 0; i--) {
            Particle p = particles.get(i);
            p.age += dt;
            p.vy -= p.gravity * dt;
            float keep = (float) Math.pow(p.drag, dt);
            p.vx *= keep;
            p.vz *= keep;
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.z += p.vz * dt;
            if (p.age >= p.life) particles.remove(i);
        }
        for (Crafted c : crafted) c.age += dt;
    }

    // visitors pop in down the path, look about and queue up toward you. the one at the front grumbles over your
    // offer, pays an emerald, cheers and wanders off into the field while the rest shuffle up and a new one arrives
    private static final float SPAWN = 10.5f;
    private static final float TRADE = 2.9f;

    private static float queueSpot(int place) {
        return 2.1f + 1.35f * place;
    }

    private void updateVillagers(float dt, int count, PlayerFigure figure) {
        spawnCooldown -= dt;
        long staying = villagers.stream().filter(v -> v.step != Step.LEAVING && v.poof < 0f).count();
        if (staying < count && spawnCooldown <= 0f) {
            Villager v = new Villager();
            v.place = (int) staying;
            v.x = 0f;
            v.z = SPAWN;
            villagers.add(v);
            burst(v.x, 0.9f, v.z, 16, Kind.PUFF);
            spawnCooldown = 0.55f;
        }
        for (int i = villagers.size() - 1; i >= 0 && staying > count; i--) {
            Villager v = villagers.get(i);
            if (v.step == Step.LEAVING || v.poof >= 0f) continue;
            v.poof = 0f;
            burst(v.x, 0.9f, v.z, 12, Kind.PUFF);
            staying--;
        }
        for (int i = villagers.size() - 1; i >= 0; i--) {
            Villager v = villagers.get(i);
            v.age += dt;
            v.stepAge += dt;
            v.landed += dt;
            if (v.poof >= 0f) {
                v.poof += dt;
                if (v.poof > 0.35f) villagers.remove(i);
                continue;
            }
            switch (v.step) {
                case LOOKING -> {
                    if (v.stepAge > 1.1f) next(v, Step.WALKING);
                }
                case WALKING -> {
                    float to = queueSpot(v.place);
                    float move = Math.min(Math.abs(v.z - to), 2.4f * dt);
                    v.z -= Math.signum(v.z - to) * move;
                    v.stride += move;
                    if (Math.abs(v.z - to) < 1e-3f) {
                        next(v, Step.WAITING);
                        v.landed = 0f;
                        if (v.place == 0) {
                            figure.wave(v.x, v.z);
                            burst(v.x, 1.7f, v.z, 5, Kind.HAPPY);
                        }
                    }
                }
                case WAITING -> {
                    if (Math.abs(v.z - queueSpot(v.place)) > 1e-3f) next(v, Step.WALKING);
                    else if (v.place == 0) {
                        float before = v.trade;
                        v.trade += dt;
                        if (before < 0.9f && v.trade >= 0.9f) {
                            emerald(v.x, 2.4f, v.z);
                            burst(v.x, 1.4f, v.z, 9, Kind.HAPPY);
                        }
                        if (v.trade >= TRADE) {
                            next(v, Step.LEAVING);
                            for (Villager o : villagers) if (o != v && o.step != Step.LEAVING && o.place > 0) o.place--;
                        }
                    }
                }
                case LEAVING -> {
                    // off the path into the field, skipping, then gone in a puff
                    float k = Math.min(1f, v.stepAge / 0.3f);
                    v.yaw = (float) Math.PI + (float) (-Math.PI / 2) * smooth(k);
                    if (v.stepAge > 0.3f) {
                        v.x += 2.2f * dt;
                        v.stride += 2.2f * dt;
                    }
                    if (v.x > 3.6f) {
                        v.poof = 0f;
                        burst(v.x, 0.9f, v.z, 12, Kind.PUFF);
                    }
                }
            }
        }
    }

    private static void next(Villager v, Step step) {
        v.step = step;
        v.stepAge = 0f;
    }

    private Villager trader() {
        for (Villager v : villagers) if (v.place == 0 && v.step == Step.WAITING && v.poof < 0f) return v;
        return null;
    }

    // the gold bars weigh you down the more there are: arms sag, you lean back, tremble, sweat and stagger
    private void gold(float dt, PlayerFigure.Pose pose) {
        if (bars == 0) return;
        float w = bars / 10f;
        float tremble = (float) Math.sin(time * 31) * w;
        float arms = -62f + 34f * w + tremble * 3f;
        pose.right = pose.left = arms;
        pose.rightWeight = pose.leftWeight = 1f;
        pose.lean -= 5f + 20f * w;
        pose.roll += tremble * 1.5f;
        pose.headPitch += 10f - 22f * w * (0.5f + 0.5f * (float) Math.sin(time * 1.7f));
        pose.look = 1f - 0.6f * w;
        // heavy loads make you stagger a step every few seconds
        float stagger = (time % 3.1f) / 0.7f;
        if (w > 0.45f && stagger < 1f) {
            float s = (float) Math.sin(Math.PI * stagger);
            pose.legs = (float) Math.sin(stagger * Math.PI * 2) * 22f * w;
            pose.legsWeight = 1f;
            pose.roll += s * 9f * w * ((int) (time / 3.1f) % 2 == 0 ? 1f : -1f);
            pose.lean -= s * 8f;
            pose.x += s * 0.08f * ((int) (time / 3.1f) % 2 == 0 ? 1f : -1f);
        }
        Villager trader = trader();
        if (trader != null) {
            float offer = trader.trade < 1.1f ? (float) Math.sin(Math.min(1f, trader.trade / 1.1f) * Math.PI) : 0f;
            pose.right -= 28f * offer;
            pose.left -= 28f * offer;
            pose.lean += 12f * offer;
        }
        sparkleTimer -= dt * (0.6f + bars * 0.25f);
        if (sparkleTimer <= 0f) {
            sparkleTimer = 0.4f;
            spawn(Kind.SPARKLE, pose.x + (float) (Math.random() - 0.5) * 0.4f, 1.1f + bars * 0.05f, 0.4f, 0f, 0.3f, 0f,
                    0f, 1f, 0.7f, 0.18f);
        }
        if (w > 0.35f) sweat(dt * w * 1.5f, pose.x, pose.z);
    }

    // the table drops in front of you; with the module on you hammer at it until a bale pops out onto the pile
    private void craft(float dt, PlayerFigure.Pose pose) {
        pose.look = 0.4f;
        pose.headPitch += 22f;
        tableHit = Math.max(0f, tableHit - dt);
        if (table < DROP + LAND) return;
        if (!in.enabled()) {
            // off: you scratch your head at it and wonder
            pose.right = -150f + (float) Math.sin(time * 8) * 10f;
            pose.rightWeight = 1f;
            pose.tilt += 15f;
            pose.lean += 6f;
            questionTimer -= dt;
            if (questionTimer <= 0f) {
                questionTimer = 2.2f;
                spawn(Kind.QUESTION, 0.15f, 2.3f, 0f, 0f, 0.35f, 0f, 0f, 1f, 1.4f, 0.45f);
            }
            return;
        }
        float period = 2.6f;
        float c = (scene - DROP - LAND) % period;
        if (c < 1.6f) {
            float hit = (float) Math.abs(Math.sin(Math.PI * c * 4));
            pose.right = -50f - 105f * hit;
            pose.rightWeight = 1f;
            pose.left = -45f;
            pose.leftWeight = 1f;
            pose.lean += 16f;
            float k = c * 4f;
            if ((int) k != (int) strikes) {
                tableHit = 0.12f;
                for (int i = 0; i < 3; i++) {
                    spawn(Kind.CHIP, (float) (Math.random() - 0.5) * 0.5f, 1.05f, 1.3f + (float) (Math.random() - 0.5) * 0.5f,
                            (float) (Math.random() - 0.5) * 2f, 2f + (float) Math.random(), (float) (Math.random() - 0.5) * 2f,
                            9f, 0.5f, 0.6f, 0.12f);
                }
            }
            strikes = k;
        } else if (c < 2.1f) {
            if (strikes < 6.4f) {
                strikes = 7f;
                int n = crafted.size();
                crafted.add(new Crafted(1.2f + (n % 3) * 0.33f, (n / 3) * 0.3f, 1.0f + (n % 2) * 0.28f));
                if (crafted.size() > 9) {
                    crafted.clear();
                    burst(1.5f, 0.3f, 1.2f, 10, Kind.PUFF);
                }
                burst(0f, 1.2f, 1.3f, 6, Kind.SPARKLE);
            }
            // a little cheer
            float k = (c - 1.6f) / 0.5f;
            float s = (float) Math.sin(Math.PI * k);
            pose.right = pose.left = -170f;
            pose.rightWeight = pose.leftWeight = s;
            pose.y += s * 0.25f;
            pose.headPitch -= 25f;
        } else {
            strikes = 0f;
        }
        glintTimer -= dt;
        if (glintTimer <= 0f && !crafted.isEmpty()) {
            glintTimer = 0.35f;
            Crafted c2 = crafted.get((int) (Math.random() * crafted.size()));
            spawn(Kind.GLINT, c2.x + (float) (Math.random() - 0.5) * 0.3f, c2.y + 0.15f + (float) Math.random() * 0.2f,
                    c2.z + (float) (Math.random() - 0.5) * 0.3f, 0f, 0.2f, 0f, 0f, 1f, 0.8f, 0.16f);
        }
    }

    // the bed drops beside you; you yawn, hop on, turn, pull a nightcap on and topple back to snore
    private void sleep(float dt, PlayerFigure.Pose pose) {
        float t = bed;
        float bedTop = 0.5625f;
        float bx = 1.8f, bz = 0.3f;
        pose.look = 0f;
        if (t < 0.65f) {
            pose.headYaw = 50f * smooth(t / 0.3f);
            if (t > DROP) pose.y = (float) Math.sin(Math.PI * Math.min(1f, (t - DROP) / LAND)) * 0.12f;
            return;
        }
        if (t < 1.25f) {
            float k = (t - 0.65f) / 0.6f;
            float s = (float) Math.sin(Math.PI * k);
            pose.headYaw = 50f * (1f - smooth(k));
            pose.right = pose.left = -170f;
            pose.rightWeight = pose.leftWeight = s;
            pose.lean = -12f * s;
            pose.headPitch = -25f * s;
            if (t - dt < 0.75f && t >= 0.75f) spawn(Kind.YAWN, 0f, 1.75f, 0.35f, 0f, 0.2f, 0.1f, 0f, 1f, 0.9f, 0.3f);
            return;
        }
        if (t < 1.5f) {
            float k = (t - 1.25f) / 0.25f;
            pose.facing = 90f * smooth(k);
            pose.right = pose.left = 40f;
            pose.rightWeight = pose.leftWeight = k;
            pose.lean = 18f * k;
            return;
        }
        if (t < 2.0f) {
            float k = (t - 1.5f) / 0.5f;
            pose.facing = 90f;
            pose.x = bx * k;
            pose.z = bz * k;
            pose.y = bedTop * k + (float) Math.sin(Math.PI * k) * 1.1f;
            pose.right = pose.left = -165f;
            pose.rightWeight = pose.leftWeight = 1f;
            pose.legs = 30f * (float) Math.sin(Math.PI * k);
            pose.legsWeight = 1f;
            pose.lean = -8f;
            if (t - dt < 1.5f) burst(0f, 0.1f, 0f, 6, Kind.DUST);
            return;
        }
        pose.x = bx;
        pose.z = bz;
        pose.y = bedTop;
        if (t < 2.35f) {
            // land with a bounce and hop round to face down the bed
            float k = (t - 2.0f) / 0.35f;
            if (t - dt < 2.0f) burst(bx, bedTop, bz, 8, Kind.PUFF);
            pose.facing = 90f * (1f - smooth(k));
            pose.y += (float) Math.sin(Math.PI * k) * 0.18f;
            pose.lean = 10f * (1f - k);
            return;
        }
        if (t < 2.95f) {
            // both hands pull the nightcap on
            float k = (t - 2.35f) / 0.6f;
            if (cap == 0f && k > 0.35f) burst(bx, 2.3f, bz, 6, Kind.SPARKLE);
            if (k > 0.35f) cap = Math.min(1f, cap + dt / 0.25f);
            float s = (float) Math.sin(Math.PI * k);
            pose.right = pose.left = -175f;
            pose.rightWeight = pose.leftWeight = s;
            pose.headPitch = 15f * s;
            return;
        }
        cap = 1f;
        // topple straight back like a plank, bounce once on the mattress, then snore
        float k = OrbitRig.clamp((t - 2.95f) / 0.45f, 0f, 1f);
        float lie = k * k;
        pose.lie = lie;
        pose.y = bedTop * (1f - lie);
        float after = t - 3.4f;
        if (after > 0f && after < 0.4f) pose.y += (float) Math.sin(Math.PI * after / 0.4f) * 0.07f;
        if (t - dt < 3.4f && t >= 3.4f) burst(bx, 0.7f, bz - 0.8f, 6, Kind.PUFF);
        if (k < 1f) {
            pose.right = pose.left = -40f * k;
            pose.rightWeight = pose.leftWeight = 1f;
            return;
        }
        zeeTimer -= dt;
        if (zeeTimer <= 0f) {
            zeeTimer = 1.1f;
            spawn(Kind.ZEE, bx, 1.0f, bz - 1.55f, 0.18f, 0.45f, 0f, 0f, 1f, 2.4f, 0.34f);
        }
        snoreTimer -= dt;
        if (snoreTimer <= 0f) {
            snoreTimer = 4.2f;
            spawn(Kind.BUBBLE, bx - 0.1f, 0.95f, bz - 1.75f, 0f, 0.05f, 0f, 0f, 1f, 1.3f, 0.5f);
        }
    }

    // the pest loop: they appear in front of you, you jump out of your skin and back away, they give chase, swoop
    // round behind and chase you back to your spot, you catch your breath, they vanish and it starts over
    private static final float LOOP = 6.4f;

    private void pests(float dt, int count, PlayerFigure.Pose pose) {
        while (pests.size() < 8) pests.add(new Pest(pests.size()));
        if (count == 0) {
            for (Pest p : pests) {
                if (p.shown) burst(p.at.x, p.at.y, p.at.z, 5, Kind.PUFF);
                p.shown = false;
            }
            return;
        }
        float c = scene % LOOP;
        float fear = 0.6f + 0.4f * (count - 1) / 7f;
        float run = 2.6f + 0.15f * count;
        float back = 0f;
        if (c >= 1.3f && c < 2.9f) back = run * smooth((c - 1.3f) / 1.6f);
        else if (c >= 2.9f && c < 3.3f) back = run;
        else if (c >= 3.3f && c < 4.6f) back = run * (1f - smooth((c - 3.3f) / 1.3f));
        pose.z = -back;
        Vector3f player = new Vector3f(0f, 0f, -back);
        Vector3f spawnAt = new Vector3f(0.9f, 1.45f, 3.0f);

        if (c < 0.9f) {
            // unaware: a little whistle and a look round as they appear
            pose.headYaw = (float) Math.sin(c * 5) * 25f;
            pose.look = 0.4f;
        } else if (c < 1.3f) {
            float k = (c - 0.9f) / 0.4f;
            if (c - dt < 0.9f) spawn(Kind.BANG, 0f, 2.4f, 0f, 0f, 0.6f, 0f, 0f, 1f, 1.1f, 0.6f);
            pose.y = (float) Math.sin(Math.PI * k) * 0.55f * fear;
            pose.right = pose.left = -172f;
            pose.rightWeight = pose.leftWeight = 1f;
            pose.lean = -20f * fear;
            pose.headPitch = -12f;
            pose.legs = 22f;
            pose.legsWeight = 1f;
        } else if (c < 2.9f) {
            // backpedal, arms flailing
            float s = c * 16f * fear;
            pose.legs = (float) Math.sin(s) * 52f;
            pose.legsWeight = 1f;
            pose.y = Math.abs((float) Math.sin(s)) * 0.1f * fear;
            pose.lean = -16f;
            pose.right = -150f + (float) Math.sin(c * 17) * 40f * fear;
            pose.left = -150f + (float) Math.sin(c * 17 + 2.1f) * 40f * fear;
            pose.rightWeight = pose.leftWeight = 1f;
            pose.roll = (float) Math.sin(c * 21) * 6f * fear;
            sweat(dt, 0f, -back);
            dust(dt, 0f, -back);
        } else if (c < 3.3f) {
            // they swoop round behind; you spin to look
            float k = (c - 2.9f) / 0.4f;
            pose.tilt = 18f * (float) Math.sin(Math.PI * k);
            pose.turn = -35f * smooth(k);
            pose.headYaw = -70f * smooth(k);
            pose.look = 0f;
            pose.right = pose.left = -120f;
            pose.rightWeight = pose.leftWeight = 1f;
            if (c - dt < 2.9f) spawn(Kind.BANG, 0f, 2.4f, -back, 0f, 0.6f, 0f, 0f, 1f, 0.9f, 0.5f);
        } else if (c < 4.6f) {
            // sprint home with them on your tail, glancing back over your shoulder
            float s = c * 17f * fear;
            pose.legs = (float) Math.sin(s) * 55f;
            pose.legsWeight = 1f;
            pose.y = Math.abs((float) Math.sin(s)) * 0.12f;
            pose.lean = 18f;
            pose.right = (float) Math.sin(s) * 70f;
            pose.left = -(float) Math.sin(s) * 70f;
            pose.rightWeight = pose.leftWeight = 1f;
            pose.look = 0f;
            float glance = (float) Math.max(0, Math.sin(c * 4.5f));
            pose.headYaw = -75f * glance;
            pose.turn = -30f * glance;
            sweat(dt, 0f, -back);
            dust(dt, 0f, -back);
        } else if (c < 5.6f) {
            // hands on knees, panting
            pose.lean = 32f;
            pose.right = pose.left = -20f;
            pose.rightWeight = pose.leftWeight = 1f;
            pose.headPitch = -20f + (float) Math.sin(c * 14) * 6f;
            pose.look = 0f;
            sweat(dt, 0f, 0f);
        } else {
            // phew: wipe the brow
            float k = (c - 5.6f) / 0.8f;
            pose.right = -140f + (float) Math.sin(k * Math.PI * 3) * 15f;
            pose.rightWeight = (float) Math.sin(Math.PI * k);
            pose.tilt = 10f;
        }

        for (Pest p : pests) {
            boolean want = p.index < count && c >= p.index * 0.07f && c < 5.6f;
            if (want && !p.shown) {
                p.at.set(spawnAt).add(ring(p.index, count, 0.9f));
                p.last.set(p.at);
                p.shownFor = 0f;
                burst(p.at.x, p.at.y, p.at.z, 7, Kind.PUFF);
            }
            if (!want && p.shown) burst(p.at.x, p.at.y, p.at.z, 7, Kind.PUFF);
            p.shown = want;
            if (!want) continue;
            p.shownFor += dt;
            Vector3f target;
            Vector3f ring = ring(p.index, count, 0.85f);
            if (c < 1.3f) {
                target = new Vector3f(spawnAt).add(ring);
            } else if (c < 2.9f) {
                target = new Vector3f(player).add(0f, 1.45f, 1.7f).add(ring);
            } else if (c < 3.3f) {
                double arc = Math.PI * smooth((c - 2.9f) / 0.4f);
                target = new Vector3f(player).add((float) Math.sin(arc) * 1.9f, 1.5f, (float) Math.cos(arc) * 1.9f).add(ring);
            } else if (c < 4.6f) {
                target = new Vector3f(player).add(0f, 1.45f, -1.6f).add(ring);
            } else {
                double a = p.index * Math.PI * 2 / count + c * 2.5;
                target = new Vector3f((float) Math.cos(a) * 1.5f, 1.75f, (float) Math.sin(a) * 1.5f);
            }
            target.y += (float) Math.sin(time * 5.1f + p.index) * 0.1f;
            p.last.set(p.at);
            float follow = 1f - (float) Math.exp(-dt * (5.5f + p.index * 0.4f));
            p.at.lerp(target, follow);
        }
    }

    private static Vector3f ring(int index, int count, float radius) {
        double a = index * Math.PI * 2 / Math.max(1, count);
        return new Vector3f((float) Math.cos(a) * radius, (float) Math.sin(a * 2) * 0.2f, (float) Math.sin(a) * radius);
    }

    // three stands land in turn; one at a time spins to show off its set, which jumps onto you for a flex
    private static final float SHOWCASE = 2.6f;

    private void loadout(float dt, PlayerFigure.Pose pose) {
        if (scene < 1.0f) {
            worn = -1;
            return;
        }
        float t = scene - 1.0f;
        int show = (int) (t / SHOWCASE) % 3;
        float k = t % SHOWCASE;
        int wearing = k >= 0.75f && k < SHOWCASE - 0.2f ? show : -1;
        if (wearing != worn) {
            Vector3f at = standSpot(show);
            if (wearing >= 0) {
                burst(0f, 1.2f, 0f, 12, Kind.SPARKLE);
                burst(at.x, 1.2f, at.z, 6, Kind.PUFF);
            } else {
                burst(at.x, 1.2f, at.z, 8, Kind.SPARKLE);
            }
            worn = wearing;
        }
        if (wearing < 0) {
            pose.headYaw = 40f;
            pose.look = 0.3f;
            return;
        }
        float w = k - 0.75f;
        if (w < 0.6f) {
            // arms up in triumph
            float s = (float) Math.sin(Math.PI * Math.min(1f, w / 0.6f));
            pose.right = -160f + (float) Math.sin(w * 20) * 10f;
            pose.left = -160f - (float) Math.sin(w * 20) * 10f;
            pose.rightWeight = pose.leftWeight = s;
            pose.y = s * 0.15f;
            pose.lean = -6f * s;
        } else {
            // admire it, turning this way and that
            pose.turn = (float) Math.sin((w - 0.6f) * 3f) * 25f;
            pose.headPitch = 28f;
            pose.look = 0f;
            pose.right = -20f;
            pose.left = -20f;
            pose.rightWeight = pose.leftWeight = 1f;
        }
    }

    private static Vector3f standSpot(int i) {
        return new Vector3f(2.3f + 1.2f * i, 0f, 1.6f - 1.0f * i);
    }

    private void sweat(float dt, float x, float z) {
        sweatTimer -= dt;
        if (sweatTimer > 0f) return;
        sweatTimer = 0.14f;
        float side = Math.random() < 0.5 ? -1f : 1f;
        spawn(Kind.SWEAT, x + side * 0.28f, 1.75f, z, side * 0.9f, 1.4f, 0f, 7f, 1f, 0.6f, 0.13f);
    }

    private void dust(float dt, float x, float z) {
        dustTimer -= dt;
        if (dustTimer > 0f) return;
        dustTimer = 0.1f;
        spawn(Kind.DUST, x + (float) (Math.random() - 0.5) * 0.4f, 0.05f, z, 0f, 0.4f, 0f, 0f, 0.5f, 0.5f, 0.25f);
    }

    private void emerald(float x, float y, float z) {
        spawn(Kind.EMERALD, x, y, z, 0f, 0.7f, 0f, 0.9f, 1f, 1.3f, 0.5f);
    }

    // -- geometry -----------------------------------------------------------------------------------------------

    // fills the entity buffers in the scene renderer's space; local maps the farm's blocks into it
    List<Draw> build(Matrix4f local, Vector3d camLocal, PlayerFigure figure) {
        for (SceneClone.Buffer b : buffers.values()) b.reset();
        for (Villager v : villagers) villagerFrame(v, local);
        for (Pest p : pests) {
            if (!p.shown) continue;
            Identifier skin = heads.apply(p.index);
            if (skin == null) continue;
            float grow = backOut(Math.min(1f, p.shownFor / 0.35f)) * 0.8f;
            float wobble = (float) (Math.sin(p.shownFor * 24) * Math.exp(-p.shownFor * 6) * 0.35);
            float dx = p.at.x - p.last.x, dz = p.at.z - p.last.z;
            float heading = dx * dx + dz * dz > 1e-6f ? (float) Math.atan2(dx, dz)
                    : (float) Math.atan2(figure.pose.x - p.at.x, figure.pose.z - p.at.z);
            Matrix4f m = new Matrix4f(local).translate(p.at.x, p.at.y, p.at.z).rotateY(heading)
                    .rotateX((float) Math.sin(time * 3.1f + p.index) * 0.2f).rotateZ((float) Math.sin(time * 2.3f + p.index) * 0.15f)
                    .scale(grow * (1f - wobble * 0.5f), grow * (1f + wobble), grow * (1f - wobble * 0.5f)).translate(0f, -1.75f, 0f);
            head(buffer(skin), m);
            // a bee's wings buzzing on the back of its head
            float flap = (float) Math.cos(time * 46f + p.index * 1.7f) * (float) Math.PI * 0.18f + 0.25f;
            ModelBoxes.part(buffer(BEE), m, 64, 64, false, -3.5f, -6.5f, 1.5f, 0f, -0.26f, flap, -9, 0, 0, 9, 0, 6, 0, 18, 0.001f);
            ModelBoxes.part(buffer(BEE), m, 64, 64, true, 3.5f, -6.5f, 1.5f, 0f, 0.26f, -flap, 0, 0, 0, 9, 0, 6, 0, 18, 0.001f);
        }
        boolean shown = "Auto Loadout".equals(focus);
        float t0 = scene - 1.0f;
        int show = t0 < 0f ? -1 : (int) (t0 / SHOWCASE) % 3;
        float k0 = t0 < 0f ? 0f : (t0 % SHOWCASE) / 0.7f;
        for (int i = 0; i < 3; i++) {
            float t = stands[i];
            if (t <= 0f) continue;
            Vector3f at = standSpot(i);
            float face = (float) Math.atan2(camLocal.x - at.x, camLocal.z - at.z);
            boolean spotlit = shown && show == i && k0 < 1f;
            float spin = spotlit ? (float) (smooth(k0) * Math.PI * 2) : 0f;
            float hop = spotlit ? (float) Math.sin(k0 * Math.PI) * 0.4f : 0f;
            Matrix4f m = drop(new Matrix4f(local).translate(at.x, hop, at.z).rotateY(face + spin), t, shown);
            stand(buffer(STAND), m);
            if (worn != i) armour(buffer(outer(i)), buffer(inner(i)), m);
        }
        if (worn >= 0) wear(figure, worn);
        if (table >= 0f) {
            float squash = tableHit > 0f ? (float) Math.sin(Math.PI * tableHit / 0.12f) * 0.12f : 0f;
            Matrix4f m = drop(new Matrix4f(local).translate(0f, 0f, 1.3f), table, "Auto Supercraft".equals(focus))
                    .scale(1f + squash * 0.5f, 1f - squash, 1f + squash * 0.5f).translate(-0.5f, 0f, -0.5f);
            cube(m, 0, 0, 0, 1, 1, 1, TABLE_TOP, PLANKS, TABLE_FRONT, TABLE_SIDE, TABLE_SIDE, TABLE_FRONT);
        }
        for (Crafted c : crafted) {
            float k = Math.min(1f, c.age / 0.6f);
            float x = c.x * smooth(k), z = 1.3f + (c.z - 1.3f) * smooth(k);
            float y = 1.0f + (c.y - 1.0f) * k + (float) Math.sin(Math.PI * k) * 1.0f;
            float land = c.age > 0.6f && c.age < 0.8f ? (float) Math.sin(Math.PI * (c.age - 0.6f) / 0.2f) * 0.2f : 0f;
            Matrix4f m = new Matrix4f(local).translate(x, y, z).rotateY((1f - k) * 6f + c.x * 3f)
                    .scale(0.3f * (1f + land * 0.5f), 0.3f * (1f - land), 0.3f * (1f + land * 0.5f)).translate(-0.5f, 0f, -0.5f);
            cube(m, 0, 0, 0, 1, 1, 1, HAY_TOP, HAY_TOP, HAY_SIDE, HAY_SIDE, HAY_SIDE, HAY_SIDE);
        }
        if (bed >= 0f) {
            Matrix4f m = drop(new Matrix4f(local).translate(1.8f, 0f, -0.6f), bed, "Dynamic Rest".equals(focus));
            bedHalf(buffer(BED), new Matrix4f(m).translate(-0.5f, 0f, 0f), 0);
            bedHalf(buffer(BED), new Matrix4f(m).translate(-0.5f, 0f, 1f), 22);
        }
        if (bars > 0) goldBars(figure);
        if (cap > 0f) nightcap(figure.headFrame(), backOut(cap));
        List<Draw> out = new ArrayList<>();
        for (Map.Entry<Identifier, SceneClone.Buffer> e : buffers.entrySet()) {
            if (e.getValue().count > 0) out.add(new Draw(e.getKey(), e.getValue()));
        }
        return out;
    }

    private static Identifier outer(int metal) {
        return mc("textures/entity/equipment/humanoid/" + METALS[metal] + ".png");
    }

    private static Identifier inner(int metal) {
        return mc("textures/entity/equipment/humanoid_leggings/" + METALS[metal] + ".png");
    }

    // the stand's set on the player: each piece follows the figure's own head, body, arm and leg frames
    private void wear(PlayerFigure figure, int metal) {
        SceneClone.Buffer outer = buffer(outer(metal)), inner = buffer(inner(metal));
        ModelBoxes.part(outer, figure.headFrame().scale(16f), 64, 32, false, 0, 24, 0, 0, 0, 0, -4, -8, -4, 8, 8, 8, 0, 0, 1f);
        Matrix4f body = figure.bodyFrame().scale(16f);
        ModelBoxes.part(outer, body, 64, 32, false, 0, 0, 0, 0, 0, 0, -4, 0, -2, 8, 12, 4, 16, 16, 1f);
        ModelBoxes.part(inner, body, 64, 32, false, 0, 0, 0, 0, 0, 0, -4, 0, -2, 8, 12, 4, 16, 16, 0.5f);
        ModelBoxes.part(outer, figure.rightArmFrame().scale(16f), 64, 32, false, 0, 24, 0, 0, 0, 0, -2, -2, -2, 4, 12, 4, 40, 16, 1f);
        ModelBoxes.part(outer, figure.leftArmFrame().scale(16f), 64, 32, true, 0, 24, 0, 0, 0, 0, -2, -2, -2, 4, 12, 4, 40, 16, 1f);
        Matrix4f right = figure.rightLegFrame().scale(16f), left = figure.leftLegFrame().scale(16f);
        ModelBoxes.part(inner, right, 64, 32, false, 0, 24, 0, 0, 0, 0, -2, 0, -2, 4, 12, 4, 0, 16, 0.5f);
        ModelBoxes.part(inner, left, 64, 32, true, 0, 24, 0, 0, 0, 0, -2, 0, -2, 4, 12, 4, 0, 16, 0.5f);
        ModelBoxes.part(outer, right, 64, 32, false, 0, 24, 0, 0, 0, 0, -2, 0, -2, 4, 12, 4, 0, 16, 1f);
        ModelBoxes.part(outer, left, 64, 32, true, 0, 24, 0, 0, 0, 0, -2, 0, -2, 4, 12, 4, 0, 16, 1f);
    }

    // a visitor's performance: it pops in with a squash and stretch, glances about, waddles up the path, lands in
    // its place and fidgets; at the front it grumbles, cheers at the emerald and skips off into the field
    private void villagerFrame(Villager v, Matrix4f local) {
        float a = v.age;
        float grow = v.poof >= 0f ? 1f - v.poof / 0.35f : backOut(Math.min(1f, a / 0.45f));
        if (grow <= 0.01f) return;
        float wobble = (float) (Math.sin(a * 22) * Math.exp(-a * 6) * 0.4);
        float sy = 1f + wobble, sxz = 1f - wobble * 0.5f;
        float hop = a < 0.45f ? (float) Math.sin(Math.PI * a / 0.45f) * 0.4f : 0f;
        float headYaw = 0f, headPitch = 0f, headRoll = 0f, turn = 0f, roll = 0f, lean = 0f, legs = 0f, arms = 0f;
        switch (v.step) {
            case LOOKING -> {
                float lt = Math.max(0f, v.stepAge - 0.3f);
                int beat = (int) (lt / 0.3f);
                float within = (lt - beat * 0.3f) / 0.3f;
                float[] looks = {1.1f, -1.1f, 0f};
                float from = beat == 0 ? 0f : looks[Math.min(beat - 1, 2)], to = looks[Math.min(beat, 2)];
                headYaw = from + (to - from) * smooth(clamp01(within / 0.4f));
                turn = headYaw * 0.4f;
                headRoll = (float) Math.sin(lt * 6) * 0.22f;
            }
            case WALKING -> {
                float w = v.stride * 3.4f;
                hop += Math.abs((float) Math.sin(w)) * 0.14f;
                roll = (float) Math.sin(w) * 0.18f;
                lean = 0.14f;
                legs = (float) Math.sin(w) * 0.85f;
                arms = (float) Math.sin(w * 2) * 0.14f;
                headPitch = (float) Math.sin(w * 2) * 0.1f;
            }
            case WAITING -> {
                roll = (float) Math.sin(a * 1.6f + v.place) * 0.06f;
                headYaw = (float) Math.sin(a * 0.7f + v.place) * 0.4f;
                headRoll = (float) Math.sin(a * 0.9f + v.place * 2) * 0.1f;
                sy *= 1f + (float) Math.sin(a * 2.2f) * 0.02f;
                if (v.place == 0) {
                    float tr = v.trade;
                    if (tr < 0.8f) {
                        // "hrmm": the head shakes over the offer
                        headYaw = (float) Math.sin(tr * 30) * 0.5f;
                        headRoll = 0.15f;
                        headPitch = 0.25f;
                    } else if (tr < 2.0f) {
                        float k = (tr - 0.9f) / 0.35f;
                        if (k > 0f && k < 2f) hop += Math.abs((float) Math.sin(Math.PI * k)) * 0.35f;
                        arms = -0.6f * (float) Math.sin(Math.PI * clamp01((tr - 0.9f) / 1.1f));
                        headPitch = -0.3f;
                    }
                } else {
                    // the rest of the queue cranes to see the trade, then idles and nods
                    Villager front = trader();
                    if (front != null && front.trade > 0.9f && front.trade < 1.4f) {
                        hop += (float) Math.sin(Math.PI * (front.trade - 0.9f) / 0.5f) * 0.18f;
                    }
                    roll += 0.12f * (v.place % 2 == 0 ? 1f : -1f);
                    float nod = (a + v.place * 0.7f) % 3.2f;
                    if (nod < 0.5f) headPitch = (float) Math.sin(nod / 0.5f * Math.PI) * 0.35f;
                }
            }
            case LEAVING -> {
                float w = v.stride * 3.0f;
                hop += Math.abs((float) Math.sin(w)) * 0.3f;
                legs = (float) Math.sin(w) * 0.9f;
                arms = -0.4f;
                roll = (float) Math.sin(w) * 0.2f;
                headPitch = -0.2f;
            }
        }
        if (v.landed < 0.3f) {
            float c = v.landed / 0.3f;
            sy *= 1f - 0.24f * (float) Math.sin(Math.PI * c);
            sxz *= 1f + 0.12f * (float) Math.sin(Math.PI * c);
        }
        float spin = v.poof >= 0f ? v.poof * 18f : 0f;
        Matrix4f m = new Matrix4f(local).translate(v.x, hop, v.z).rotateY(v.yaw + turn + spin).rotateZ(roll).rotateX(lean)
                .scale(grow * sxz, grow * sy, grow * sxz);
        for (Identifier tex : new Identifier[]{VILLAGER, VILLAGER_PLAINS, VILLAGER_FARMER}) {
            villager(buffer(tex), m, headYaw, headPitch, headRoll, legs, arms);
        }
    }

    // a block or stand falling in from three blocks up, squashing as it lands; shrinking away once hidden
    private static Matrix4f drop(Matrix4f m, float t, boolean shown) {
        if (!shown) return m.scale(Math.max(0f, t / (DROP + LAND)));
        if (t < DROP) {
            float k = t / DROP;
            return m.translate(0f, 3f * (1f - k * k), 0f).scale(0.85f, 1.15f, 0.85f);
        }
        float c = Math.min(1f, (t - DROP) / LAND);
        float squash = (float) Math.sin(Math.PI * c) * 0.25f * (1f - c * 0.5f);
        return m.scale(1f + squash * 0.5f, 1f - squash, 1f + squash * 0.5f);
    }

    // gold bars stacked in a little pyramid on the forearms, wherever the hands are carrying them
    private void goldBars(PlayerFigure figure) {
        SceneClone.Buffer out = buffer(GOLD);
        Matrix4f body = figure.bodyFrame();
        Matrix4f inverse = new Matrix4f(body).invert();
        Vector3f hands = figure.rightArmFrame().transformPosition(0f, -8f, 0f, new Vector3f())
                .add(figure.leftArmFrame().transformPosition(0f, -8f, 0f, new Vector3f())).mul(0.5f);
        inverse.transformPosition(hands);
        int left = bars;
        int row = 0;
        while (left > 0) {
            int inRow = Math.min(left, Math.max(1, 4 - row));
            for (int i = 0; i < inRow; i++) {
                float x = (i - (inRow - 1) / 2f) * 3.4f;
                Matrix4f m = new Matrix4f(body).translate(x, hands.y + 1.6f + row * 2.3f, hands.z - 1.2f - (row % 2) * 0.6f);
                cubeInto(out, m, -1.6f, 0f, -3.5f, 1.6f, 2.2f, 3.5f, 1f / 16f);
            }
            left -= inRow;
            row++;
        }
    }

    // a red wool nightcap with a white bobble drooping off the back, worn over the head
    private void nightcap(Matrix4f head, float s) {
        Matrix4f m = new Matrix4f(head).translate(0f, 7.5f, 0f).scale(s).translate(0f, -7.5f, 0f);
        cubeInto(buffer(RED_WOOL), m, -4.6f, 6.5f, -4.6f, 4.6f, 9f, 4.6f, 1f / 16f);
        Matrix4f top = new Matrix4f(m).translate(0f, 9f, -0.5f).rotateX(-0.35f);
        cubeInto(buffer(RED_WOOL), top, -3.4f, 0f, -3.4f, 3.4f, 2.6f, 3.4f, 1f / 16f);
        Matrix4f tip = new Matrix4f(top).translate(0f, 2.6f, -0.8f).rotateX(-0.6f);
        cubeInto(buffer(RED_WOOL), tip, -2f, 0f, -2f, 2f, 2.6f, 2f, 1f / 16f);
        Matrix4f droop = new Matrix4f(tip).translate(0f, 2.4f, -0.6f).rotateX(-1.1f + (float) Math.sin(time * 2) * 0.15f);
        cubeInto(buffer(RED_WOOL), droop, -1.2f, 0f, -1.2f, 1.2f, 2.8f, 1.2f, 1f / 16f);
        cubeInto(buffer(WHITE_WOOL), new Matrix4f(droop).translate(0f, 3.2f, 0f), -1.5f, -1.5f, -1.5f, 1.5f, 1.5f, 1.5f, 1f / 16f);
    }

    private void cube(Matrix4f m, float x0, float y0, float z0, float x1, float y1, float z1,
                      Identifier top, Identifier bottom, Identifier north, Identifier south, Identifier west, Identifier east) {
        face(buffer(top), m, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, 1f, 1f, 1f);
        face(buffer(bottom), m, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, 1f, 1f, 0.5f);
        face(buffer(north), m, x1, y1, z0, x0, y1, z0, x0, y0, z0, x1, y0, z0, 1f, 1f, 0.8f);
        face(buffer(south), m, x0, y1, z1, x1, y1, z1, x1, y0, z1, x0, y0, z1, 1f, 1f, 0.8f);
        face(buffer(west), m, x0, y1, z0, x0, y1, z1, x0, y0, z1, x0, y0, z0, 1f, 1f, 0.6f);
        face(buffer(east), m, x1, y1, z1, x1, y1, z0, x1, y0, z0, x1, y0, z1, 1f, 1f, 0.6f);
    }

    private void cubeInto(SceneClone.Buffer out, Matrix4f m, float x0, float y0, float z0, float x1, float y1, float z1,
                          float uvPerUnit) {
        face(out, m, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, (x1 - x0) * uvPerUnit, (z1 - z0) * uvPerUnit, 1f);
        face(out, m, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, (x1 - x0) * uvPerUnit, (z1 - z0) * uvPerUnit, 0.5f);
        face(out, m, x1, y1, z0, x0, y1, z0, x0, y0, z0, x1, y0, z0, (x1 - x0) * uvPerUnit, (y1 - y0) * uvPerUnit, 0.8f);
        face(out, m, x0, y1, z1, x1, y1, z1, x1, y0, z1, x0, y0, z1, (x1 - x0) * uvPerUnit, (y1 - y0) * uvPerUnit, 0.8f);
        face(out, m, x0, y1, z0, x0, y1, z1, x0, y0, z1, x0, y0, z0, (z1 - z0) * uvPerUnit, (y1 - y0) * uvPerUnit, 0.6f);
        face(out, m, x1, y1, z1, x1, y1, z0, x1, y0, z0, x1, y0, z1, (z1 - z0) * uvPerUnit, (y1 - y0) * uvPerUnit, 0.6f);
    }

    // a quad over corners a, b, c, d (top-left round to bottom-left) showing the top-left u x v of its texture
    private static void face(SceneClone.Buffer out, Matrix4f m, float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz, float u, float v, float shade) {
        Vector3f a = m.transformPosition(ax, ay, az, new Vector3f());
        Vector3f b = m.transformPosition(bx, by, bz, new Vector3f());
        Vector3f c = m.transformPosition(cx, cy, cz, new Vector3f());
        Vector3f d = m.transformPosition(dx, dy, dz, new Vector3f());
        int color = SceneClone.rgba(0xFFFFFF, shade, 255);
        u = Math.min(1f, u);
        v = Math.min(1f, v);
        out.vertex(a.x, a.y, a.z, 0f, 0f, color);
        out.vertex(b.x, b.y, b.z, u, 0f, color);
        out.vertex(c.x, c.y, c.z, u, v, color);
        out.vertex(a.x, a.y, a.z, 0f, 0f, color);
        out.vertex(c.x, c.y, c.z, u, v, color);
        out.vertex(d.x, d.y, d.z, 0f, v, color);
    }

    // one half of the bed block entity, a 16 x 16 x 6 slab whose front lies face up 9 pixels high the way the
    // game's bed renderer lays it; its texture's top edge sits at -z
    private static void bedHalf(SceneClone.Buffer out, Matrix4f block, int v) {
        Matrix4f m = new Matrix4f(block).translate(0f, 9f / 16f, 0f).rotateX((float) (-Math.PI / 2)).scale(1f / 16f);
        ModelBoxes.box(out, m, 64, 64, false, 0, 0, -6, 16, 16, 0, 0, v, 16, 16, 6);
    }

    private SceneClone.Buffer buffer(Identifier texture) {
        return buffers.computeIfAbsent(texture, t -> new SceneClone.Buffer(1024));
    }

    // -- models -------------------------------------------------------------------------------------------------

    static void villager(SceneClone.Buffer out, Matrix4f m, float look, float walk) {
        villager(out, m, look, 0f, 0f, (float) Math.sin(walk) * 0.7f, 0f);
    }

    // head yaw/pitch/roll, leg swing and arm jiggle in radians
    static void villager(SceneClone.Buffer out, Matrix4f m, float look, float nod, float tilt, float legs, float arms) {
        ModelBoxes.part(out, m, 64, 64, false, 0, 0, 0, nod, look, tilt, -4, -10, -4, 8, 10, 8, 0, 0, 0f);
        ModelBoxes.part(out, m, 64, 64, false, 0, 0, 0, nod, look, tilt, -4, -10, -4, 8, 10, 8, 32, 0, 0.51f);
        ModelBoxes.part(out, m, 64, 64, false, 0, 0, 0, nod, look, tilt, -1, -3, -6, 2, 4, 2, 24, 0, 0f);
        ModelBoxes.part(out, m, 64, 64, false, 0, 0, 0, 0, 0, 0, -4, 0, -3, 8, 12, 6, 16, 20, 0f);
        ModelBoxes.part(out, m, 64, 64, false, 0, 0, 0, 0, 0, 0, -4, 0, -3, 8, 20, 6, 0, 38, 0.5f);
        ModelBoxes.part(out, m, 64, 64, false, 0, 3, -1, -0.75f + arms, 0, 0, -8, -2, -2, 4, 8, 4, 44, 22, 0f);
        ModelBoxes.part(out, m, 64, 64, true, 0, 3, -1, -0.75f + arms, 0, 0, 4, -2, -2, 4, 8, 4, 44, 22, 0f);
        ModelBoxes.part(out, m, 64, 64, false, 0, 3, -1, -0.75f + arms, 0, 0, -4, 2, -2, 8, 4, 4, 40, 38, 0f);
        ModelBoxes.part(out, m, 64, 64, false, -2, 12, 0, legs, 0, 0, -2, 0, -2, 4, 12, 4, 0, 22, 0f);
        ModelBoxes.part(out, m, 64, 64, true, 2, 12, 0, -legs, 0, 0, -2, 0, -2, 4, 12, 4, 0, 22, 0f);
    }

    static void head(SceneClone.Buffer out, Matrix4f m) {
        ModelBoxes.part(out, m, 64, 64, false, 0, 0, 0, 0, 0, 0, -4, -8, -4, 8, 8, 8, 0, 0, 0f);
        ModelBoxes.part(out, m, 64, 64, false, 0, 0, 0, 0, 0, 0, -4, -8, -4, 8, 8, 8, 32, 0, 0.5f);
    }

    static void stand(SceneClone.Buffer out, Matrix4f m) {
        ModelBoxes.part(out, m, 64, 64, false, 0, 1, 0, 0, 0, 0, -1, -7, -1, 2, 7, 2, 0, 0, 0f);
        ModelBoxes.part(out, m, 64, 64, false, 0, 0, 0, 0, 0, 0, -6, 0, -1.5f, 12, 3, 3, 0, 26, 0f);
        ModelBoxes.part(out, m, 64, 64, false, -5, 2, 0, 0, 0, 0, -2, -2, -1, 2, 12, 2, 24, 0, 0f);
        ModelBoxes.part(out, m, 64, 64, true, 5, 2, 0, 0, 0, 0, 0, -2, -1, 2, 12, 2, 32, 16, 0f);
        ModelBoxes.part(out, m, 64, 64, false, -1.9f, 12, 0, 0, 0, 0, -1, 0, -1, 2, 11, 2, 8, 0, 0f);
        ModelBoxes.part(out, m, 64, 64, true, 1.9f, 12, 0, 0, 0, 0, -1, 0, -1, 2, 11, 2, 40, 16, 0f);
        ModelBoxes.part(out, m, 64, 64, false, 0, 0, 0, 0, 0, 0, -3, 3, -1, 2, 7, 2, 16, 0, 0f);
        ModelBoxes.part(out, m, 64, 64, false, 0, 0, 0, 0, 0, 0, 1, 3, -1, 2, 7, 2, 48, 16, 0f);
        ModelBoxes.part(out, m, 64, 64, false, 0, 0, 0, 0, 0, 0, -4, 10, -1, 8, 2, 2, 0, 48, 0f);
        ModelBoxes.part(out, m, 64, 64, false, 0, 12, 0, 0, 0, 0, -6, 11, -6, 12, 1, 12, 0, 32, 0f);
    }

    // a full set: helmet, chestplate and boots from the outer layer, leggings from the inner one
    static void armour(SceneClone.Buffer outer, SceneClone.Buffer inner, Matrix4f m) {
        ModelBoxes.part(outer, m, 64, 32, false, 0, 1, 0, 0, 0, 0, -4, -8, -4, 8, 8, 8, 0, 0, 1f);
        ModelBoxes.part(outer, m, 64, 32, false, 0, 0, 0, 0, 0, 0, -4, 0, -2, 8, 12, 4, 16, 16, 1f);
        ModelBoxes.part(outer, m, 64, 32, false, -5, 2, 0, 0, 0, 0, -3, -2, -2, 4, 12, 4, 40, 16, 1f);
        ModelBoxes.part(outer, m, 64, 32, true, 5, 2, 0, 0, 0, 0, -1, -2, -2, 4, 12, 4, 40, 16, 1f);
        ModelBoxes.part(outer, m, 64, 32, false, -1.9f, 12, 0, 0, 0, 0, -2, 0, -2, 4, 12, 4, 0, 16, 1f);
        ModelBoxes.part(outer, m, 64, 32, true, 1.9f, 12, 0, 0, 0, 0, -2, 0, -2, 4, 12, 4, 0, 16, 1f);
        ModelBoxes.part(inner, m, 64, 32, false, 0, 0, 0, 0, 0, 0, -4, 0, -2, 8, 12, 4, 16, 16, 0.5f);
        ModelBoxes.part(inner, m, 64, 32, false, -1.9f, 12, 0, 0, 0, 0, -2, 0, -2, 4, 12, 4, 0, 16, 0.5f);
        ModelBoxes.part(inner, m, 64, 32, true, 1.9f, 12, 0, 0, 0, 0, -2, 0, -2, 4, 12, 4, 0, 16, 0.5f);
    }

    // -- flat things: puffs, z's, sparkles, sweat, chips and the rest ------------------------------------------------

    void appendQuads(List<OrbitWorldRenderer.Quad> out, Function<Vector3f, Vector3d> toWorld, OrbitLayout.Camera cam) {
        if (!drawn) {
            drawSprites();
            drawn = true;
        }
        for (Particle p : particles) {
            float k = p.age / p.life;
            float alpha, size;
            switch (p.kind) {
                case ZEE -> {
                    alpha = Math.min(1f, (1f - k) * 1.6f);
                    size = p.size * (0.7f + 0.6f * k);
                }
                case SPARKLE, HAPPY, GLINT -> {
                    alpha = (float) Math.sin(Math.PI * k);
                    size = p.size * (float) Math.sin(Math.PI * k);
                }
                case BANG, QUESTION -> {
                    alpha = Math.min(1f, (1f - k) * 3f);
                    size = p.size * backOut(Math.min(1f, k * 5f));
                }
                case BUBBLE -> {
                    alpha = k < 0.9f ? 0.9f : (1f - k) * 9f;
                    size = p.size * (0.3f + 0.9f * k);
                }
                case EMERALD -> {
                    alpha = Math.min(1f, (1f - k) * 2.5f);
                    size = p.size * backOut(Math.min(1f, k * 4f));
                }
                default -> {
                    alpha = (1f - k) * (1f - k);
                    size = p.size * (0.6f + 0.9f * k);
                }
            }
            if (size <= 0.005f) continue;
            out.add(billboard(toWorld.apply(new Vector3f(p.x, p.y, p.z)), cam, size, sprites.get(p.kind).texture(), alpha));
        }
    }

    private void drawSprites() {
        sprite(Kind.PUFF, nvg -> {
            nvg.radialGradient(16f, 16f, 2f, 15f, 0xF0F2F2F2, 0x00B8B8B8);
            nvg.circle(13f, 13f, 6f, 0x66FFFFFF);
        });
        sprite(Kind.DUST, nvg -> nvg.radialGradient(16f, 16f, 2f, 15f, 0xC0B39B7A, 0x00A08A6A));
        sprite(Kind.SPARKLE, nvg -> star(nvg, 0xFFFFF4B0, 0xCCFFE680));
        sprite(Kind.HAPPY, nvg -> star(nvg, 0xFF8BF07A, 0xCC40D060));
        sprite(Kind.CHIP, nvg -> {
            nvg.rect(9f, 9f, 14f, 14f, 0xFF9C7448);
            nvg.rect(9f, 9f, 14f, 4f, 0xFFB98D5C);
        });
        sprite(Kind.SWEAT, nvg -> {
            nvg.beginPath();
            nvg.moveTo(16f, 3f);
            nvg.bezierTo(24f, 14f, 26f, 19f, 26f, 22f);
            nvg.bezierTo(26f, 28f, 21f, 30f, 16f, 30f);
            nvg.bezierTo(11f, 30f, 6f, 28f, 6f, 22f);
            nvg.bezierTo(6f, 19f, 8f, 14f, 16f, 3f);
            nvg.closePath();
            nvg.fillPath(0xE69AD4FF);
            nvg.circle(12f, 21f, 2.5f, 0xCCFFFFFF);
        });
        sprite(Kind.BANG, nvg -> {
            nvg.text(Fonts.UI_BOLD, "!", 11f, 0f, 30f, 0xFF000000);
            nvg.text(Fonts.UI_BOLD, "!", 9f, -2f, 30f, 0xFFFF4040);
        });
        sprite(Kind.ZEE, nvg -> {
            nvg.text(Fonts.UI_BOLD, "Z", 7f, 2f, 24f, 0xCC000000);
            nvg.text(Fonts.UI_BOLD, "Z", 5f, 0f, 24f, 0xFFFFFFFF);
        });
        sprite(Kind.BUBBLE, nvg -> {
            nvg.radialGradient(16f, 16f, 8f, 15f, 0x22CFE8FF, 0x99E8F4FF);
            nvg.circleOutline(16f, 16f, 14f, 1.5f, 0xDDFFFFFF);
            nvg.circle(11f, 11f, 3f, 0xCCFFFFFF);
        });
        sprite(Kind.EMERALD, nvg -> nvg.mcIcon(McIcons.of("minecraft:emerald"), 2f, 2f, 28f, 0xFFFFFFFF));
        sprite(Kind.GLINT, nvg -> star(nvg, 0xFFF0C8FF, 0xCCB070FF));
        sprite(Kind.QUESTION, nvg -> {
            nvg.text(Fonts.UI_BOLD, "?", 9f, 2f, 30f, 0xFF000000);
            nvg.text(Fonts.UI_BOLD, "?", 7f, 0f, 30f, 0xFFFFD84A);
        });
        sprite(Kind.YAWN, nvg -> {
            nvg.radialGradient(16f, 16f, 6f, 15f, 0xAAFFFFFF, 0x00FFFFFF);
            nvg.circleOutline(16f, 16f, 11f, 1.5f, 0x88FFFFFF);
        });
    }

    private void sprite(Kind kind, java.util.function.Consumer<dev.aether.renderer.NVGRenderer> draw) {
        PanelSurface s = new PanelSurface();
        s.render(32f, 32f, 2f, draw);
        sprites.put(kind, s);
    }

    private static void star(dev.aether.renderer.NVGRenderer nvg, int core, int glow) {
        nvg.radialGradient(16f, 16f, 1f, 12f, glow, glow & 0x00FFFFFF);
        nvg.beginPath();
        nvg.moveTo(16f, 2f);
        nvg.lineTo(18.5f, 13.5f);
        nvg.lineTo(30f, 16f);
        nvg.lineTo(18.5f, 18.5f);
        nvg.lineTo(16f, 30f);
        nvg.lineTo(13.5f, 18.5f);
        nvg.lineTo(2f, 16f);
        nvg.lineTo(13.5f, 13.5f);
        nvg.closePath();
        nvg.fillPath(core);
    }

    private static OrbitWorldRenderer.Quad billboard(Vector3d at, OrbitLayout.Camera cam, double size, int texture, float a) {
        Vector3d r = new Vector3d(cam.right()).mul(size / 2), u = new Vector3d(cam.up()).mul(size / 2);
        return new OrbitWorldRenderer.Quad(new Vector3d(at).sub(r).add(u), new Vector3d(at).add(r).add(u),
                new Vector3d(at).add(r).sub(u), new Vector3d(at).sub(r).sub(u), texture, a, 0f);
    }

    // a puff of particles of one kind around a point in farm blocks
    private void burst(float x, float y, float z, int count, Kind kind) {
        for (int i = 0; i < count; i++) {
            double a = Math.random() * Math.PI * 2;
            float r = (float) (0.2 + Math.random() * 0.35);
            float speed = kind == Kind.PUFF ? 0.6f + (float) Math.random() : 0.4f + (float) Math.random() * 0.6f;
            spawn(kind, x + (float) Math.cos(a) * r, y + (float) (Math.random() - 0.5) * (kind == Kind.PUFF ? 1.2f : 0.5f),
                    z + (float) Math.sin(a) * r, (float) Math.cos(a) * speed, 0.2f + (float) Math.random() * 0.6f,
                    (float) Math.sin(a) * speed, kind == Kind.PUFF ? -0.15f : 0f, 0.15f,
                    0.6f + (float) Math.random() * 0.5f, kind == Kind.PUFF ? 0.35f + (float) Math.random() * 0.3f : 0.2f);
        }
    }

    private void spawn(Kind kind, float x, float y, float z, float vx, float vy, float vz, float gravity, float drag,
                       float life, float size) {
        Particle p = new Particle();
        p.kind = kind;
        p.x = x;
        p.y = y;
        p.z = z;
        p.vx = vx;
        p.vy = vy;
        p.vz = vz;
        p.gravity = gravity;
        p.drag = drag;
        p.life = life;
        p.size = size;
        particles.add(p);
    }

    private static float backOut(float t) {
        float s = 1.70158f, u = t - 1f;
        return 1f + u * u * ((s + 1f) * u + s);
    }

    private static float smooth(float t) {
        t = clamp01(t);
        return t * t * (3 - 2 * t);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static Identifier mc(String path) {
        return Identifier.withDefaultNamespace(path);
    }

    @Override
    public void close() {
        for (SceneClone.Buffer b : buffers.values()) b.free();
        buffers.clear();
        for (PanelSurface s : sprites.values()) s.close();
        sprites.clear();
    }
}
