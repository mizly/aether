package dev.aether.ui.orbit;

import dev.aether.config.AetherConfig;
import dev.aether.renderer.McIcons;
import dev.aether.ui.util.Fonts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// the farm acts out the module you are looking at: visitors walk up for Auto Visitor and you hold more gold the more
// you let them spend, a crafting table appears for Auto Supercraft, silverfish swarm you for Pest Manager, you go to
// bed for Dynamic Rest and three armour stands show up for Auto Loadout. local space is the farm's, around your feet
final class SceneActors implements AutoCloseable {
    record Draw(Identifier texture, SceneClone.Buffer buffer) {
    }

    private static final Identifier VILLAGER = id("textures/entity/villager/villager.png");
    private static final Identifier VILLAGER_PLAINS = id("textures/entity/villager/type/plains.png");
    private static final Identifier VILLAGER_FARMER = id("textures/entity/villager/profession/farmer.png");
    private static final Identifier SILVERFISH = id("textures/entity/silverfish/silverfish.png");
    private static final Identifier STAND = id("textures/entity/armorstand/armorstand.png");
    private static final String[] METALS = {"gold", "diamond", "netherite"};

    private static final int[][] FISH_SIZES = {{3, 2, 2}, {4, 3, 2}, {6, 4, 3}, {3, 3, 3}, {2, 2, 3}, {2, 1, 2}, {1, 1, 2}};
    private static final int[][] FISH_TEXS = {{0, 0}, {0, 4}, {0, 9}, {0, 16}, {0, 22}, {11, 0}, {13, 4}};

    private static final class Villager {
        final int slot;
        float age;
        float leaving = -1f;
        float travelled;
        boolean arrived;
        float x, z, yaw;

        Villager(int slot) {
            this.slot = slot;
        }
    }

    private static final class Pest {
        final int index;
        float age;
        float leaving = -1f;

        Pest(int index) {
            this.index = index;
        }
    }

    private static final class Particle {
        float x, y, z, vx, vy, vz, age, life, size;
        boolean zee;
    }

    private final Map<Identifier, SceneClone.Buffer> buffers = new LinkedHashMap<>();
    private final SceneClone.Buffer blocks = new SceneClone.Buffer(512);
    private final List<Villager> villagers = new ArrayList<>();
    private final List<Pest> pests = new ArrayList<>();
    private final List<Particle> particles = new ArrayList<>();
    private final OrbitSpring table = new OrbitSpring(0f, 70f, 9f);
    private final OrbitSpring bed = new OrbitSpring(0f, 60f, 10f);
    private final float[] stands = new float[3];
    private final PanelSurface puff = new PanelSurface();
    private final PanelSurface zee = new PanelSurface();
    private final PanelSurface gold = new PanelSurface();
    private final List<BlockStateModelPart> parts = new ArrayList<>();
    private boolean drawn;
    private String goldItem;
    private int goldCount = -1;
    private float zeeTimer;
    private float time;
    private boolean standsShown;

    // advances everything for the focused module, by its raw page name, and poses the player to match
    void update(float dt, float time, String focus, boolean enabled, PlayerFigure figure) {
        this.time = time;
        boolean visitors = "Auto Visitor".equals(focus);
        boolean crafting = "Auto Supercraft".equals(focus);
        boolean pestsOn = "Pest Manager".equals(focus);
        boolean resting = "Dynamic Rest".equals(focus);
        boolean loadout = "Auto Loadout".equals(focus);

        updateVillagers(dt, visitors ? clamp(AetherConfig.VISITOR_THRESHOLD.get(), 0, 5) : 0, figure);
        updatePests(dt, pestsOn ? clamp(AetherConfig.PEST_THRESHOLD.get(), 0, 8) : 0);

        if (crafting && table.t < 0.5f) burst(0f, 0.5f, 1.3f, 14);
        table.t = crafting ? 1f : 0f;
        table.step(dt);
        bed.t = resting ? 1f : 0f;
        bed.step(dt);
        if (loadout && !standsShown) {
            for (int i = 0; i < 3; i++) stands[i] = -0.18f * i;
        }
        standsShown = loadout;
        for (int i = 0; i < 3; i++) {
            float before = stands[i];
            stands[i] = loadout ? stands[i] + dt : Math.max(0f, Math.min(stands[i], 0.4f) - dt * 2f);
            if (loadout && before < 0f && stands[i] >= 0f) {
                Vector3f at = standSpot(i);
                burst(at.x, 1f, at.z, 12);
            }
        }

        double money = visitors ? AetherConfig.VISITOR_MAX_PURCHASE_LIMIT.get() / 1_000_000.0 : 0;
        float level = pests.isEmpty() ? 0f : 0.2f + 0.8f * (pests.size() - 1) / 7f;
        figure.pose(level, crafting && enabled, money > 0.05, resting, dt);
        goldItem = money <= 0.05 ? null : money < 1 ? "gold_nugget" : money < 6 ? "gold_ingot" : "gold_block";
        goldCount = (int) Math.max(1, Math.round(money));

        if (resting && figure.lying() > 0.8f) {
            zeeTimer -= dt;
            if (zeeTimer <= 0f) {
                zeeTimer = 0.9f;
                Particle p = new Particle();
                p.x = 0f;
                p.y = 0.95f;
                p.z = -1.5f;
                p.vx = 0.15f;
                p.vy = 0.45f;
                p.life = 2.2f;
                p.size = 0.32f;
                p.zee = true;
                particles.add(p);
            }
        }
        for (int i = particles.size() - 1; i >= 0; i--) {
            Particle p = particles.get(i);
            p.age += dt;
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.z += p.vz * dt;
            if (!p.zee) {
                p.vx *= (float) Math.pow(0.15, dt);
                p.vz *= (float) Math.pow(0.15, dt);
                p.vy = p.vy * (float) Math.pow(0.3, dt) + 0.15f * dt;
            }
            if (p.age >= p.life) particles.remove(i);
        }
    }

