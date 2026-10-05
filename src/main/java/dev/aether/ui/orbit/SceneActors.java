package dev.aether.ui.orbit;

import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

// the farm acts out the module you are looking at, each as a little looping skit in the spirit of minecraft live:
// visitors queue up the path and trade, gold bars weigh you down, a crafting table drops in and you craft, flying
// pests chase you round the yard until you vacuum them up, a bed lands and you hop in with a nightcap, armour
// stands show off their sets. local space is the farm's, in blocks around your feet, +z up the path, the yard at +x
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
    private static final Identifier IRON = mc("textures/block/iron_block.png");
    private static final Identifier LIME = mc("textures/block/lime_concrete.png");
    private static final Identifier GRAY = mc("textures/block/gray_concrete.png");
    private static final Identifier BLACK = mc("textures/block/black_concrete.png");
    private static final Identifier PATH = mc("textures/block/dirt_path_top.png");
    private static final Identifier GRASS = mc("textures/block/grass_block_top.png");
    private static final Identifier EMERALD = mc("textures/item/emerald.png");
    private static final Identifier JUKE_TOP = mc("textures/block/jukebox_top.png");
    private static final Identifier JUKE_SIDE = mc("textures/block/jukebox_side.png");
    private static final Identifier SPRAYER = mc("textures/item/glass_bottle.png");
    private static final int GRASS_TINT = 0x91BD59;
    private static final String[] METALS = {"gold", "diamond", "netherite"};

    // the yard beside the path where the bed and the stands go, clear of the crops (see PresetGarden.yard)
    private static final float BED_X = 2.9f, BED_Z = -0.6f;
    private static final float TABLE_Z = 1.3f;

    // drop-ins fall for DROP seconds, then squash on landing for LAND
    private static final float DROP = 0.35f;
    private static final float LAND = 0.3f;

    private enum Step { LOOKING, WALKING, WAITING, LEAVING }

    // a visitor on the conveyor up the path: place 0 trades with you, then walks off and the rest shuffle up
    private static final class Villager {
        int place;
        Step step = Step.LOOKING;
        float age, stepAge, poof = -1f, trade, stride, landed = 9f;
        float x, z, q, yaw = (float) Math.PI;
        // the drawn head, body and limb angles ease toward each frame's targets so steps blend into each other
        final float[] eased = new float[8];
        boolean primed;
    }

    private static final class Pest {
        final int index;
        // which skyblock pest's head it wears
        int head;
        final Vector3f at = new Vector3f();
        final Vector3f last = new Vector3f();
        boolean shown;
        float shownFor, scale = 1f;

        Pest(int index) {
            this.index = index;
            this.head = index;
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
    private final List<Pest> lured = new ArrayList<>();
    private final List<Crafted> crafted = new ArrayList<>();
    private final SceneParticles particles = new SceneParticles();
    private final FarmSkits farm = new FarmSkits(particles);
    private final ExtraSkits extra = new ExtraSkits(particles);
    private final ModuleSkits modules;

    private String focus;
    private float scene;
    private float time;
    private Inputs in = Inputs.NONE;
    private float table = -1f;
    private float tableHit;
    private float bed = -1f;
    private float cap;
    private float vacuum;
    private int worn = -1;
    private float spawnCooldown;
    private final float[] stands = new float[3];
    private int bars;
    private float strikes;
    private float frameDt;
    private final Vector3f browRight = new Vector3f(), browLeft = new Vector3f();
    private float zeeTimer, sweatTimer, dustTimer, sparkleTimer, snoreTimer, questionTimer, glintTimer, breathTimer,
            suckTimer;

    SceneActors(IntFunction<Identifier> heads) {
        this.heads = heads;
        this.modules = new ModuleSkits(particles, heads);
    }

    // -- the skits ----------------------------------------------------------------------------------------------

    // advances every skit for the focused module and poses the player for it
    void update(float dt, float time, Inputs inputs, PlayerFigure figure) {
        this.time = time;
        this.frameDt = dt;
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
        if (visitors) {
            pose.facing = (float) Math.toDegrees(Math.atan2(FRONT_X, FRONT_Z));
            gold(dt, pose);
        }

        float shownFor = DROP + LAND;
        table = crafting ? (table < 0f ? 0f : table + dt) : (table < 0f ? -1f : Math.min(table, shownFor) - dt * 3f);
        if (!crafting && table < 0f) table = -1f;
        if (crafting && table - dt < DROP && table >= DROP) landed(0f, TABLE_Z);
        if (crafting) craft(dt, pose);

        bed = resting ? (bed < 0f ? 0f : bed + dt) : (bed < 0f ? -1f : Math.min(bed, shownFor) - dt * 3f);
        if (!resting && bed < 0f) bed = -1f;
        if (resting && bed - dt < DROP && bed >= DROP) landed(BED_X, BED_Z);
        if (resting) sleep(dt, pose);

        pests(dt, pestsOn ? clamp(in.pests(), 0, 8) : 0, pose);
        farm.update(dt, scene, time, focus, in.enabled(), pose);
        boolean dynamic = "Dynamic Pests".equals(focus);
        jukebox = dynamic ? (jukebox < 0f ? 0f : jukebox + dt) : (jukebox < 0f ? -1f : Math.min(jukebox, shownFor) - dt * 3f);
        if (!dynamic && jukebox < 0f) jukebox = -1f;
        if (dynamic && jukebox - dt < DROP && jukebox >= DROP) landed(JUKEBOX.x, JUKEBOX.z);
        dynamicPests(dt, dynamic, pose);
        extra.update(dt, scene, time, focus, pose);
        modules.update(dt, scene, time, focus, pose);
        boolean busy = visitors || crafting || pestsOn || resting || loadout || dynamic || FarmSkits.handles(focus)
                || ExtraSkits.handles(focus) || ModuleSkits.handles(focus);
        if (!busy) idle(pose);
        if (!pestsOn) vacuum = Math.max(0f, vacuum - dt * 4f);

        if (loadout) {
            if (scene - dt <= 0f) for (int i = 0; i < 3; i++) stands[i] = -0.2f * i;
            for (int i = 0; i < 3; i++) {
                float before = stands[i];
                stands[i] += dt;
                Vector3f at = standSpot(i);
                if (before < DROP && stands[i] >= DROP) landed(at.x, at.z);
            }
            loadout(pose);
        } else {
            for (int i = 0; i < 3; i++) stands[i] = Math.max(0f, Math.min(stands[i], shownFor) - dt * 3f);
        }

        particles.step(dt);
        for (Crafted c : crafted) c.age += dt;
    }

    // standing about with nothing to act out: now and then a shift of weight, a glance down, a stretch
    private void idle(PlayerFigure.Pose pose) {
        float c = time % 11f;
        if (c > 4f && c < 5.4f) {
            float s = (float) Math.sin(Math.PI * (c - 4f) / 1.4f);
            pose.roll += 4f * s;
            pose.x += 0.05f * s;
            pose.legs = 8f * s;
            pose.legsWeight = s;
        } else if (c > 7f && c < 7.8f) {
            pose.headPitch += 25f * (float) Math.sin(Math.PI * (c - 7f) / 0.8f);
        } else if (c > 9.2f && c < 10.6f) {
            float s = (float) Math.sin(Math.PI * (c - 9.2f) / 1.4f);
            pose.right = pose.left = -172f;
            pose.rightWeight = pose.leftWeight = s;
            pose.lean -= 8f * s;
            pose.squash -= 0.04f * s;
            pose.squint = s > 0.4f ? 1f : 0f;
        }
    }

    // something heavy lands: a poof and a spray of whatever it landed on
    private void landed(float x, float z) {
        particles.poof(x, 0.3f, z, 10, 0.5f);
        for (int i = 0; i < 12; i++) {
            float a = (float) (Math.random() * Math.PI * 2);
            ground(x + (float) Math.cos(a) * 0.5f, z + (float) Math.sin(a) * 0.5f, (float) Math.cos(a) * 0.12f, 0.2f,
                    (float) Math.sin(a) * 0.12f);
        }
    }

    // a crumb of the block under (x, z): the path near the middle, grass either side
    private void ground(float x, float z, float vx, float vy, float vz) {
        boolean path = Math.abs(x) < 1.5f;
        particles.terrain(path ? PATH : GRASS, x, 0.05f, z, vx, vy, vz, path ? 0xFFFFFF : GRASS_TINT);
    }

    // visitors pop in by the lawn's hedge, look about and queue up across the lawn toward you. the one at the front
    // grumbles over your offer, pays an emerald, cheers and wanders off while the rest shuffle up and a new one arrives
    // the line runs across the shot on your left, since the path ahead is hidden behind the front panel
    private static final float FRONT_X = 2.0f, FRONT_Z = 0.6f;
    private static final float SPAWN = 5.3f;
    private static final float TRADE = 2.9f;
    private static final float LINE_YAW = (float) (-Math.PI / 2);
    private static final float LEAVE_X = 0.7071f, LEAVE_Z = 0.7071f;

    private static float queueSpot(int place) {
        return 1.25f * place;
    }

    private static void along(Villager v) {
        v.x = FRONT_X + v.q;
        v.z = FRONT_Z;
    }

    private void updateVillagers(float dt, int count, PlayerFigure figure) {
        spawnCooldown -= dt;
        long staying = villagers.stream().filter(v -> v.step != Step.LEAVING && v.poof < 0f).count();
        if (staying < count && spawnCooldown <= 0f) {
            Villager v = new Villager();
            v.place = (int) staying;
            v.q = SPAWN;
            v.yaw = LINE_YAW;
            along(v);
            villagers.add(v);
            particles.poof(v.x, 0.9f, v.z, 14, 0.4f);
            spawnCooldown = 0.55f;
        }
        for (int i = villagers.size() - 1; i >= 0 && staying > count; i--) {
            Villager v = villagers.get(i);
            if (v.step == Step.LEAVING || v.poof >= 0f) continue;
            v.poof = 0f;
            particles.poof(v.x, 0.9f, v.z, 12, 0.4f);
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
                    float move = Math.min(Math.abs(v.q - to), 2.4f * dt);
                    v.q -= Math.signum(v.q - to) * move;
                    along(v);
                    float before = v.stride;
                    v.stride += move;
                    if ((int) (before * 1.1f) != (int) (v.stride * 1.1f)) ground(v.x, v.z, 0f, 0.1f, 0f);
                    if (Math.abs(v.q - to) < 1e-3f) {
                        next(v, Step.WAITING);
                        v.landed = 0f;
                        if (v.place == 0) {
                            figure.wave(v.x, v.z);
                            particles.happy(v.x, 1.9f, v.z, 5, 0.4f);
                        }
                    }
                }
                case WAITING -> {
                    if (Math.abs(v.q - queueSpot(v.place)) > 1e-3f) next(v, Step.WALKING);
                    else if (v.place == 0) {
                        float before = v.trade;
                        v.trade += dt;
                        if (before < 0.25f && v.trade >= 0.25f) particles.angry(v.x, 1.6f, v.z);
                        if (before < 0.9f && v.trade >= 0.9f) {
                            particles.item(EMERALD, v.x, 2.2f, v.z, 0.18f, 26, 0.22f);
                            particles.happy(v.x, 1.4f, v.z, 9, 0.45f);
                        }
                        if (v.trade >= TRADE) {
                            next(v, Step.LEAVING);
                            for (Villager o : villagers) if (o != v && o.step != Step.LEAVING && o.place > 0) o.place--;
                        }
                    }
                }
                case LEAVING -> {
                    // off across the lawn, skipping, then gone in a puff
                    float k = Math.min(1f, v.stepAge / 0.3f);
                    v.yaw = LINE_YAW + (float) (Math.atan2(LEAVE_X, LEAVE_Z) - LINE_YAW) * smooth(k);
                    if (v.stepAge > 0.3f) {
                        v.x += LEAVE_X * 2.2f * dt;
                        v.z += LEAVE_Z * 2.2f * dt;
                        v.stride += 2.2f * dt;
                    }
                    if (v.stepAge > 1.6f) {
                        v.poof = 0f;
                        particles.poof(v.x, 0.9f, v.z, 12, 0.4f);
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
        pose.squash = 0.03f + 0.07f * w;
        // heavy loads make you stagger a step every few seconds
        float stagger = (time % 3.1f) / 0.7f;
        if (w > 0.45f && stagger < 1f) {
            float s = (float) Math.sin(Math.PI * stagger);
            float side = (int) (time / 3.1f) % 2 == 0 ? 1f : -1f;
            pose.legs = (float) Math.sin(stagger * Math.PI * 2) * 22f * w;
            pose.legsWeight = 1f;
            pose.roll += s * 9f * w * side;
            pose.lean -= s * 8f;
            pose.x += s * 0.08f * side;
            pose.squint = s > 0.3f ? 1f : 0f;
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
            particles.spark(pose.x + (float) (Math.random() - 0.5) * 0.4f, 1.1f + bars * 0.05f, 0.45f, 0f, 0.02f, 0f,
                    0xFFD84A);
        }
        if (w > 0.35f) sweat(dt * w * 1.5f);
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
                particles.glyph('?', 0xFFFF55, 0.1f, 2.25f, 0f, 0f, 0.02f, 30, 0.22f);
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
            pose.squash = 0.05f * (1f - hit);
            float k = c * 4f;
            if ((int) k != (int) strikes) {
                tableHit = 0.12f;
                for (int i = 0; i < 4; i++) {
                    particles.terrain(i % 2 == 0 ? TABLE_FRONT : PLANKS, (float) (Math.random() - 0.5) * 0.6f, 1.02f,
                            TABLE_Z + (float) (Math.random() - 0.5) * 0.6f, (float) (Math.random() - 0.5) * 0.15f,
                            0.12f + (float) Math.random() * 0.1f, (float) (Math.random() - 0.5) * 0.15f, 0xFFFFFF);
                }
                particles.crit(0f, 1.05f, TABLE_Z, 2, false);
            }
            strikes = k;
        } else if (c < 2.1f) {
            if (strikes < 6.4f) {
                strikes = 7f;
                int n = crafted.size();
                crafted.add(new Crafted(1.0f + (n % 3) * 0.33f, (n / 3) * 0.3f, 1.0f + (n % 2) * 0.28f));
                if (crafted.size() > 9) {
                    crafted.clear();
                    particles.poof(1.3f, 0.3f, 1.2f, 10, 0.4f);
                }
                particles.crit(0f, 1.2f, TABLE_Z, 10, true);
            }
            // a little cheer
            float k = (c - 1.6f) / 0.5f;
            float air = clamp01((k - 0.2f) / 0.8f);
            float s = (float) Math.sin(Math.PI * air);
            pose.squash = k < 0.2f ? 0.2f : 0f;
            pose.right = pose.left = -170f;
            pose.rightWeight = pose.leftWeight = s;
            pose.y += s * 0.3f;
            pose.headPitch -= 25f;
        } else {
            strikes = 0f;
        }
        glintTimer -= dt;
        if (glintTimer <= 0f && !crafted.isEmpty()) {
            glintTimer = 0.4f;
            Crafted g = crafted.get((int) (Math.random() * crafted.size()));
            particles.crit(g.x, g.y + 0.3f, g.z, 1, true);
        }
    }

    // the bed drops beside you in the yard; you yawn, hop on, turn, pull a nightcap on and topple back to snore
    private void sleep(float dt, PlayerFigure.Pose pose) {
        float t = bed;
        float bedTop = 0.5625f;
        float bx = BED_X, bz = BED_Z + 0.9f;
        pose.look = 0f;
        if (t < 0.65f) {
            pose.headYaw = 55f * smooth(t / 0.3f);
            if (t > DROP) pose.y = (float) Math.sin(Math.PI * Math.min(1f, (t - DROP) / LAND)) * 0.12f;
            return;
        }
        if (t < 1.25f) {
            float k = (t - 0.65f) / 0.6f;
            float s = (float) Math.sin(Math.PI * k);
            pose.headYaw = 55f * (1f - smooth(k));
            pose.right = pose.left = -170f;
            pose.rightWeight = pose.leftWeight = s;
            pose.lean = -12f * s;
            pose.headPitch = -25f * s;
            pose.squint = s > 0.3f ? 1f : 0f;
            breathTimer -= dt;
            if (k > 0.2f && k < 0.7f && breathTimer <= 0f) {
                breathTimer = 0.08f;
                particles.cloud(0f, 1.62f, 0.3f, 0f, 0.02f, 0.04f, 0.6f);
            }
            return;
        }
        if (t < 1.5f) {
            float k = (t - 1.25f) / 0.25f;
            pose.facing = 90f * smooth(k);
            pose.right = pose.left = 40f;
            pose.rightWeight = pose.leftWeight = k;
            pose.lean = 18f * k;
            pose.squash = 0.25f * smooth(k);
            return;
        }
        if (t < 2.05f) {
            float k = (t - 1.5f) / 0.55f;
            float e = smooth(k);
            pose.facing = 90f;
            pose.x = bx * e;
            pose.z = bz * e;
            pose.y = bedTop * k + (float) Math.sin(Math.PI * k) * 1.2f;
            pose.right = pose.left = -165f;
            pose.rightWeight = pose.leftWeight = 1f;
            pose.legs = 30f * (float) Math.sin(Math.PI * k);
            pose.legsWeight = 1f;
            pose.lean = -8f;
            if (t - dt < 1.5f) for (int i = 0; i < 6; i++) ground(0f, 0f, (float) (Math.random() - 0.5) * 0.2f, 0.15f, -0.05f);
            return;
        }
        pose.x = bx;
        pose.z = bz;
        pose.y = bedTop;
        if (t < 2.4f) {
            // land with a bounce and hop round to face down the bed
            float k = (t - 2.05f) / 0.35f;
            if (t - dt < 2.05f) particles.poof(bx, bedTop + 0.1f, bz, 5, 0.3f);
            pose.facing = 90f * (1f - smooth(k));
            pose.y += (float) Math.sin(Math.PI * k) * 0.18f;
            pose.lean = 10f * (1f - k);
            return;
        }
        if (t < 3.0f) {
            // both hands pull the nightcap on
            float k = (t - 2.4f) / 0.6f;
            if (cap == 0f && k > 0.35f) particles.poof(bx, bedTop + 2.0f, bz, 4, 0.15f);
            if (k > 0.35f) cap = Math.min(1f, cap + dt / 0.25f);
            float s = (float) Math.sin(Math.PI * k);
            pose.right = pose.left = -175f;
            pose.rightWeight = pose.leftWeight = s;
            pose.headPitch = 15f * s;
            return;
        }
        cap = 1f;
        // topple straight back like a plank, bounce once on the mattress, then snore
        float k = OrbitRig.clamp((t - 3.0f) / 0.45f, 0f, 1f);
        float lie = k * k;
        pose.lie = lie;
        pose.y = bedTop * (1f - lie);
        float after = t - 3.45f;
        if (after > 0f && after < 0.4f) pose.y += (float) Math.sin(Math.PI * after / 0.4f) * 0.07f;
        if (t - dt < 3.45f && t >= 3.45f) particles.poof(bx, 0.75f, bz - 0.8f, 6, 0.4f);
        if (k < 1f) {
            pose.right = pose.left = -40f * k;
            pose.rightWeight = pose.leftWeight = 1f;
            return;
        }
        zeeTimer -= dt;
        if (zeeTimer <= 0f) {
            zeeTimer = 1.0f;
            particles.glyph('Z', 0xFFFFFF, bx + 0.1f, 1.0f, bz - 1.55f, 0.012f, 0.025f, 50, 0.16f);
        }
        snoreTimer -= dt;
        if (snoreTimer <= 0f) {
            snoreTimer = 2.4f;
            particles.bubble(bx - 0.05f, 0.95f, bz - 1.7f, 2.5f);
        }
    }

    // the pest loop: they appear in front of you, you jump out of your skin, spin and run a lap of the yard with them
    // on your tail, skid back onto your spot, turn round on them, vacuum every one up, catch your breath, repeat
    private static final float LOOP = 10f;
    private static final float LAP_R = 1.6f;
    private static final float RUN_FROM = 1.45f, RUN_TO = 4.3f;
    private static final float SUCK = 5.0f;

    // where the lap puts you at angle theta, and your heading there in degrees
    private static Vector3f lap(float theta) {
        return new Vector3f(LAP_R - LAP_R * (float) Math.cos(theta), 0f, -LAP_R * (float) Math.sin(theta));
    }

    private float lapAngle(float c) {
        float k = clamp01((c - RUN_FROM) / (RUN_TO - RUN_FROM));
        // ease in and out a little so the start and the skid aren't instant
        float e = k * k * (3f - 2f * k) * 0.35f + k * 0.65f;
        return e * (float) Math.PI * 2f;
    }

    private void pests(float dt, int count, PlayerFigure.Pose pose) {
        while (pests.size() < 8) pests.add(new Pest(pests.size()));
        if (count == 0) {
            for (Pest p : pests) {
                if (p.shown) particles.poof(p.at.x, p.at.y, p.at.z, 5, 0.2f);
                p.shown = false;
            }
            return;
        }
        float c = scene % LOOP;
        float fear = 0.6f + 0.4f * (count - 1) / 7f;
        float suckEnd = SUCK + 0.35f + 0.26f * count;
        float theta = lapAngle(c);
        Vector3f spawnAt = new Vector3f(0.8f, 1.5f, 2.8f);
        boolean running = c >= RUN_FROM && c < RUN_TO;
        if (c >= RUN_FROM) {
            Vector3f at = lap(theta);
            pose.x = at.x;
            pose.z = at.z;
        }

        if (c < 0.85f) {
            // unaware: looking about as they pop in
            pose.headYaw = (float) Math.sin(c * 5) * 25f;
            pose.look = 0.4f;
        } else if (c < 1.2f) {
            float k = (c - 0.85f) / 0.35f;
            if (c - dt < 0.85f) particles.glyph('!', 0xFF5555, 0f, 2.3f, 0f, 0f, 0.03f, 24, 0.3f);
            // a flinch down, then up out of his skin
            float air = clamp01((k - 0.18f) / 0.82f);
            pose.squash = k < 0.18f ? 0.3f : 0f;
            pose.y = (float) Math.sin(Math.PI * air) * 0.6f * fear;
            pose.right = pose.left = -172f;
            pose.rightWeight = pose.leftWeight = 1f;
            pose.lean = -20f * fear;
            pose.headPitch = -12f;
            pose.legs = 22f;
            pose.legsWeight = 1f;
        } else if (c < RUN_FROM) {
            // whirl round to run, crouching to push off
            float k = (c - 1.2f) / (RUN_FROM - 1.2f);
            pose.facing = 180f * smooth(k);
            pose.lean = 20f * k;
            pose.right = pose.left = 30f * k;
            pose.rightWeight = pose.leftWeight = 1f;
        } else if (running) {
            // the lap: heading follows the circle, arms pumping, glancing back now and then
            pose.facing = 180f - (float) Math.toDegrees(theta);
            float stride = theta * LAP_R * 3.2f;
            pose.legs = (float) Math.sin(stride) * 55f;
            pose.legsWeight = 1f;
            pose.y = Math.abs((float) Math.sin(stride)) * 0.1f;
            pose.lean = 20f;
            pose.roll = -8f;
            pose.right = (float) Math.sin(stride) * 75f;
            pose.left = -(float) Math.sin(stride) * 75f;
            pose.rightWeight = pose.leftWeight = 1f;
            pose.look = 0f;
            float glance = (float) Math.max(0, Math.sin(c * 4.2f)) * fear;
            pose.headYaw = 80f * glance;
            pose.turn = 25f * glance;
            pose.headPitch = -5f;
            sweat(dt);
            dust(dt, pose.x, pose.z);
        } else if (c < SUCK) {
            // skid onto the spot, spin round to face them and whip the vacuum out
            float k = clamp01((c - RUN_TO) / (SUCK - RUN_TO));
            pose.facing = -180f + 180f * smooth(clamp01(k / 0.55f));
            pose.lean = -14f * (1f - k);
            pose.right = -90f * smooth(clamp01((k - 0.4f) / 0.6f));
            pose.left = -80f * smooth(clamp01((k - 0.5f) / 0.5f));
            pose.rightWeight = pose.leftWeight = 1f;
            pose.look = 0f;
            pose.headPitch = -6f;
            vacuum = Math.max(vacuum, smooth(clamp01((k - 0.4f) / 0.5f)));
            if (c - dt < RUN_TO) for (int i = 0; i < 8; i++) ground(0f, 0f, (float) (Math.random() - 0.5) * 0.2f, 0.2f, -0.1f);
        } else if (c < suckEnd) {
            // braced, shaking with the suction, eyes screwed shut
            float shake = (float) Math.sin(c * 60) * 2f;
            pose.squint = 1f;
            pose.squash = 0.06f;
            pose.right = -90f + shake;
            pose.left = -80f - shake;
            pose.rightWeight = pose.leftWeight = 1f;
            pose.lean = -8f;
            pose.legs = 14f;
            pose.legsWeight = 1f;
            pose.roll = shake * 0.6f;
            pose.look = 0f;
            pose.headPitch = -4f;
            vacuum = 1f;
            suckTimer -= dt;
            if (suckTimer <= 0f) {
                suckTimer = 0.04f;
                Vector3f tip = nozzle(pose);
                float a = (float) (Math.random() * Math.PI * 2);
                float r = 0.5f + (float) Math.random() * 0.4f;
                float sx = tip.x + (float) Math.cos(a) * r, sy = tip.y + (float) Math.sin(a) * r, sz = tip.z + 0.9f;
                particles.cloud(sx, sy, sz, (tip.x - sx) * 0.12f, (tip.y - sy) * 0.12f, (tip.z - sz) * 0.12f, 0.5f);
            }
        } else if (c < suckEnd + 0.35f) {
            float k = (c - suckEnd) / 0.35f;
            vacuum = 1f - smooth(k);
            pose.right = -90f * (1f - k);
            pose.left = -80f * (1f - k);
            pose.rightWeight = pose.leftWeight = 1f;
        } else if (c < LOOP - 0.9f) {
            // hands on knees, puffing, then a big breath out
            vacuum = 0f;
            float k = (c - suckEnd - 0.35f) / (LOOP - 0.9f - suckEnd - 0.35f);
            float bend = k < 0.75f ? 1f : 1f - smooth((k - 0.75f) / 0.25f);
            pose.lean = 34f * bend - 10f * (1f - bend);
            pose.right = pose.left = -20f * bend;
            pose.rightWeight = pose.leftWeight = 1f;
            pose.headPitch = (-20f + (float) Math.sin(c * 14) * 6f) * bend - 15f * (1f - bend);
            pose.y = (float) Math.sin(c * 14) * 0.015f * bend;
            pose.squash = (0.04f + (float) Math.sin(c * 14) * 0.03f) * bend;
            pose.squint = k < 0.35f ? 1f : 0f;
            pose.look = 0f;
            breathTimer -= dt;
            if (breathTimer <= 0f) {
                breathTimer = bend > 0.5f ? 0.38f : 0.06f;
                float mouthY = bend > 0.5f ? 1.25f : 1.6f;
                particles.cloud(0f, mouthY, 0.35f, 0f, bend > 0.5f ? -0.01f : 0.03f, 0.05f, bend > 0.5f ? 0.5f : 0.8f);
            }
            sweat(dt * 0.6f);
        } else {
            // phew: wipe the brow
            float k = (c - (LOOP - 0.9f)) / 0.9f;
            pose.right = -140f + (float) Math.sin(k * Math.PI * 3) * 15f;
            pose.rightWeight = (float) Math.sin(Math.PI * k);
            pose.tilt = 10f;
        }

        Vector3f player = new Vector3f(pose.x, 0f, pose.z);
        for (Pest p : pests) {
            float sucked = SUCK + 0.15f + 0.26f * p.index;
            boolean want = p.index < count && c >= p.index * 0.08f && c < sucked + 0.4f;
            if (want && !p.shown) {
                p.at.set(spawnAt).add(ring(p.index, count, 0.9f));
                p.last.set(p.at);
                p.shownFor = 0f;
                p.scale = 1f;
                particles.poof(p.at.x, p.at.y, p.at.z, 7, 0.25f);
            }
            if (!want && p.shown) {
                // gone into the vacuum with a pop
                Vector3f tip = nozzle(pose);
                particles.poof(tip.x, tip.y, tip.z, 4, 0.1f);
                particles.crit(tip.x, tip.y, tip.z, 4, false);
            }
            p.shown = want;
            if (!want) continue;
            p.shownFor += dt;
            Vector3f target;
            float follow = 1f - (float) Math.exp(-dt * (4.5f + p.index * 0.35f));
            if (c < RUN_FROM) {
                target = new Vector3f(spawnAt).add(ring(p.index, count, 0.85f));
            } else if (c < RUN_TO + 0.2f) {
                // they chase along your trail, each a little further back
                float lag = (1.5f + 0.55f * p.index) / LAP_R;
                float back = theta - lag;
                target = back >= 0f ? lap(back) : new Vector3f(spawnAt).lerp(lap(0f), clamp01(1f + back / 1.5f));
                target.add(0f, 1.45f + (float) Math.sin(c * 6 + p.index) * 0.12f, 0f).add(ring(p.index, count, 0.25f));
            } else if (c < sucked) {
                // they bunch up in front of you as you turn on them
                target = new Vector3f(player).add(0f, 1.4f, 2.0f).add(ring(p.index, count, 0.75f));
                if (c > SUCK) {
                    // the pull: drawn sideways toward the nozzle, wobbling harder as their turn comes
                    float pull = clamp01((c - SUCK) / (sucked - SUCK)) * 0.35f;
                    target.lerp(nozzle(pose), pull);
                    target.add((float) Math.sin(c * 30 + p.index) * 0.05f, 0f, 0f);
                }
            } else {
                float k = clamp01((c - sucked) / 0.4f);
                Vector3f tip = nozzle(pose);
                float spin = k * 12f;
                target = new Vector3f(tip).add((float) Math.cos(spin) * 0.3f * (1f - k), (float) Math.sin(spin) * 0.3f * (1f - k), 0f);
                follow = 1f - (float) Math.exp(-dt * 18f);
                p.scale = 1f - k * 0.9f;
            }
            target.y += (float) Math.sin(time * 5.1f + p.index) * 0.08f;
            p.last.set(p.at);
            p.at.lerp(target, follow);
        }
    }

    // dynamic pests: a jukebox lands, you put a vinyl on, spray all round you, and the pests that vinyl draws
    // come flying in to circle you; when the record ends it pops back out and the next one goes on
    private static final Vector3f JUKEBOX = new Vector3f(1.7f, 0f, 1.4f);
    private static final String[] DISCS = {"13", "cat", "blocks", "chirp", "far", "mall"};
    private static final int[] SPRAY_TINT = {0x7FE36A, 0xC26BFF, 0xFFD24A, 0x6BD8FF, 0xFF7A6B, 0xB8FF6B};
    private static final float DYN_LOOP = 9f;

    private float jukebox = -1f, jukeHit, noteTimer, discAt = -1f, discBack = -1f;
    private boolean disc, spraying;
    private int vinyl;

    private void dynamicPests(float dt, boolean on, PlayerFigure.Pose pose) {
        while (lured.size() < 4) lured.add(new Pest(lured.size()));
        jukeHit = Math.max(0f, jukeHit - dt);
        if (!on) {
            for (Pest p : lured) {
                if (p.shown) particles.poof(p.at.x, p.at.y, p.at.z, 5, 0.2f);
                p.shown = false;
            }
            disc = spraying = false;
            discAt = discBack = -1f;
            return;
        }
        float c = scene % DYN_LOOP;
        vinyl = (int) (scene / DYN_LOOP) % DISCS.length;
        float face = (float) Math.toDegrees(Math.atan2(JUKEBOX.x, JUKEBOX.z));
        pose.facing = face;
        pose.look = 0.3f;
        disc = c >= 0.7f && c < 1.5f;
        spraying = c >= 2.4f && c < 4.6f;
        if (c < 0.7f) {
            pose.headYaw = 0f;
        } else if (c < 1.5f) {
            // the vinyl comes out and up for a look
            float k = (c - 0.7f) / 0.8f;
            pose.right = -150f * smooth(k / 0.5f);
            pose.rightWeight = 1f;
            pose.headPitch = -18f * smooth(k / 0.5f);
        } else if (c < 1.9f) {
            // in it goes
            float k = (c - 1.5f) / 0.4f;
            if (c - dt < 1.5f) discAt = 0f;
            pose.right = -150f + 90f * smooth(k);
            pose.rightWeight = 1f;
            pose.lean = 10f * (float) Math.sin(Math.PI * k);
        } else if (c < 2.4f) {
            // a little nod to the music
            pose.headPitch = (float) Math.sin(c * 14) * 10f;
            pose.y = Math.abs((float) Math.sin(c * 7)) * 0.05f;
        } else if (c < 4.6f) {
            // spin round, misting the air with the vinyl's spray
            float k = (c - 2.4f) / 2.2f;
            pose.facing = face + 360f * smooth(k);
            pose.right = -80f + (float) Math.sin(time * 20) * 3f;
            pose.rightWeight = 1f;
            pose.squint = 0f;
            double f = Math.toRadians(pose.facing);
            float fx = (float) Math.sin(f), fz = (float) Math.cos(f);
            for (int i = 0; i < 2; i++) {
                particles.spell(fx * 0.6f - fz * 0.3f, 1.05f, fz * 0.6f + fx * 0.3f, fx * 0.12f + (float) (Math.random() - 0.5) * 0.04f,
                        0.01f + (float) Math.random() * 0.02f, fz * 0.12f + (float) (Math.random() - 0.5) * 0.04f,
                        SPRAY_TINT[vinyl]);
            }
        } else if (c < 5.0f) {
            // waiting, looking round for them
            pose.facing = face + 360f;
            pose.headYaw = (float) Math.sin((c - 4.6f) * 12) * 40f;
        } else if (c < 7.6f) {
            pose.facing = face + 360f;
            float k = c - 5.0f;
            if (k < 0.6f) {
                // they're here: a jump for joy
                float air = clamp01((k - 0.1f) / 0.5f);
                pose.squash = k < 0.1f ? 0.25f : 0f;
                pose.y = (float) Math.sin(Math.PI * air) * 0.35f;
                pose.right = pose.left = -165f * (float) Math.sin(Math.PI * air);
                pose.rightWeight = pose.leftWeight = 1f;
            } else {
                // following them round with fist pumps
                pose.right = -120f - 40f * Math.abs((float) Math.sin(c * 6));
                pose.rightWeight = 1f;
                pose.headYaw = (float) Math.sin(c * 1.6f) * 50f;
                pose.headPitch = -12f;
            }
        } else {
            pose.facing = face + 360f;
            if (c - dt < 7.6f) discBack = 0f;
            pose.right = -60f * smooth(clamp01((c - 7.6f) / 0.3f));
            pose.rightWeight = 1f;
        }
        if (discAt >= 0f) {
            discAt += dt;
            if (discAt >= 0.4f) {
                discAt = -1f;
                jukeHit = 0.15f;
                for (int i = 0; i < 3; i++) particles.note(JUKEBOX.x, 1.2f, JUKEBOX.z);
            }
        }
        if (discBack >= 0f) {
            discBack += dt;
            if (discBack >= 0.45f) discBack = -1f;
        }
        boolean playing = c >= 1.9f && c < 7.6f;
        noteTimer -= dt;
        if (playing && noteTimer <= 0f) {
            noteTimer = 0.35f;
            particles.note(JUKEBOX.x + (float) (Math.random() - 0.5) * 0.4f, 1.15f, JUKEBOX.z + (float) (Math.random() - 0.5) * 0.4f);
        }
        for (Pest p : lured) {
            p.head = vinyl;
            float due = 5.0f + p.index * 0.15f;
            boolean want = c >= due && c < 7.6f;
            double a = p.index * Math.PI / 2 + c * (1.3 + p.index * 0.15);
            Vector3f target = new Vector3f((float) Math.cos(a) * (1.8f + 0.2f * p.index), 1.4f + (float) Math.sin(c * 3 + p.index) * 0.25f,
                    (float) Math.sin(a) * (1.8f + 0.2f * p.index));
            if (want && !p.shown) {
                // in from out over the field
                p.at.set(target).mul(2.2f, 1f, 2.2f).add(0f, 0.8f, 0f);
                p.last.set(p.at);
                p.shownFor = 0f;
                p.scale = 1f;
                particles.poof(p.at.x, p.at.y, p.at.z, 7, 0.25f);
            }
            if (!want && p.shown) particles.poof(p.at.x, p.at.y, p.at.z, 7, 0.25f);
            p.shown = want;
            if (!want) continue;
            p.shownFor += dt;
            p.last.set(p.at);
            p.at.lerp(target, 1f - (float) Math.exp(-dt * 3.5f));
        }
    }

    // where the vacuum's mouth is, held out in front of you
    private static Vector3f nozzle(PlayerFigure.Pose pose) {
        double f = Math.toRadians(pose.facing);
        float fx = (float) Math.sin(f), fz = (float) Math.cos(f);
        return new Vector3f(pose.x + fx * 1.35f - fz * 0.3f, 1.15f, pose.z + fz * 1.35f + fx * 0.3f);
    }

    private static Vector3f ring(int index, int count, float radius) {
        double a = index * Math.PI * 2 / Math.max(1, count);
        return new Vector3f((float) Math.cos(a) * radius, (float) Math.sin(a * 2) * 0.2f, (float) Math.sin(a) * radius);
    }

    // three stands land in the yard; one at a time spins to show off its set, which jumps onto you for a moment
    private static final float SHOWCASE = 2.6f;

    private void loadout(PlayerFigure.Pose pose) {
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
                particles.totem(0f, 1.1f, 0f, 30);
                particles.poof(at.x, 1.0f, at.z, 6, 0.3f);
            } else {
                particles.poof(0f, 1.0f, 0f, 6, 0.3f);
                particles.happy(at.x, 1.2f, at.z, 6, 0.4f);
            }
            worn = wearing;
        }
        if (wearing < 0) {
            pose.headYaw = 45f;
            pose.look = 0.3f;
            return;
        }
        float w = k - 0.75f;
        if (w < 0.6f) {
            // arms up in triumph
            float air = clamp01((w - 0.08f) / 0.52f);
            float s = (float) Math.sin(Math.PI * air);
            pose.squash = w < 0.08f ? 0.25f : 0f;
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
        return new Vector3f(3.4f + 1.55f * i, 0f, 1.9f);
    }

    // a bead forms on one side of the head, runs down and drips off; heavier work, more often
    private void sweat(float dt) {
        sweatTimer -= dt;
        if (sweatTimer > 0f) return;
        sweatTimer = 0.35f + (float) Math.random() * 0.25f;
        boolean leftSide = Math.random() < 0.5;
        Vector3f side = new Vector3f(browLeft).sub(browRight);
        if (side.lengthSquared() < 1e-6f) return;
        side.normalize(leftSide ? 0.025f : -0.025f);
        particles.sweat(leftSide ? browLeft : browRight, side.x, side.z);
    }

    private void dust(float dt, float x, float z) {
        dustTimer -= dt;
        if (dustTimer > 0f) return;
        dustTimer = 0.07f;
        ground(x + (float) (Math.random() - 0.5) * 0.3f, z + (float) (Math.random() - 0.5) * 0.3f, 0f, 0.12f, 0f);
    }

    // -- geometry -----------------------------------------------------------------------------------------------

    // fills the entity buffers in the scene renderer's space; local maps the farm's blocks into it and right / up
    // are the camera's axes there, for the particles
    List<Draw> build(Matrix4f local, Vector3d camLocal, Vector3f right, Vector3f up, PlayerFigure figure) {
        for (SceneClone.Buffer b : buffers.values()) b.reset();
        for (Villager v : villagers) villagerFrame(v, local);
        List<Pest> flying = new ArrayList<>(pests);
        flying.addAll(lured);
        for (Pest p : flying) {
            if (!p.shown) continue;
            Identifier skin = heads.apply(p.head);
            if (skin == null) continue;
            float grow = backOut(Math.min(1f, p.shownFor / 0.35f)) * 0.8f * p.scale;
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
            Matrix4f m = drop(new Matrix4f(local).translate(0f, 0f, TABLE_Z), table, "Auto Supercraft".equals(focus))
                    .scale(1f + squash * 0.5f, 1f - squash, 1f + squash * 0.5f).translate(-0.5f, 0f, -0.5f);
            cube(m, 0, 0, 0, 1, 1, 1, TABLE_TOP, PLANKS, TABLE_FRONT, TABLE_SIDE, TABLE_SIDE, TABLE_FRONT);
        }
        for (Crafted c : crafted) {
            float k = Math.min(1f, c.age / 0.6f);
            float x = c.x * smooth(k), z = TABLE_Z + (c.z - TABLE_Z) * smooth(k);
            float y = 1.0f + (c.y - 1.0f) * k + (float) Math.sin(Math.PI * k) * 1.0f;
            float land = c.age > 0.6f && c.age < 0.8f ? (float) Math.sin(Math.PI * (c.age - 0.6f) / 0.2f) * 0.2f : 0f;
            Matrix4f m = new Matrix4f(local).translate(x, y, z).rotateY((1f - k) * 6f + c.x * 3f)
                    .scale(0.3f * (1f + land * 0.5f), 0.3f * (1f - land), 0.3f * (1f + land * 0.5f)).translate(-0.5f, 0f, -0.5f);
            cube(m, 0, 0, 0, 1, 1, 1, HAY_TOP, HAY_TOP, HAY_SIDE, HAY_SIDE, HAY_SIDE, HAY_SIDE);
        }
        if (bed >= 0f) {
            Matrix4f m = drop(new Matrix4f(local).translate(BED_X, 0f, BED_Z), bed, "Dynamic Rest".equals(focus));
            bedHalf(buffer(BED), new Matrix4f(m).translate(-0.5f, 0f, 0f), 0);
            bedHalf(buffer(BED), new Matrix4f(m).translate(-0.5f, 0f, 1f), 22);
        }
        if (bars > 0) goldBars(figure);
        if (cap > 0f) nightcap(figure.headFrame(), backOut(cap));
        if (vacuum > 0.01f) vacuum(figure.rightArmFrame(), backOut(vacuum));
        if (jukebox >= 0f) {
            float squash = jukeHit > 0f ? (float) Math.sin(Math.PI * jukeHit / 0.15f) * 0.15f : 0f;
            Matrix4f m = drop(new Matrix4f(local).translate(JUKEBOX.x, 0f, JUKEBOX.z), jukebox, "Dynamic Pests".equals(focus))
                    .scale(1f + squash * 0.5f, 1f - squash, 1f + squash * 0.5f).translate(-0.5f, 0f, -0.5f);
            cube(m, 0, 0, 0, 1, 1, 1, JUKE_TOP, JUKE_SIDE, JUKE_SIDE, JUKE_SIDE, JUKE_SIDE, JUKE_SIDE);
            Identifier record = mc("textures/item/music_disc_" + DISCS[vinyl] + ".png");
            Matrix4f toFarm = new Matrix4f(local).invert();
            Vector3f hand = toFarm.transformPosition(figure.rightArmFrame().transformPosition(0f, -11f, 0f, new Vector3f()));
            Vector3f slot = new Vector3f(JUKEBOX.x, 1.05f, JUKEBOX.z);
            if (disc) FarmSkits.billboard(buffer(record), local, hand.x, hand.y + 0.1f, hand.z, 0.2f, right, up);
            if (discAt >= 0f || discBack >= 0f) {
                float k = discAt >= 0f ? clamp01(discAt / 0.4f) : 1f - clamp01(discBack / 0.45f);
                Vector3f at = new Vector3f(hand).lerp(slot, smooth(k));
                at.y += (float) Math.sin(Math.PI * k) * 0.5f;
                FarmSkits.billboard(buffer(record), local, at.x, at.y, at.z, 0.2f, right, up);
            }
            if (spraying) FarmSkits.item(buffer(SPRAYER), figure.rightArmFrame(), 1f);
        }
        // the temples in farm blocks, where sweat beads cling
        Matrix4f toFarm = new Matrix4f(local).invert();
        toFarm.transformPosition(figure.headFrame().transformPosition(-4.4f, 5f, 1.5f, browRight));
        toFarm.transformPosition(figure.headFrame().transformPosition(4.4f, 5f, 1.5f, browLeft));
        farm.build(local, figure, this::buffer, right, up);
        extra.build(local, figure, this::buffer, right, up);
        modules.build(local, figure, this::buffer, right, up);
        particles.build(this::buffer, local, right, up);
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

    // a pest vacuum in the right hand: iron canister under the forearm with a lime band, a hose out past the fist
    // and a black intake. built in the arm's frame, where -y runs down the arm to the hand
    private void vacuum(Matrix4f arm, float s) {
        Matrix4f m = new Matrix4f(arm).translate(0f, -10f, -1f).scale(s).translate(0f, 10f, 1f);
        float px = 1f / 16f;
        cubeInto(buffer(IRON), m, -2.5f, -13f, -6.5f, 2.5f, -5f, -2f, px);
        cubeInto(buffer(LIME), m, -2.7f, -10f, -6.7f, 2.7f, -8.5f, -1.8f, px);
        cubeInto(buffer(GRAY), m, -1f, -23f, -3f, 1f, -11f, -1f, px);
        cubeInto(buffer(BLACK), m, -2.5f, -26f, -4.5f, 2.5f, -23f, 0.5f, px);
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
        float[] target = {headYaw, headPitch, headRoll, turn, roll, lean, legs, arms};
        float head = 1f - (float) Math.exp(-frameDt * 11f), limbs = 1f - (float) Math.exp(-frameDt * 22f);
        for (int i = 0; i < 8; i++) {
            if (!v.primed) v.eased[i] = target[i];
            else v.eased[i] += (target[i] - v.eased[i]) * (i >= 6 ? limbs : head);
        }
        v.primed = true;
        headYaw = v.eased[0];
        headPitch = v.eased[1];
        headRoll = v.eased[2];
        turn = v.eased[3];
        roll = v.eased[4];
        lean = v.eased[5];
        legs = v.eased[6];
        arms = v.eased[7];
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
    static void face(SceneClone.Buffer out, Matrix4f m, float ax, float ay, float az, float bx, float by, float bz,
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

    // vanilla's default armour stand pose, in radians: arms held a little forward and out, legs barely apart
    private static final float R_ARM_X = rad(-15), R_ARM_Z = rad(10), L_ARM_X = rad(-10), L_ARM_Z = rad(-10);
    private static final float R_LEG = rad(1), L_LEG = rad(-1);

    // the armour stand exactly as ArmorStandModel builds it, in its default pose
    static void stand(SceneClone.Buffer out, Matrix4f m) {
        ModelBoxes.part(out, m, 64, 64, false, 0, 1, 0, 0, 0, 0, -1, -7, -1, 2, 7, 2, 0, 0, 0f);
        ModelBoxes.part(out, m, 64, 64, false, 0, 0, 0, 0, 0, 0, -6, 0, -1.5f, 12, 3, 3, 0, 26, 0f);
        ModelBoxes.part(out, m, 64, 64, false, -5, 2, 0, R_ARM_X, 0, R_ARM_Z, -2, -2, -1, 2, 12, 2, 24, 0, 0f);
        ModelBoxes.part(out, m, 64, 64, true, 5, 2, 0, L_ARM_X, 0, L_ARM_Z, 0, -2, -1, 2, 12, 2, 32, 16, 0f);
        ModelBoxes.part(out, m, 64, 64, false, -1.9f, 12, 0, R_LEG, 0, R_LEG, -1, 0, -1, 2, 11, 2, 8, 0, 0f);
        ModelBoxes.part(out, m, 64, 64, true, 1.9f, 12, 0, L_LEG, 0, L_LEG, -1, 0, -1, 2, 11, 2, 40, 16, 0f);
        ModelBoxes.part(out, m, 64, 64, false, 0, 0, 0, 0, 0, 0, -3, 3, -1, 2, 7, 2, 16, 0, 0f);
        ModelBoxes.part(out, m, 64, 64, false, 0, 0, 0, 0, 0, 0, 1, 3, -1, 2, 7, 2, 48, 16, 0f);
        ModelBoxes.part(out, m, 64, 64, false, 0, 0, 0, 0, 0, 0, -4, 10, -1, 8, 2, 2, 0, 48, 0f);
        ModelBoxes.part(out, m, 64, 64, false, 0, 12, 0, 0, 0, 0, -6, 11, -6, 12, 1, 12, 0, 32, 0f);
    }

    // a full set the way ArmorStandArmorModel fits it: helmet with its overlay, chestplate and boots from the outer
    // layer, leggings from the inner one, the limbs following the stand's pose
    static void armour(SceneClone.Buffer outer, SceneClone.Buffer inner, Matrix4f m) {
        ModelBoxes.part(outer, m, 64, 32, false, 0, 1, 0, 0, 0, 0, -4, -8, -4, 8, 8, 8, 0, 0, 1f);
        ModelBoxes.part(outer, m, 64, 32, false, 0, 1, 0, 0, 0, 0, -4, -8, -4, 8, 8, 8, 32, 0, 1.5f);
        ModelBoxes.part(outer, m, 64, 32, false, 0, 0, 0, 0, 0, 0, -4, 0, -2, 8, 12, 4, 16, 16, 1f);
        ModelBoxes.part(outer, m, 64, 32, false, -5, 2, 0, R_ARM_X, 0, R_ARM_Z, -3, -2, -2, 4, 12, 4, 40, 16, 1f);
        ModelBoxes.part(outer, m, 64, 32, true, 5, 2, 0, L_ARM_X, 0, L_ARM_Z, -1, -2, -2, 4, 12, 4, 40, 16, 1f);
        ModelBoxes.part(outer, m, 64, 32, false, -1.9f, 11, 0, R_LEG, 0, R_LEG, -2, 0, -2, 4, 12, 4, 0, 16, 0.9f);
        ModelBoxes.part(outer, m, 64, 32, true, 1.9f, 11, 0, L_LEG, 0, L_LEG, -2, 0, -2, 4, 12, 4, 0, 16, 0.9f);
        ModelBoxes.part(inner, m, 64, 32, false, 0, 0, 0, 0, 0, 0, -4, 0, -2, 8, 12, 4, 16, 16, 0.5f);
        ModelBoxes.part(inner, m, 64, 32, false, -1.9f, 11, 0, R_LEG, 0, R_LEG, -2, 0, -2, 4, 12, 4, 0, 16, 0.4f);
        ModelBoxes.part(inner, m, 64, 32, true, 1.9f, 11, 0, L_LEG, 0, L_LEG, -2, 0, -2, 4, 12, 4, 0, 16, 0.4f);
    }

    private static float rad(float degrees) {
        return (float) Math.toRadians(degrees);
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
        particles.clear();
    }
}