    // -- visitors -----------------------------------------------------------------------------------------------

    private void updateVillagers(float dt, int count, PlayerFigure figure) {
        long staying = villagers.stream().filter(v -> v.leaving < 0f).count();
        for (int slot = 0; slot < 5 && staying < count; slot++) {
            int s = slot;
            if (villagers.stream().anyMatch(v -> v.slot == s && v.leaving < 0f)) continue;
            Villager v = new Villager(slot);
            float[] from = villagerSpot(slot, 9.5f);
            v.x = from[0];
            v.z = from[1];
            v.yaw = (float) Math.atan2(-v.x, -v.z);
            villagers.add(v);
            burst(v.x, 0.9f, v.z, 16);
            staying++;
        }
        for (int i = villagers.size() - 1; i >= 0 && staying > count; i--) {
            Villager v = villagers.get(i);
            if (v.leaving >= 0f) continue;
            v.leaving = 0f;
            burst(v.x, 0.9f, v.z, 12);
            staying--;
        }
        for (int i = villagers.size() - 1; i >= 0; i--) {
            Villager v = villagers.get(i);
            v.age += dt;
            if (v.leaving >= 0f) {
                v.leaving += dt;
                if (v.leaving > 0.35f) villagers.remove(i);
                continue;
            }
            // it pops in, looks about for a moment, then walks the line up to you
            if (v.age > 1.7f && !v.arrived) {
                float[] from = villagerSpot(v.slot, 9.5f), to = villagerSpot(v.slot, 2.4f);
                float length = (float) Math.hypot(to[0] - from[0], to[1] - from[1]);
                v.travelled = Math.min(length, v.travelled + 2.2f * dt);
                float k = v.travelled / length;
                v.x = from[0] + (to[0] - from[0]) * k;
                v.z = from[1] + (to[1] - from[1]) * k;
                v.yaw = (float) Math.atan2(to[0] - from[0], to[1] - from[1]);
                if (v.travelled >= length) {
                    v.arrived = true;
                    figure.wave(v.x, v.z);
                }
            }
            if (v.arrived) v.yaw = (float) Math.atan2(-v.x, -v.z);
        }
    }

    // visitors fan out ahead of you, each on its own line toward you
    private static float[] villagerSpot(int slot, float radius) {
        double angle = Math.toRadians(-56 + slot * 28);
        return new float[]{(float) (Math.sin(angle) * radius), (float) (Math.cos(angle) * radius)};
    }

    // -- pests --------------------------------------------------------------------------------------------------

    private void updatePests(float dt, int count) {
        long staying = pests.stream().filter(p -> p.leaving < 0f).count();
        for (int index = 0; index < 8 && staying < count; index++) {
            int n = index;
            if (pests.stream().anyMatch(p -> p.index == n && p.leaving < 0f)) continue;
            Pest p = new Pest(index);
            pests.add(p);
            Vector3f at = pestSpot(p);
            burst(at.x, 0.2f, at.z, 8);
            staying++;
        }
        for (int i = pests.size() - 1; i >= 0 && staying > count; i--) {
            Pest p = pests.get(i);
            if (p.leaving >= 0f) continue;
            p.leaving = 0f;
            staying--;
        }
        for (int i = pests.size() - 1; i >= 0; i--) {
            Pest p = pests.get(i);
            p.age += dt;
            if (p.leaving >= 0f) {
                p.leaving += dt;
                if (p.leaving > 0.3f) pests.remove(i);
            }
        }
    }

    // they circle you at different distances and speeds, half one way, half the other
    private Vector3f pestSpot(Pest p) {
        float dir = p.index % 2 == 0 ? 1f : -1f;
        float angle = p.index * 0.785f + time * (0.7f + 0.1f * (p.index % 3)) * dir;
        float radius = 1.3f + 0.3f * (p.index % 3);
        return new Vector3f((float) Math.sin(angle) * radius, 0f, (float) Math.cos(angle) * radius);
    }

    // -- geometry -----------------------------------------------------------------------------------------------

    // fills the entity buffers in the scene renderer's space; local maps the farm's blocks into it
    List<Draw> build(Matrix4f local, Vector3d camLocal) {
        for (SceneClone.Buffer b : buffers.values()) b.reset();
        blocks.reset();
        for (Villager v : villagers) {
            float s = v.leaving >= 0f ? 1f - v.leaving / 0.35f : backOut(Math.min(1f, v.age / 0.45f));
            if (s <= 0.01f) continue;
            float look = !v.arrived && v.age > 0.45f && v.age < 1.7f ? (float) Math.sin((v.age - 0.45f) * 5f) * 1.0f : 0f;
            float walk = !v.arrived && v.age > 1.7f ? v.travelled * 4f : 0f;
            Matrix4f m = new Matrix4f(local).translate(v.x, 0f, v.z).rotateY(v.yaw).scale(s);
            for (Identifier tex : new Identifier[]{VILLAGER, VILLAGER_PLAINS, VILLAGER_FARMER}) villager(buffer(tex), m, look, walk);
        }
        for (Pest p : pests) {
            float s = p.leaving >= 0f ? 1f - p.leaving / 0.3f : backOut(Math.min(1f, p.age / 0.35f));
            if (s <= 0.01f) continue;
            Vector3f at = pestSpot(p);
            float dir = p.index % 2 == 0 ? 1f : -1f;
            float heading = (float) Math.atan2(at.z * dir, -at.x * dir);
            silverfish(buffer(SILVERFISH), new Matrix4f(local).translate(at.x, 0f, at.z).rotateY(heading).scale(s),
                    time * 20f + p.index * 3f);
        }
        for (int i = 0; i < 3; i++) {
            float t = stands[i];
            if (t <= 0f) continue;
            float s = backOut(Math.min(1f, t / 0.4f));
            Vector3f at = standSpot(i);
            float face = (float) Math.atan2(camLocal.x - at.x, camLocal.z - at.z);
            Matrix4f m = new Matrix4f(local).translate(at.x, 0f, at.z).rotateY(face).scale(s);
            stand(buffer(STAND), m);
            armour(buffer(id("textures/entity/equipment/humanoid/" + METALS[i] + ".png")),
                    buffer(id("textures/entity/equipment/humanoid_leggings/" + METALS[i] + ".png")), m);
        }
        float tableScale = backOut(clamp01(table.x));
        if (tableScale > 0.01f) {
            Matrix4f m = new Matrix4f(local).translate(0f, 0f, 1.3f).scale(tableScale).translate(-0.5f, 0f, -0.5f);
            block(Blocks.CRAFTING_TABLE.defaultBlockState(), m);
        }
        float bedScale = clamp01(bed.x);
        if (bedScale > 0.01f) {
            // a wool bed, pillow at the head end where the sleeper's head lands
            Matrix4f base = new Matrix4f(local).translate(0f, 0f, -0.95f).scale(bedScale, bedScale, bedScale);
            block(Blocks.WHITE_WOOL.defaultBlockState(), new Matrix4f(base).translate(-0.5f, 0f, -1f).scale(1f, 0.5625f, 1f));
            block(Blocks.RED_WOOL.defaultBlockState(), new Matrix4f(base).translate(-0.5f, 0f, 0f).scale(1f, 0.5625f, 1f));
        }
        List<Draw> out = new ArrayList<>();
        for (Map.Entry<Identifier, SceneClone.Buffer> e : buffers.entrySet()) {
            if (e.getValue().count > 0) out.add(new Draw(e.getKey(), e.getValue()));
        }
        return out;
    }

    SceneClone.Buffer blocks() {
        return blocks;
    }

    private SceneClone.Buffer buffer(Identifier texture) {
        return buffers.computeIfAbsent(texture, t -> new SceneClone.Buffer(1024));
    }

    private static Vector3f standSpot(int i) {
        return new Vector3f(2.3f + 1.2f * i, 0f, 1.6f - 1.0f * i);
    }

    static void villager(SceneClone.Buffer out, Matrix4f m, float look, float walk) {
        float legs = (float) Math.sin(walk) * 0.7f;
        ModelBoxes.part(out, m, 64, 64, false, 0, 0, 0, 0, look, 0, -4, -10, -4, 8, 10, 8, 0, 0, 0f);
        ModelBoxes.part(out, m, 64, 64, false, 0, 0, 0, 0, look, 0, -4, -10, -4, 8, 10, 8, 32, 0, 0.51f);
        ModelBoxes.part(out, m, 64, 64, false, 0, 0, 0, 0, look, 0, -1, -3, -6, 2, 4, 2, 24, 0, 0f);
        ModelBoxes.part(out, m, 64, 64, false, 0, 0, 0, 0, 0, 0, -4, 0, -3, 8, 12, 6, 16, 20, 0f);
        ModelBoxes.part(out, m, 64, 64, false, 0, 0, 0, 0, 0, 0, -4, 0, -3, 8, 20, 6, 0, 38, 0.5f);
        ModelBoxes.part(out, m, 64, 64, false, 0, 3, -1, -0.75f, 0, 0, -8, -2, -2, 4, 8, 4, 44, 22, 0f);
        ModelBoxes.part(out, m, 64, 64, true, 0, 3, -1, -0.75f, 0, 0, 4, -2, -2, 4, 8, 4, 44, 22, 0f);
        ModelBoxes.part(out, m, 64, 64, false, 0, 3, -1, -0.75f, 0, 0, -4, 2, -2, 8, 4, 4, 40, 38, 0f);
        ModelBoxes.part(out, m, 64, 64, false, -2, 12, 0, legs, 0, 0, -2, 0, -2, 4, 12, 4, 0, 22, 0f);
        ModelBoxes.part(out, m, 64, 64, true, 2, 12, 0, -legs, 0, 0, -2, 0, -2, 4, 12, 4, 0, 22, 0f);
    }

    // seven segments that wriggle the way the game animates them
    static void silverfish(SceneClone.Buffer out, Matrix4f m, float ticks) {
        float placement = -3.5f;
        for (int i = 0; i < 7; i++) {
            int[] size = FISH_SIZES[i];
            float yRot = (float) (Math.cos(ticks * 0.9f + i * 0.15f * Math.PI) * Math.PI * 0.05f * (1 + Math.abs(i - 2)));
            float sway = (float) (Math.sin(ticks * 0.9f + i * 0.15f * Math.PI) * Math.PI * 0.2f * Math.abs(i - 2));
            ModelBoxes.part(out, m, 64, 32, false, sway, 24 - size[1], placement, 0, yRot, 0,
                    size[0] * -0.5f, 0, size[2] * -0.5f, size[0], size[1], size[2], FISH_TEXS[i][0], FISH_TEXS[i][1], 0f);
            if (i < 6) placement += (size[2] + FISH_SIZES[i + 1][2]) * 0.5f;
        }
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

    // a block from the game's own model, through m from its 0..1 cube, shaded per side like the farm
    private void block(BlockState state, Matrix4f m) {
        var models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        parts.clear();
        models.get(state).collectParts(RandomSource.create(42L), parts);
        for (BlockStateModelPart part : parts) {
            for (Direction dir : Direction.values()) for (BakedQuad q : part.getQuads(dir)) quad(q, m);
            for (BakedQuad q : part.getQuads(null)) quad(q, m);
        }
    }

    private void quad(BakedQuad q, Matrix4f m) {
        float shade = switch (q.direction()) {
            case UP -> 1f;
            case DOWN -> 0.5f;
            case NORTH, SOUTH -> 0.8f;
            case EAST, WEST -> 0.6f;
        };
        int color = SceneClone.rgba(0xFFFFFF, shade, 255);
        for (int i : new int[]{0, 1, 2, 0, 2, 3}) {
            Vector3fc p = q.position(i);
            Vector3f w = m.transformPosition(p.x(), p.y(), p.z(), new Vector3f());
            long uv = q.packedUV(i);
            blocks.vertex(w.x, w.y, w.z, UVPair.unpackU(uv), UVPair.unpackV(uv), color);
        }
    }

    // -- flat things: poof clouds, the sleeper's z's and the gold in your hand ------------------------------------

    void appendQuads(List<OrbitWorldRenderer.Quad> out, java.util.function.Function<Vector3f, Vector3d> toWorld,
                     OrbitLayout.Camera cam, PlayerFigure figure, boolean slim) {
        if (!drawn) {
            puff.render(32f, 32f, 2f, nvg -> {
                nvg.radialGradient(16f, 16f, 2f, 15f, 0xF0F2F2F2, 0x00B8B8B8);
                nvg.circle(13f, 13f, 6f, 0x66FFFFFF);
            });
            zee.render(48f, 48f, 2f, nvg -> {
                nvg.text(Fonts.UI_BOLD, "Z", 13f, 7f, 34f, 0xCC000000);
                nvg.text(Fonts.UI_BOLD, "Z", 11f, 5f, 34f, 0xFFFFFFFF);
            });
            drawn = true;
        }
        for (Particle p : particles) {
            float k = p.age / p.life;
            float alpha = p.zee ? Math.min(1f, (1f - k) * 1.6f) : (1f - k) * (1f - k);
            float size = p.zee ? p.size * (0.7f + 0.6f * k) : p.size * (0.6f + 0.9f * k);
            out.add(billboard(toWorld.apply(new Vector3f(p.x, p.y, p.z)), cam, size, p.zee ? zee.texture() : puff.texture(),
                    alpha));
        }
        if (goldItem != null) {
            String item = goldItem;
            int count = goldCount;
            if (!item.equals(lastGold) || count != lastCount) {
                gold.render(48f, 48f, 2f, nvg -> {
                    nvg.mcIcon(McIcons.of("minecraft:" + item), 4f, 4f, 40f, 0xFFFFFFFF);
                    if (count > 1) {
                        String n = Integer.toString(count);
                        float tw = n.length() * 12f;
                        nvg.mcTextLiteral(n, 46f - tw, 30f, 2, 0xFFFFFFFF, true);
                    }
                });
                lastGold = item;
                lastCount = count;
            }
            Vector3f hand = figure.hand(slim);
            float size = 0.42f + 0.012f * Math.min(20, count);
            out.add(billboard(toWorld.apply(new Vector3f(hand.x, hand.y - 0.05f, hand.z + 0.12f)), cam, size, gold.texture(), 1f));
        }
    }

    private String lastGold;
    private int lastCount = -1;

    private static OrbitWorldRenderer.Quad billboard(Vector3d at, OrbitLayout.Camera cam, double size, int texture, float a) {
        Vector3d r = new Vector3d(cam.right()).mul(size / 2), u = new Vector3d(cam.up()).mul(size / 2);
        return new OrbitWorldRenderer.Quad(new Vector3d(at).sub(r).add(u), new Vector3d(at).add(r).add(u),
                new Vector3d(at).add(r).sub(u), new Vector3d(at).sub(r).sub(u), texture, a, 0f);
    }

    // a puff of smoke like a mob spawning, around a point in local blocks
    private void burst(float x, float y, float z, int count) {
        for (int i = 0; i < count; i++) {
            Particle p = new Particle();
            double a = Math.random() * Math.PI * 2;
            float r = (float) (0.2 + Math.random() * 0.35);
            p.x = x + (float) Math.cos(a) * r;
            p.y = y + (float) (Math.random() - 0.5) * 1.2f;
            p.z = z + (float) Math.sin(a) * r;
            p.vx = (float) Math.cos(a) * (0.6f + (float) Math.random());
            p.vy = 0.2f + (float) Math.random() * 0.6f;
            p.vz = (float) Math.sin(a) * (0.6f + (float) Math.random());
            p.life = 0.6f + (float) Math.random() * 0.5f;
            p.size = 0.35f + (float) Math.random() * 0.3f;
            particles.add(p);
        }
    }

    private static float backOut(float t) {
        float s = 1.70158f, u = t - 1f;
        return 1f + u * u * ((s + 1f) * u + s);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static Identifier id(String path) {
        return Identifier.withDefaultNamespace(path);
    }

    @Override
    public void close() {
        for (SceneClone.Buffer b : buffers.values()) b.free();
        buffers.clear();
        blocks.free();
        puff.close();
        zee.close();
        gold.close();
    }
}
