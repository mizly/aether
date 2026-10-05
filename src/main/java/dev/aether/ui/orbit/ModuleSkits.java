package dev.aether.ui.orbit;

import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.function.Function;
import java.util.function.IntFunction;

// a skit for nearly every remaining module: the failsafes each act out what they catch (a gui popping open, the
// view yanked round, a teleport, a ghost block, a player wandering up), the garden helpers do their jobs, and the
// display options show themselves off. the client plumbing pages (updates, account, language) stay quiet
final class ModuleSkits {
    private static final Identifier FONT = mc("textures/font/ascii.png");
    private static final Identifier BLACK = mc("textures/block/black_concrete.png");
    private static final Identifier TRADER = mc("textures/entity/wandering_trader/wandering_trader.png");
    private static final Identifier GOLD_INGOT = mc("textures/item/gold_ingot.png");
    private static final Identifier GOLD_NUGGET = mc("textures/item/gold_nugget.png");
    private static final Identifier GLASS = mc("textures/block/glass.png");
    private static final Identifier FARMLAND = mc("textures/block/farmland_moist.png");
    private static final Identifier DIRT = mc("textures/block/dirt.png");
    private static final Identifier CHEST = mc("textures/entity/chest/normal.png");
    private static final Identifier TOTEM = mc("textures/item/totem_of_undying.png");
    private static final Identifier CONTAINER = mc("textures/gui/container/generic_54.png");
    private static final Identifier COMPASS = mc("textures/item/compass_00.png");
    private static final Identifier ENDER_EYE = mc("textures/item/ender_eye.png");
    private static final Identifier ENDER_PEARL = mc("textures/item/ender_pearl.png");
    private static final Identifier HOTBAR = mc("textures/gui/sprites/hud/hotbar.png");
    private static final Identifier HOTBAR_SELECTION = mc("textures/gui/sprites/hud/hotbar_selection.png");
    private static final Identifier HOE = mc("textures/item/diamond_hoe.png");
    private static final Identifier SHOVEL = mc("textures/item/diamond_shovel.png");
    private static final Identifier SPYGLASS = mc("textures/item/spyglass.png");
    private static final Identifier NAME_TAG = mc("textures/item/name_tag.png");
    private static final Identifier ITEM_FRAME = mc("textures/block/item_frame.png");
    private static final Identifier ROCKET = mc("textures/item/firework_rocket.png");
    private static final Identifier LEAD = mc("textures/block/brown_wool.png");
    private static final Identifier KNOT = mc("textures/entity/lead_knot/lead_knot.png");
    private static final Identifier POST = mc("textures/block/oak_planks.png");
    private static final Identifier LOG = mc("textures/block/oak_log.png");
    private static final Identifier SUN = mc("textures/environment/celestial/sun.png");
    private static final Identifier MOON = mc("textures/environment/celestial/moon/full_moon.png");
    private static final Identifier BELL = mc("textures/entity/bell/bell_body.png");
    private static final Identifier LAMP = mc("textures/block/redstone_lamp.png");
    private static final Identifier LAMP_ON = mc("textures/block/redstone_lamp_on.png");
    private static final Identifier STONE = mc("textures/block/stone.png");
    private static final Identifier LEVER = mc("textures/block/lever.png");
    private static final Identifier COBBLE = mc("textures/block/cobblestone.png");
    private static final Identifier DUST = mc("textures/block/redstone_dust_dot.png");
    private static final Identifier REPEATER = mc("textures/block/repeater.png");
    private static final Identifier REPEATER_ON = mc("textures/block/repeater_on.png");
    private static final Identifier STRANGER = mc("textures/entity/player/wide/alex.png");
    private static final Identifier[] WHEAT = new Identifier[8];
    private static final Identifier[] CLOCK = new Identifier[64];
    private static final Identifier[] HOTBAR_ITEMS = {HOE, mc("textures/item/carrot.png"), mc("textures/item/bread.png"),
            mc("textures/item/wheat_seeds.png"), mc("textures/item/bone_meal.png"), mc("textures/item/compass_16.png"),
            mc("textures/item/ender_pearl.png"), mc("textures/item/emerald.png"), mc("textures/item/clock_00.png")};
    private static final Identifier[] HUD_ITEMS = {mc("textures/item/clock_00.png"), mc("textures/item/compass_16.png"),
            GOLD_INGOT, mc("textures/item/wheat.png")};
    private static final Identifier[] SORTED = {mc("textures/item/wheat.png"), mc("textures/item/carrot.png"),
            mc("textures/item/potato.png"), mc("textures/item/melon_slice.png"), mc("textures/item/pumpkin_pie.png")};
    private static final Identifier[] PAINTINGS = {mc("textures/painting/kebab.png"), mc("textures/painting/sunset.png"),
            mc("textures/painting/wanderer.png"), mc("textures/painting/aztec.png")};
    private static final String[] HUD_DYES = {"lime", "light_blue", "yellow", "magenta"};
    private static final String[] MENU_DYES = {"orange", "red", "pink", "yellow"};
    private static final int[] HUD_TINTS = {0x80C71F, 0x3AB3DA, 0xFED83D, 0xC74EBD};
    private static final int[] MENU_TINTS = {0xF9801D, 0xB02E26, 0xF38BAA, 0xFED83D};
    private static final int[] FIREWORK = {0xFF5555, 0x55FF55, 0x5555FF, 0xFFFF55, 0xFF55FF, 0x55FFFF};

    static {
        for (int i = 0; i < 8; i++) WHEAT[i] = mc("textures/block/wheat_stage" + i + ".png");
        for (int i = 0; i < 64; i++) CLOCK[i] = mc(String.format("textures/item/clock_%02d.png", i));
    }

    private final SceneParticles particles;
    private final IntFunction<Identifier> heads;
    private final PlayerFigure stranger = new PlayerFigure();
    private String focus;
    private float scene, time, dt;
    private int round;
    private float lastC;
    private boolean strangerOut;
    private final float[] crops = {1f, 1f, 1f, 1f, 1f};
    private final float[] greenhouse = new float[9];
    private String obfuscated = "";

    ModuleSkits(SceneParticles particles, IntFunction<Identifier> heads) {
        this.particles = particles;
        this.heads = heads;
    }

    static boolean handles(String focus) {
        return switch (focus == null ? "" : focus) {
            case "Auto Pest Exchange", "Auto Greenhouse", "Farming QOL", "Failsafe Settings", "GUI Opened", "Rotation",
                 "World Change", "Inventory Slot Changed", "BPS", "Dirt Check", "Ghost Block", "Player Nearby",
                 "TP Check", "HUD", "Profit Tracker", "Nick Hider", "Freecam", "Freelook", "PiP", "Fun",
                 "Ungrab Mouse", "Skybox", "HUD Colors", "Menu Colors", "Miscellaneous", "Discord" -> true;
            default -> false;
        };
    }

    private static float loop(String focus) {
        return switch (focus) {
            case "Auto Greenhouse" -> 8f;
            case "Player Nearby", "Skybox" -> 9f;
            case "Farming QOL", "Profit Tracker", "Miscellaneous" -> 6f;
            case "Auto Pest Exchange", "Fun", "BPS", "Dirt Check", "Ghost Block", "Freecam", "HUD", "Ungrab Mouse" -> 5.5f;
            default -> 4.5f;
        };
    }

    void update(float dt, float scene, float time, String focus, PlayerFigure.Pose pose) {
        if (focus == null ? this.focus != null : !focus.equals(this.focus)) {
            this.focus = focus;
            java.util.Arrays.fill(crops, 1f);
            java.util.Arrays.fill(greenhouse, 0f);
            strangerOut = false;
            lastC = 0f;
        }
        this.scene = scene;
        this.time = time;
        this.dt = dt;
        if (!handles(focus)) return;
        float length = loop(focus);
        float c = scene % length;
        round = (int) (scene / length);
        switch (focus) {
            case "Auto Pest Exchange" -> exchange(c, pose);
            case "Auto Greenhouse" -> greenhouse(c, pose);
            case "Farming QOL" -> sort(c, pose);
            case "Failsafe Settings" -> totem(c, pose);
            case "GUI Opened" -> guiOpened(c, pose);
            case "Rotation" -> rotation(c, pose);
            case "World Change" -> worldChange(c, pose);
            case "Inventory Slot Changed" -> slotChanged(c, pose);
            case "BPS" -> bps(c, pose);
            case "Dirt Check" -> dirtCheck(c, pose);
            case "Ghost Block" -> ghostBlock(c, pose);
            case "Player Nearby" -> playerNearby(c, pose);
            case "TP Check" -> tpCheck(c, pose);
            case "HUD" -> hud(c, pose);
            case "Profit Tracker" -> profit(c, pose);
            case "Nick Hider" -> nickHider(c, pose);
            case "Freecam" -> freecam(c, pose);
            case "Freelook" -> freelook(c, pose);
            case "PiP" -> pip(c, pose);
            case "Fun" -> fun(c, pose);
            case "Ungrab Mouse" -> ungrab(c, pose);
            case "Skybox" -> skybox(c, pose);
            case "HUD Colors" -> dyes(c, pose, HUD_TINTS);
            case "Menu Colors" -> dyes(c, pose, MENU_TINTS);
            case "Miscellaneous" -> redstone(c, pose);
            case "Discord" -> bell(c, pose);
            default -> {
            }
        }
        lastC = c;
    }

    // true on the frame c first reaches at
    private boolean at(float c, float at) {
        return lastC < at && c >= at || (c < lastC && at <= c);
    }

    // -- pest exchange: hand three pests to the trader, three gold ingots come back --------------------------------

    private static final Vector3f TRADER_AT = new Vector3f(1.4f, 0f, 2.3f);

    private void exchange(float c, PlayerFigure.Pose pose) {
        pose.facing = (float) Math.toDegrees(Math.atan2(TRADER_AT.x, TRADER_AT.z));
        pose.look = 0.3f;
        float toss = c % 0.6f;
        if (c < 1.8f) {
            pose.right = toss < 0.25f ? 20f * smooth(toss / 0.25f) : 20f - 110f * smooth((toss - 0.25f) / 0.2f);
            pose.rightWeight = 1f;
            if (at(c, 0.45f) || at(c, 1.05f) || at(c, 1.65f)) {
                particles.poof(TRADER_AT.x, 1.6f, TRADER_AT.z, 3, 0.1f);
            }
        } else if (c < 2.6f) {
            // the trader looks them over
            pose.right = -20f;
            pose.rightWeight = 1f;
            if (at(c, 2.5f)) particles.happy(TRADER_AT.x, 2.2f, TRADER_AT.z, 6, 0.3f);
        } else if (c < 4.2f) {
            // gold comes back: catch, catch, catch
            float k = ((c - 2.6f) % 0.5f) / 0.5f;
            pose.right = pose.left = -70f - 30f * (float) Math.sin(Math.PI * k);
            pose.rightWeight = pose.leftWeight = 1f;
            if (at(c, 3.0f) || at(c, 3.5f) || at(c, 4.0f)) particles.crit(0f, 1.3f, 0.4f, 4, true);
        } else {
            float k = (c - 4.2f) / 1.3f;
            float air = clamp01((k - 0.1f) / 0.5f);
            pose.squash = k < 0.1f ? 0.2f : 0f;
            pose.y = (float) Math.sin(Math.PI * air) * 0.3f;
            pose.right = pose.left = -160f * (float) Math.sin(Math.PI * air);
            pose.rightWeight = pose.leftWeight = 1f;
        }
    }

    // -- greenhouse: glass goes up around a plot, block by block, and the wheat inside shoots up -----------------

    private static final float GH_X = 3.6f, GH_Z = 1.6f;

    private void greenhouse(float c, PlayerFigure.Pose pose) {
        pose.facing = (float) Math.toDegrees(Math.atan2(GH_X, GH_Z));
        pose.look = 0.2f;
        pose.headPitch = 10f;
        for (int i = 0; i < 9; i++) {
            float due = 0.3f + i * 0.22f;
            if (at(c, due)) particles.poof(GH_X - 1f + (i % 3), 1f, GH_Z - 1f + (i / 3), 2, 0.2f);
            greenhouse[i] = c >= due && c < 7.3f ? Math.min(1f, (c - due) / 0.2f) : 0f;
        }
        // inside, the wheat grows a stage at a time once the glass is up
        float grow = clamp01((c - 2.6f) / 3.2f);
        for (int i = 0; i < 5; i++) crops[i] = grow;
        if (c > 2.6f && c < 5.8f && Math.random() < 0.15) {
            particles.happy(GH_X + (float) (Math.random() - 0.5) * 2f, 0.6f, GH_Z + (float) (Math.random() - 0.5) * 2f, 1, 0.1f);
        }
        if (at(c, 7.3f)) {
            for (int i = 0; i < 20; i++) {
                particles.terrain(GLASS, GH_X + (float) (Math.random() - 0.5) * 3f, 0.5f + (float) Math.random() * 1.5f,
                        GH_Z + (float) (Math.random() - 0.5) * 3f, 0f, 0.1f, 0f, 0xFFFFFF);
            }
        }
        if (c > 5.8f && c < 7.2f) {
            // a proud clap
            float k = ((c - 5.8f) % 0.35f) / 0.35f;
            pose.right = pose.left = -80f + (float) Math.sin(k * Math.PI * 2) * 15f;
            pose.rightWeight = pose.leftWeight = 1f;
        }
    }

    // -- farming qol: the chest pops open and the harvest sorts itself in ----------------------------------------

    private static final Vector3f CHEST_AT = new Vector3f(1.5f, 0f, 1.5f);
    private float lid;

    private void sort(float c, PlayerFigure.Pose pose) {
        pose.facing = (float) Math.toDegrees(Math.atan2(CHEST_AT.x, CHEST_AT.z));
        pose.look = 0.3f;
        pose.headPitch = 20f;
        boolean open = c > 0.4f && c < 4.6f;
        lid += ((open ? 1f : 0f) - lid) * (1f - (float) Math.exp(-dt * 10f));
        if (c > 0.8f && c < 4.2f) {
            float k = ((c - 0.8f) % 0.68f) / 0.68f;
            pose.right = k < 0.4f ? 10f : 10f - 100f * smooth((k - 0.4f) / 0.3f);
            pose.rightWeight = 1f;
        } else if (c >= 4.6f) {
            // dust the hands off
            pose.right = pose.left = -60f + (float) Math.sin(c * 30) * 10f;
            pose.rightWeight = pose.leftWeight = c < 5.4f ? 1f : 0f;
        }
        if (at(c, 4.6f)) particles.poof(CHEST_AT.x, 0.9f, CHEST_AT.z, 4, 0.3f);
    }

    // -- failsafe settings: the totem pops and saves you --------------------------------------------------------

    private void totem(float c, PlayerFigure.Pose pose) {
        if (c < 1.2f) {
            pose.right = -100f * smooth(c / 0.4f);
            pose.rightWeight = 1f;
            pose.headPitch = 15f;
        } else if (c < 1.6f) {
            // the pop: thrown back by it
            if (at(c, 1.2f)) particles.totem(0f, 1.2f, 0.3f, 60);
            float k = (c - 1.2f) / 0.4f;
            pose.lean = -22f * (float) Math.sin(Math.PI * k);
            pose.right = pose.left = -150f;
            pose.rightWeight = pose.leftWeight = (float) Math.sin(Math.PI * k);
            pose.squint = 1f;
        } else if (c < 3.2f) {
            // fist to the sky
            float k = (c - 1.6f) / 1.6f;
            pose.right = -170f;
            pose.rightWeight = smooth(k / 0.2f);
            pose.headPitch = -20f;
            pose.y = (float) Math.sin(Math.PI * clamp01(k / 0.4f)) * 0.25f;
        }
    }

    // -- gui opened: a chest window springs up in your face; startled, you swat it shut ------------------------

    private void guiOpened(float c, PlayerFigure.Pose pose) {
        if (at(c, 0.5f)) particles.glyph('!', 0xFF5555, 0f, 2.3f, 0f, 0f, 0.03f, 20, 0.3f);
        if (c >= 0.5f && c < 0.9f) {
            float k = (c - 0.5f) / 0.4f;
            pose.lean = -15f * (float) Math.sin(Math.PI * k);
            pose.y = (float) Math.sin(Math.PI * k) * 0.3f;
            pose.right = pose.left = -150f * (float) Math.sin(Math.PI * k);
            pose.rightWeight = pose.leftWeight = 1f;
        } else if (c >= 1.6f && c < 2.3f) {
            float k = (c - 1.6f) / 0.7f;
            pose.right = -100f + 60f * (float) Math.sin(Math.PI * k);
            pose.rightWeight = 1f;
            pose.lean = 10f * (float) Math.sin(Math.PI * k);
        }
        if (at(c, 2.0f)) particles.poof(0f, 1.6f, 1.6f, 5, 0.3f);
    }

    private float guiScale(float c) {
        if (c < 0.3f) return 0f;
        if (c < 0.6f) return backOut((c - 0.3f) / 0.3f);
        if (c < 2.0f) return 1f;
        return Math.max(0f, 1f - (c - 2.0f) / 0.2f);
    }

    // -- rotation: your view yanked round; you shake it off and turn back ----------------------------------------

    private void rotation(float c, PlayerFigure.Pose pose) {
        if (c < 0.6f) {
            pose.facing = 0f;
        } else if (c < 0.85f) {
            if (at(c, 0.6f)) particles.glyph('!', 0xFF5555, 0f, 2.3f, 0f, 0f, 0.03f, 20, 0.3f);
            pose.facing = 140f * backOut((c - 0.6f) / 0.25f);
            pose.right = pose.left = -60f;
            pose.rightWeight = pose.leftWeight = 1f;
            pose.squint = 1f;
        } else if (c < 1.8f) {
            pose.facing = 140f;
            pose.headYaw = (float) Math.sin((c - 0.85f) * 22) * 30f * (1f - (c - 0.85f));
            pose.look = 0f;
        } else if (c < 2.8f) {
            pose.facing = 140f * (1f - smooth((c - 1.8f) / 1f));
            pose.tilt = 10f;
        }
    }

    // -- world change: an eye of ender leads you off; you come back somewhere, puzzled ---------------------------

    private void worldChange(float c, PlayerFigure.Pose pose) {
        if (c < 1.2f) {
            pose.headPitch = -30f * smooth(c / 0.5f);
            pose.look = 0f;
        } else if (c < 1.9f) {
            float k = (c - 1.2f) / 0.7f;
            pose.vanish = smooth(clamp01((k - 0.3f) / 0.7f));
            pose.right = pose.left = -160f;
            pose.rightWeight = pose.leftWeight = 1f;
            for (int i = 0; i < 2; i++) {
                double a = Math.random() * Math.PI * 2;
                particles.portal((float) Math.cos(a) * 1.3f, 0.2f + (float) Math.random() * 1.8f, (float) Math.sin(a) * 1.3f,
                        0f, 1f, 0f, false);
            }
        } else if (c < 2.3f) {
            pose.vanish = 1f;
        } else {
            float k = clamp01((c - 2.3f) / 0.4f);
            if (at(c, 2.3f)) {
                for (int i = 0; i < 30; i++) {
                    double a = Math.random() * Math.PI * 2;
                    particles.portal((float) Math.cos(a) * 1.2f, 0.2f + (float) Math.random() * 1.8f, (float) Math.sin(a) * 1.2f,
                            0f, 1f, 0f, true);
                }
            }
            if (at(c, 2.8f)) particles.glyph('?', 0xFFFF55, 0f, 2.3f, 0f, 0f, 0.03f, 30, 0.3f);
            pose.vanish = 1f - smooth(k);
            pose.headYaw = c > 2.8f ? (float) Math.sin((c - 2.8f) * 4) * 60f : 0f;
            pose.look = 0f;
            pose.tilt = c > 2.8f ? 14f : 0f;
            pose.right = c > 2.8f ? -150f + (float) Math.sin(c * 20) * 8f : 0f;
            pose.rightWeight = c > 2.8f ? 1f : 0f;
        }
    }

    private float eyeY(float c) {
        if (c < 0.2f) return 1.4f;
        return 1.4f + Math.min(1.6f, (c - 0.2f) * 2.2f) + (float) Math.sin(c * 6) * 0.06f;
    }

    // -- inventory slot changed: the selection jumps off your hoe; you scroll it back -----------------------------

    private int slot(float c) {
        if (c >= 1.2f && c < 2.6f) return 3;
        if (c >= 2.6f && c < 2.8f) return 2;
        if (c >= 2.8f && c < 3.0f) return 1;
        return 0;
    }

    private void slotChanged(float c, PlayerFigure.Pose pose) {
        pose.right = -45f;
        pose.rightWeight = 1f;
        pose.headPitch = -20f;
        pose.look = 0.3f;
        if (at(c, 1.2f)) particles.glyph('!', 0xFF5555, 0f, 2.3f, 0f, 0f, 0.03f, 20, 0.3f);
        if (c >= 1.2f && c < 1.6f) {
            pose.lean = -10f * (float) Math.sin(Math.PI * (c - 1.2f) / 0.4f);
        }
        if (c >= 2.4f && c < 3.2f) pose.headPitch = -35f;
        if (at(c, 3.0f)) particles.happy(0f, 2.4f, 0f, 4, 0.3f);
    }

    // -- bps: hoeing flat out, slowing to a crawl, the clock flashes, back up to speed ----------------------------

    private void bps(float c, PlayerFigure.Pose pose) {
        float speed = c < 1.8f ? 1f : c < 3f ? 1f - 0.8f * smooth((c - 1.8f) / 1.2f) : 0.2f + 0.8f * smooth((c - 3.4f) / 0.8f);
        float phase = c * 5f * speed;
        pose.x = (float) Math.sin(c * 1.4f) * 1.4f;
        pose.legs = (float) Math.sin(phase * 2) * 20f * speed;
        pose.legsWeight = 1f;
        pose.right = -45f - 80f * Math.abs((float) Math.sin(phase * 3));
        pose.rightWeight = 1f;
        pose.headPitch = 20f;
        if (speed < 0.4f) pose.squint = 1f;
        if (at(c, 3.0f)) particles.glyph('!', 0xFF5555, pose.x, 2.3f, 0f, 0f, 0.03f, 20, 0.3f);
        if (Math.random() < 0.25 * speed) {
            particles.terrain(WHEAT[7], pose.x - 0.3f, 0.5f, 0.9f, (float) (Math.random() - 0.5) * 0.1f, 0.15f, 0.05f, 0xFFFFFF);
        }
    }

    private String bpsText(float c) {
        float bps = c < 1.8f ? 19.8f : c < 3f ? 19.8f - 15.5f * smooth((c - 1.8f) / 1.2f) : 4.3f + 15.5f * smooth((c - 3.4f) / 0.8f);
        return String.format(java.util.Locale.ROOT, "%.1f BPS", bps + (float) Math.sin(time * 9) * 0.2f);
    }

    // -- dirt check: a block of dirt where a crop should be; stop, dig it out, carry on --------------------------

    private void dirtCheck(float c, PlayerFigure.Pose pose) {
        if (c < 1.0f) {
            pose.x = -1.6f + c * 1.6f;
            pose.legs = (float) Math.sin(c * 12) * 20f;
            pose.legsWeight = 1f;
            pose.right = -60f;
            pose.rightWeight = 1f;
            pose.headPitch = 20f;
        } else if (c < 1.5f) {
            pose.headPitch = 25f;
            if (at(c, 1.0f)) particles.glyph('!', 0xFF5555, 0f, 2.3f, 0f, 0f, 0.03f, 20, 0.3f);
            pose.lean = -8f;
        } else if (c < 3.2f) {
            // dig
            float k = (c - 1.5f) * 3f;
            pose.right = -50f - 70f * Math.abs((float) Math.sin(Math.PI * k));
            pose.rightWeight = 1f;
            pose.lean = 18f;
            pose.squash = 0.08f;
            if ((int) k != (int) ((c - dt - 1.5f) * 3f)) {
                for (int i = 0; i < 5; i++) {
                    particles.terrain(DIRT, (float) (Math.random() - 0.5) * 0.6f, 0.8f, 1.6f, (float) (Math.random() - 0.5) * 0.15f,
                            0.15f, -0.05f, 0xFFFFFF);
                }
            }
            if (at(c, 3.1f)) particles.poof(0f, 0.5f, 1.6f, 6, 0.3f);
        } else {
            pose.x = (c - 3.2f) * 0.8f;
            pose.legs = (float) Math.sin(c * 12) * 20f;
            pose.legsWeight = 1f;
            pose.right = -60f;
            pose.rightWeight = 1f;
            pose.headPitch = 20f;
        }
    }

    // -- ghost block: walk into a block that isn't there, bonk, it flickers out ---------------------------------

    private void ghostBlock(float c, PlayerFigure.Pose pose) {
        if (c < 1.2f) {
            pose.z = -0.8f + c * 1.0f;
            pose.legs = (float) Math.sin(c * 10) * 30f;
            pose.legsWeight = 1f;
            pose.right = (float) Math.sin(c * 10) * 25f;
            pose.left = -(float) Math.sin(c * 10) * 25f;
            pose.rightWeight = pose.leftWeight = 1f;
        } else if (c < 1.6f) {
            // bonk
            float k = (c - 1.2f) / 0.4f;
            if (at(c, 1.2f)) particles.crit(0f, 1.6f, 0.75f, 6, false);
            pose.z = 0.4f - 0.35f * (float) Math.sin(Math.PI * k * 0.5f);
            pose.lean = -20f * (float) Math.sin(Math.PI * k);
            pose.squint = 1f;
        } else if (c < 3.0f) {
            pose.z = 0.05f;
            if (at(c, 1.9f)) particles.glyph('?', 0xFFFF55, 0f, 2.3f, 0.05f, 0f, 0.03f, 30, 0.3f);
            pose.right = -100f;
            pose.rightWeight = smooth((c - 1.9f) / 0.3f);
            pose.tilt = 12f;
            if (at(c, 2.6f)) particles.poof(0f, 0.5f, 1.3f, 6, 0.3f);
        } else {
            pose.z = 0.05f + (c - 3.0f) * 1.0f;
            pose.legs = (float) Math.sin(c * 10) * 30f;
            pose.legsWeight = 1f;
        }
    }

    private float ghostAlpha(float c) {
        if (c < 1.2f) return 0.15f + 0.1f * (float) Math.sin(time * 20);
        if (c < 2.6f) return (float) Math.sin(time * 30) > 0 ? 1f : 0.4f;
        return 0f;
    }

    // -- player nearby: someone wanders up the path and stares; you freeze, wave awkwardly, they leave -----------

    private void playerNearby(float c, PlayerFigure.Pose pose) {
        PlayerFigure.Pose s = stranger.pose;
        s.reset();
        strangerOut = c < 7.5f;
        float z;
        if (c < 2.5f) {
            z = 8f - 5.4f * smooth(c / 2.5f);
            s.facing = 180f;
            s.legs = (float) Math.sin(c * 9) * 30f * (1f - smooth((c - 2f) / 0.5f));
            s.legsWeight = 1f;
            s.right = (float) Math.sin(c * 9) * 25f;
            s.left = -s.right;
            s.rightWeight = s.leftWeight = 1f;
        } else if (c < 5.5f) {
            z = 2.6f;
            s.facing = 180f;
            if (c > 4.2f && c < 5f) {
                s.right = -150f + (float) Math.sin(c * 16) * 20f;
                s.rightWeight = 1f;
            }
        } else {
            z = 2.6f + 5.4f * smooth((c - 5.5f) / 2f);
            s.facing = c < 5.8f ? 180f + 180f * smooth((c - 5.5f) / 0.3f) : 360f;
            s.legs = (float) Math.sin(c * 9) * 30f;
            s.legsWeight = 1f;
        }
        s.z = z;
        s.x = 0.4f;
        s.look = 0f;
        s.headPitch = 5f;
        if (at(c, 2.6f)) particles.glyph('!', 0xFF5555, 0f, 2.3f, 0f, 0f, 0.03f, 20, 0.3f);
        if (c >= 2.6f && c < 3.0f) pose.lean = -8f * (float) Math.sin(Math.PI * (c - 2.6f) / 0.4f);
        if (c >= 3.2f && c < 4.2f) {
            // an awkward little wave
            pose.right = -150f + (float) Math.sin(c * 14) * 15f;
            pose.rightWeight = 1f;
            pose.tilt = 10f;
        }
        pose.look = 0f;
        pose.headYaw = (float) Math.toDegrees(Math.atan2(0.4f, z)) * 0.8f;
    }

    // -- tp check: yanked sideways in a flash, you look round and walk back --------------------------------------

    private void tpCheck(float c, PlayerFigure.Pose pose) {
        if (c < 1.0f) {
            pose.x = 0f;
        } else if (c < 3.0f) {
            if (at(c, 1.0f)) {
                for (int i = 0; i < 12; i++) particles.endRod((float) (Math.random() - 0.5) * 0.6f, (float) Math.random() * 2f, 0f);
                for (int i = 0; i < 12; i++) particles.endRod(-2.4f + (float) (Math.random() - 0.5) * 0.6f, (float) Math.random() * 2f, 0.4f);
                particles.glyph('!', 0xFF5555, -2.4f, 2.3f, 0.4f, 0f, 0.03f, 20, 0.3f);
            }
            pose.x = -2.4f;
            pose.z = 0.4f;
            pose.headYaw = (float) Math.sin((c - 1f) * 5) * 60f;
            pose.look = 0f;
            pose.squint = c < 1.3f ? 1f : 0f;
            pose.lean = c < 1.3f ? -12f : 0f;
        } else {
            float k = clamp01((c - 3.0f) / 1.3f);
            pose.x = -2.4f * (1f - smooth(k));
            pose.z = 0.4f * (1f - smooth(k));
            pose.facing = k < 1f ? 90f * (float) Math.sin(Math.PI * k) : 0f;
            pose.legs = (float) Math.sin(c * 11) * 30f * (k < 1f ? 1f : 0f);
            pose.legsWeight = 1f;
        }
    }

    // -- hud: frames pop up round you one by one, each holding a readout ----------------------------------------

    private void hud(float c, PlayerFigure.Pose pose) {
        int n = frames(c);
        pose.look = 0f;
        pose.headYaw = c < 2.5f ? -45f + 30f * Math.max(0, n - 1) : (float) Math.sin(c * 1.5f) * 40f;
        pose.headPitch = -12f;
        for (int i = 0; i < 4; i++) if (at(c, 0.3f + i * 0.5f)) particles.poof(hudX(i), 2.3f, hudZ(i), 3, 0.15f);
    }

    private static int frames(float c) {
        return c < 0.3f ? 0 : Math.min(4, (int) ((c - 0.3f) / 0.5f) + 1);
    }

    private static float hudX(int i) {
        return (float) Math.sin(Math.toRadians(-60 + i * 40)) * 1.4f;
    }

    private static float hudZ(int i) {
        return (float) Math.cos(Math.toRadians(-60 + i * 40)) * 1.4f;
    }

    // -- profit tracker: gold rains onto a growing pile while the takings tick up ---------------------------------

    private void profit(float c, PlayerFigure.Pose pose) {
        pose.facing = 40f;
        pose.headPitch = 25f;
        pose.look = 0.2f;
        if (c < 4.5f && Math.random() < 0.3) {
            particles.item(GOLD_NUGGET, 1.1f + (float) (Math.random() - 0.5) * 0.5f, 3f, 1.1f + (float) (Math.random() - 0.5) * 0.5f,
                    -0.05f, 18, 0.15f);
        }
        // counting along
        pose.headPitch += (float) Math.sin(c * 8) * 5f;
        if (c > 4.6f) {
            float k = (c - 4.6f) / 1.4f;
            pose.right = pose.left = -160f * (float) Math.sin(Math.PI * k);
            pose.rightWeight = pose.leftWeight = 1f;
            pose.y = (float) Math.sin(Math.PI * k) * 0.25f;
        }
    }

    private String profitText(float c) {
        int coins = (int) (clamp01(c / 4.5f) * 48_750);
        return "+" + String.format(java.util.Locale.ROOT, "%,d", coins) + " coins";
    }

    // -- nick hider: your name tag scrambles and settles on a fake --------------------------------------------------

    private void nickHider(float c, PlayerFigure.Pose pose) {
        if (c < 1f) {
            pose.right = -150f * smooth(c / 0.3f);
            pose.rightWeight = 1f;
            pose.headPitch = -20f;
        } else if (c < 1.3f) {
            pose.right = -150f + 100f * smooth((c - 1f) / 0.3f);
            pose.rightWeight = 1f;
            if (at(c, 1.15f)) particles.poof(0f, 2.3f, 0f, 4, 0.2f);
        } else {
            pose.headPitch = -25f;
            pose.look = 0f;
            pose.tilt = (float) Math.sin(c * 3) * 8f;
        }
        String glyphs = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghkmnpqrstuvwxyz0123456789#%&?";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) sb.append(glyphs.charAt((int) (Math.random() * glyphs.length())));
        obfuscated = sb.toString();
    }

    private String nickText(float c) {
        if (c < 1.15f) return "Steve";
        if (c < 2.6f) return obfuscated;
        return "Hidden";
    }

    // -- freecam: spyglass up, watching your floating camera drift round the farm --------------------------------

    private Vector3f camAt(float c) {
        double a = c / 5.5 * Math.PI * 2;
        return new Vector3f((float) Math.sin(a) * 3.5f, 2.6f + (float) Math.sin(a * 2) * 0.6f, 1.5f + (float) Math.cos(a) * 3f);
    }

    private void freecam(float c, PlayerFigure.Pose pose) {
        Vector3f eye = camAt(c);
        pose.right = -160f;
        pose.rightWeight = 1f;
        pose.look = 0f;
        float yaw = (float) Math.toDegrees(Math.atan2(eye.x, eye.z));
        pose.turn = Math.max(-60f, Math.min(60f, yaw)) * 0.6f;
        pose.headYaw = Math.max(-70f, Math.min(70f, yaw - pose.turn));
        pose.headPitch = -18f;
        if (Math.random() < 0.3) particles.endRod(eye.x, eye.y, eye.z);
    }

    // -- freelook: hoeing along while the head swivels right round like an owl's ---------------------------------

    private void freelook(float c, PlayerFigure.Pose pose) {
        pose.x = (float) Math.sin(c * 1.4f) * 1.4f;
        pose.legs = (float) Math.sin(c * 9) * 18f;
        pose.legsWeight = 1f;
        pose.right = -45f - 60f * Math.abs((float) Math.sin(c * 7));
        pose.rightWeight = 1f;
        pose.look = 0f;
        pose.headYaw = (float) Math.sin(c * 1.2f) * 150f;
        pose.headPitch = (float) Math.sin(c * 2.1f) * 15f;
    }

    // -- pip: a little picture floats beside you, flicking between scenes; you point at it ------------------------

    private void pip(float c, PlayerFigure.Pose pose) {
        pose.right = -120f;
        pose.rightWeight = 1f;
        pose.turn = 25f;
        pose.look = 0f;
        pose.headYaw = 25f;
        pose.headPitch = -10f;
        if (at(c, 0.01f) || at(c, 2.25f)) particles.poof(-1.2f, 2f, 1.2f, 4, 0.2f);
    }

    // -- fun: fireworks off all round, you cheering ------------------------------------------------------------

    private final float[] rockets = {-1f, -1f, -1f};
    private final float[] rocketX = new float[3], rocketZ = new float[3];

    private void fun(float c, PlayerFigure.Pose pose) {
        for (int i = 0; i < 3; i++) {
            float due = 0.3f + i * 1.4f;
            if (at(c, due)) {
                rockets[i] = 0f;
                double a = Math.random() * Math.PI * 2;
                rocketX[i] = (float) Math.cos(a) * 2.5f;
                rocketZ[i] = 1.5f + (float) Math.sin(a) * 1.5f;
            }
            if (rockets[i] >= 0f) {
                rockets[i] += dt;
                float y = rockets[i] * 6f;
                if (Math.random() < 0.6) particles.spark(rocketX[i], y, rocketZ[i], 0f, -0.02f, 0f, 0xFFDDAA);
                if (rockets[i] >= 0.9f) {
                    rockets[i] = -1f;
                    int color = FIREWORK[(int) (Math.random() * FIREWORK.length)];
                    for (int k = 0; k < 50; k++) {
                        double a = Math.random() * Math.PI * 2, b = Math.random() * Math.PI - Math.PI / 2;
                        float s = 0.3f;
                        particles.spark(rocketX[i], 5.4f, rocketZ[i], (float) (Math.cos(a) * Math.cos(b)) * s,
                                (float) Math.sin(b) * s, (float) (Math.sin(a) * Math.cos(b)) * s, color);
                    }
                }
            }
        }
        // cheering at each burst
        float beat = (c % 1.4f) / 1.4f;
        pose.right = pose.left = -165f * (float) Math.sin(Math.PI * clamp01(beat * 1.6f));
        pose.rightWeight = pose.leftWeight = 1f;
        pose.headPitch = -35f;
        pose.look = 0f;
        pose.y = (float) Math.max(0, Math.sin(Math.PI * clamp01((beat - 0.6f) / 0.3f))) * 0.2f;
    }

    // -- ungrab mouse: leashed to a post, you strain, the lead snaps and you tumble free -------------------------

    private static final Vector3f POST_AT = new Vector3f(-2.2f, 0f, 1.2f);

    private boolean leashed(float c) {
        return c < 2.6f;
    }

    private void ungrab(float c, PlayerFigure.Pose pose) {
        if (c < 2.6f) {
            // pulling the other way
            pose.facing = 120f;
            pose.lean = 20f + (float) Math.sin(c * 12) * 4f;
            pose.legs = (float) Math.sin(c * 10) * 25f;
            pose.legsWeight = 1f;
            pose.left = -60f;
            pose.leftWeight = 1f;
            pose.x = 0.2f + (float) Math.sin(c * 12) * 0.03f;
            pose.squint = 1f;
            if (Math.random() < 0.08) particles.cloud(0.4f, 1.6f, 0.2f, 0.02f, 0.02f, 0f, 0.4f);
        } else if (c < 3.4f) {
            if (at(c, 2.6f)) {
                particles.poof(POST_AT.x, 1f, POST_AT.z, 6, 0.2f);
                particles.crit(-1f, 1.1f, 0.7f, 8, false);
            }
            float k = (c - 2.6f) / 0.8f;
            pose.facing = 120f;
            pose.x = 0.2f + 1.2f * smooth(k);
            pose.z = -0.6f * smooth(k);
            pose.lean = 20f + 25f * (float) Math.sin(Math.PI * k);
            pose.right = pose.left = -150f * (float) Math.sin(Math.PI * k);
            pose.rightWeight = pose.leftWeight = 1f;
        } else {
            float k = clamp01((c - 3.4f) / 2f);
            pose.x = 1.4f * (1f - smooth(k));
            pose.z = -0.6f * (1f - smooth(k));
            pose.facing = 120f * (1f - smooth(k));
            pose.right = pose.left = -160f * (float) Math.sin(Math.PI * clamp01((c - 3.4f) / 0.8f));
            pose.rightWeight = pose.leftWeight = 1f;
            if (at(c, 3.5f)) particles.happy(1.4f, 2f, -0.6f, 6, 0.3f);
        }
    }

    // -- skybox: the sun crosses the sky, sets, the moon and the stars come out, you yawn ------------------------

    private void skybox(float c, PlayerFigure.Pose pose) {
        float sun = c / 9f * 360f;
        pose.look = 0f;
        float elevation = (float) Math.sin(Math.toRadians(sun));
        pose.headPitch = -25f - 15f * Math.abs(elevation);
        pose.headYaw = (float) Math.cos(Math.toRadians(sun)) * 40f;
        if (elevation < -0.2f && Math.random() < 0.15) {
            particles.endRod((float) (Math.random() - 0.5) * 16f, 6f + (float) Math.random() * 3f, 4f + (float) Math.random() * 6f);
        }
        if (c > 6.0f && c < 7.2f) {
            float s = (float) Math.sin(Math.PI * (c - 6.0f) / 1.2f);
            pose.right = pose.left = -175f;
            pose.rightWeight = pose.leftWeight = s;
            pose.squint = s > 0.3f ? 1f : 0f;
            pose.lean = -10f * s;
        }
    }

    // -- hud colors / menu colors: dye flung about in splashes of colour ----------------------------------------

    private void dyes(float c, PlayerFigure.Pose pose, int[] tints) {
        int which = (int) (c / 1.125f) % 4;
        float k = (c % 1.125f) / 1.125f;
        pose.facing = (which - 1.5f) * 30f;
        pose.right = k < 0.4f ? 30f * smooth(k / 0.4f) : 30f - 170f * smooth((k - 0.4f) / 0.25f);
        pose.rightWeight = 1f;
        pose.lean = k > 0.4f && k < 0.7f ? 10f : 0f;
        if (at(c, which * 1.125f + 0.6f)) {
            double f = Math.toRadians(pose.facing);
            float fx = (float) Math.sin(f), fz = (float) Math.cos(f);
            for (int i = 0; i < 24; i++) {
                particles.spell(fx * 0.5f, 1.6f, fz * 0.5f, fx * (0.08f + (float) Math.random() * 0.1f) + (float) (Math.random() - 0.5) * 0.08f,
                        0.06f + (float) Math.random() * 0.08f, fz * (0.08f + (float) Math.random() * 0.1f) + (float) (Math.random() - 0.5) * 0.08f,
                        tints[which]);
            }
        }
    }

    private Identifier dye(float c, boolean menu) {
        int which = (int) (c / 1.125f) % 4;
        return mc("textures/item/" + (menu ? MENU_DYES : HUD_DYES)[which] + "_dye.png");
    }

    // -- miscellaneous: throw a lever and watch the signal run down the wire to the lamp -------------------------

    private static final float WIRE_X0 = -1.5f, WIRE_Z = 1.6f;

    private float power(float c) {
        return c < 0.8f ? 0f : c < 3.8f ? Math.min(1f, (c - 0.8f) / 1.2f) : 0f;
    }

    private void redstone(float c, PlayerFigure.Pose pose) {
        pose.facing = (float) Math.toDegrees(Math.atan2(WIRE_X0, WIRE_Z));
        pose.headPitch = 20f;
        pose.look = 0f;
        if (c > 0.4f && c < 0.9f || c > 3.4f && c < 3.9f) {
            pose.right = -60f - 30f * (float) Math.sin(Math.PI * ((c % 3f) - 0.4f) / 0.5f);
            pose.rightWeight = 1f;
            pose.lean = 10f;
        }
        if (c > 2f && c < 3.4f) {
            // watch the lamp come on
            pose.facing = (float) Math.toDegrees(Math.atan2(2f, WIRE_Z));
            pose.headYaw = 10f;
        }
        if (at(c, 2.0f)) particles.happy(2.2f, 1.2f, WIRE_Z, 4, 0.3f);
    }

    // -- discord: ring the bell, the notes ring out --------------------------------------------------------------

    private static final Vector3f BELL_AT = new Vector3f(1.4f, 0f, 1.4f);
    private float swing;
    private float swingV;

    private void bell(float c, PlayerFigure.Pose pose) {
        pose.facing = (float) Math.toDegrees(Math.atan2(BELL_AT.x, BELL_AT.z));
        pose.look = 0f;
        pose.headPitch = -10f;
        float k = (c % 1.5f) / 1.5f;
        pose.right = k < 0.3f ? 20f * smooth(k / 0.3f) : 20f - 130f * smooth((k - 0.3f) / 0.15f);
        pose.rightWeight = 1f;
        if (at(c, (int) (c / 1.5f) * 1.5f + 0.6f)) {
            swingV += 9f;
            for (int i = 0; i < 4; i++) particles.note(BELL_AT.x, 2.1f, BELL_AT.z);
        }
        swingV += (-60f * swing - 3f * swingV) * dt;
        swing += swingV * dt;
    }

    // -- geometry -----------------------------------------------------------------------------------------------

    void build(Matrix4f local, PlayerFigure figure, Function<Identifier, SceneClone.Buffer> buffer, Vector3f right,
               Vector3f up) {
        if (!handles(focus)) return;
        float c = scene % loop(focus);
        Matrix4f arm = figure.rightArmFrame();
        Matrix4f toFarm = new Matrix4f(local).invert();
        Vector3f hand = toFarm.transformPosition(arm.transformPosition(0f, -11f, 0f, new Vector3f()));
        switch (focus) {
            case "Auto Pest Exchange" -> {
                Matrix4f m = new Matrix4f(local).translate(TRADER_AT.x, 0f, TRADER_AT.z)
                        .rotateY((float) Math.atan2(-TRADER_AT.x, -TRADER_AT.z));
                float nod = c >= 1.8f && c < 2.6f ? (float) Math.sin((c - 1.8f) * 12) * 0.3f : 0f;
                SceneActors.villager(buffer.apply(TRADER), m, (float) Math.sin(time) * 0.2f, nod, 0f, 0f,
                        c > 2.6f && c < 4.2f ? -0.4f : 0f);
                if (c < 1.8f) {
                    float k = (c % 0.6f - 0.25f) / 0.2f;
                    if (k > 0f && k < 1f) {
                        Vector3f at = new Vector3f(hand).lerp(new Vector3f(TRADER_AT.x, 1.5f, TRADER_AT.z), k);
                        at.y += (float) Math.sin(Math.PI * k) * 0.6f;
                        Identifier skin = heads.apply((int) (c / 0.6f) % 6);
                        if (skin != null) {
                            SceneActors.head(buffer.apply(skin), new Matrix4f(local).translate(at.x, at.y, at.z)
                                    .rotateY(k * 6f).scale(0.45f).translate(0f, -1.75f, 0f));
                        }
                    }
                } else if (c >= 2.6f && c < 4.2f) {
                    float k = ((c - 2.6f) % 0.5f) / 0.4f;
                    if (k < 1f) {
                        Vector3f at = new Vector3f(TRADER_AT.x, 1.4f, TRADER_AT.z).lerp(new Vector3f(0f, 1.2f, 0.4f), k);
                        at.y += (float) Math.sin(Math.PI * k) * 0.7f;
                        FarmSkits.billboard(buffer.apply(GOLD_INGOT), local, at.x, at.y, at.z, 0.2f, right, up);
                    }
                }
            }
            case "Auto Greenhouse" -> {
                for (int i = 0; i < 9; i++) {
                    float x = GH_X - 1f + (i % 3), z = GH_Z - 1f + (i / 3);
                    crop(buffer, local, x, z, crops[i % 5]);
                }
                for (int i = 0; i < 9; i++) {
                    if (greenhouse[i] <= 0f) continue;
                    float s = backOut(greenhouse[i]);
                    int gx = i % 3, gz = i / 3;
                    float x = GH_X - 1f + gx, z = GH_Z - 1f + gz;
                    // walls on the outside ring, a roof over everything
                    Matrix4f roof = new Matrix4f(local).translate(x, 1f + 0.5f, z).scale(s).translate(-0.5f, -0.5f, -0.5f);
                    cube(buffer, roof, GLASS, GLASS, GLASS);
                    if (gx != 1 || gz != 1) {
                        Matrix4f wall = new Matrix4f(local).translate(x, 0.5f, z).scale(s).translate(-0.5f, -0.5f, -0.5f);
                        if (gz != 0 || gx != 1) cube(buffer, wall, GLASS, GLASS, GLASS);
                    }
                }
            }
            case "Farming QOL" -> {
                chest(buffer.apply(CHEST), new Matrix4f(local).translate(CHEST_AT.x, 0f, CHEST_AT.z)
                        .rotateY((float) Math.atan2(-CHEST_AT.x, -CHEST_AT.z)), lid);
                if (c > 0.8f && c < 4.2f) {
                    float k = ((c - 0.8f) % 0.68f) / 0.68f;
                    if (k > 0.5f) {
                        float f = (k - 0.5f) / 0.5f;
                        Vector3f at = new Vector3f(hand).lerp(new Vector3f(CHEST_AT.x, 0.9f, CHEST_AT.z), f);
                        at.y += (float) Math.sin(Math.PI * f) * 0.6f;
                        Identifier item = SORTED[(int) ((c - 0.8f) / 0.68f) % SORTED.length];
                        FarmSkits.billboard(buffer.apply(item), local, at.x, at.y, at.z, 0.18f, right, up);
                    }
                }
            }
            case "Failsafe Settings" -> {
                if (c < 1.2f) FarmSkits.held(buffer.apply(TOTEM), arm, 0.9f);
                else if (c < 2.6f) {
                    // the totem rises and swells as it pops, the way it fills the screen in game
                    float k = (c - 1.2f) / 1.4f;
                    float size = 0.25f + 0.5f * (float) Math.sin(Math.PI * Math.min(1f, k * 1.4f));
                    if (k < 0.85f) {
                        FarmSkits.billboard(buffer.apply(TOTEM), local, 0f, 1.5f + k * 1.2f, 0.6f, size, right, up);
                    }
                }
            }
            case "GUI Opened" -> {
                float s = guiScale(c);
                if (s > 0.01f) {
                    Vector3f center = local.transformPosition(0f, 1.6f, 1.6f, new Vector3f());
                    panel(buffer.apply(CONTAINER), center, 0.9f * s, 0.62f * s, right, up, 0f, 0f, 176f / 256f, 125f / 256f);
                }
            }
            case "Rotation" -> {
                float spin = c >= 0.6f && c < 1.8f ? (c - 0.6f) * 40f : 0f;
                Matrix4f m = new Matrix4f(local);
                FarmSkits.billboard(buffer.apply(COMPASS), m, 0f, 2.55f, 0f, 0.22f,
                        new Vector3f(right).mul((float) Math.cos(spin)), up);
            }
            case "World Change" -> {
                if (c < 1.9f) {
                    FarmSkits.billboard(buffer.apply(ENDER_EYE), local, 0.2f, eyeY(c), 0.5f + c * 0.3f, 0.16f, right, up);
                    if (Math.random() < 0.5) particles.portal(0.2f, eyeY(c), 0.5f + c * 0.3f, 0.2f, eyeY(c) - 0.3f, 0.5f, true);
                }
            }
            case "Inventory Slot Changed" -> {
                Vector3f center = local.transformPosition(0f, 2.7f, 0f, new Vector3f());
                float hw = 1.3f, hh = hw * 22f / 182f;
                panel(buffer.apply(HOTBAR), center, hw, hh, right, up, 0f, 0f, 1f, 1f);
                float slotW = hw * 2f * 20f / 182f;
                for (int i = 0; i < 9; i++) {
                    Vector3f at = new Vector3f(center).fma(-hw + hw * 2f * (3f + i * 20f) / 182f + slotW * 0.4f, right)
                            .fma(-hh * 2f / 22f * 0.5f, up).add(new Vector3f(right).cross(up).mul(0.01f));
                    panel(buffer.apply(HOTBAR_ITEMS[i]), at, slotW * 0.4f, slotW * 0.4f, right, up, 0f, 0f, 1f, 1f);
                }
                int s = slot(c);
                float sx = -hw + hw * 2f * (s * 20f + 11f) / 182f;
                Vector3f sel = new Vector3f(center).fma(sx, right).add(new Vector3f(right).cross(up).mul(0.02f));
                panel(buffer.apply(HOTBAR_SELECTION), sel, hw * 24f / 182f, hh * 24f / 22f, right, up, 0f, 0f, 1f, 23f / 24f);
                FarmSkits.held(buffer.apply(HOTBAR_ITEMS[s]), arm, 1f);
            }
            case "BPS" -> {
                FarmSkits.held(buffer.apply(HOE), arm, 1f);
                int frame = (int) (time * 16) % 64;
                Matrix4f m = new Matrix4f(local);
                FarmSkits.billboard(buffer.apply(CLOCK[frame]), m, figure.pose.x, 2.55f, 0f, 0.2f, right, up);
                text(buffer, local, bpsText(c), figure.pose.x, 2.95f, 0f, 0.16f, c > 2.4f && c < 3.6f ? 0xFF5555 : 0x55FF55,
                        right, up);
            }
            case "Dirt Check" -> {
                for (int i = 0; i < 5; i++) crop(buffer, local, -2f + i, 1.6f, i == 2 && c >= 0.5f && c < 3.1f ? -1f : 1f);
                if (c >= 0.5f && c < 3.1f) {
                    float pop = backOut(Math.min(1f, (c - 0.5f) / 0.25f));
                    float shake = c > 1.5f ? (float) Math.sin(time * 40) * 0.03f : 0f;
                    Matrix4f m = new Matrix4f(local).translate(shake, 0f, 1.6f).scale(pop).translate(-0.5f, 0f, -0.5f);
                    cube(buffer, m, mc("textures/block/dirt.png"), DIRT, DIRT);
                }
                if (c >= 1.5f && c < 3.2f) FarmSkits.held(buffer.apply(SHOVEL), arm, 1f);
                else FarmSkits.held(buffer.apply(HOE), arm, 1f);
            }
            case "Ghost Block" -> {
                float a = ghostAlpha(c);
                if (a > 0.05f) {
                    Matrix4f m = new Matrix4f(local).translate(-0.5f, 0f, 0.75f);
                    if (a > 0.5f) cube(buffer, m, GLASS, GLASS, GLASS);
                    else cube(buffer, new Matrix4f(m).translate(0.5f, 0.5f, 0.5f).scale(0.98f).translate(-0.5f, -0.5f, -0.5f),
                            GLASS, GLASS, GLASS);
                }
            }
            case "Player Nearby" -> {
                if (strangerOut) stranger.build(buffer.apply(STRANGER), local, false, time);
            }
            case "TP Check" -> {
                if (c >= 0.7f && c < 1.0f) {
                    float k = (c - 0.7f) / 0.3f;
                    FarmSkits.billboard(buffer.apply(ENDER_PEARL), local, -2.4f * k, 1.2f + (float) Math.sin(Math.PI * k) * 1f,
                            0.4f * k, 0.12f, right, up);
                }
            }
            case "HUD" -> {
                int n = frames(c);
                for (int i = 0; i < n; i++) {
                    float pop = backOut(Math.min(1f, (c - 0.3f - i * 0.5f) / 0.3f));
                    float bob = (float) Math.sin(time * 2 + i) * 0.05f;
                    Vector3f center = local.transformPosition(hudX(i), 2.3f + bob, hudZ(i), new Vector3f());
                    panel(buffer.apply(ITEM_FRAME), center, 0.22f * pop, 0.22f * pop, right, up, 0f, 0f, 1f, 1f);
                    Vector3f front = new Vector3f(center).add(new Vector3f(right).cross(up).mul(0.01f));
                    panel(buffer.apply(HUD_ITEMS[i]), front, 0.15f * pop, 0.15f * pop, right, up, 0f, 0f, 1f, 1f);
                }
            }
            case "Profit Tracker" -> {
                int ingots = (int) (clamp01(c / 4.5f) * 9);
                for (int i = 0; i < ingots; i++) {
                    int row = i / 3;
                    float x = 1.1f + (i % 3 - 1) * 0.18f + (row % 2) * 0.09f, z = 1.1f + (i % 2) * 0.1f;
                    FarmSkits.billboard(buffer.apply(GOLD_INGOT), local, x, 0.12f + row * 0.13f, z, 0.14f, right, up);
                }
                text(buffer, local, profitText(c), 1.1f, 1.2f + Math.min(c, 4.5f) * 0.05f, 1.1f, 0.13f, 0xFFD700, right, up);
            }
            case "Nick Hider" -> {
                if (c < 1.15f) FarmSkits.held(buffer.apply(NAME_TAG), arm, 0.8f);
                text(buffer, local, nickText(c), figure.pose.x, 2.3f, figure.pose.z, 0.18f,
                        c < 1.15f ? 0xFFFFFF : c < 2.6f ? 0xAAAAAA : 0x55FFFF, right, up);
            }
            case "Freecam" -> {
                FarmSkits.held(buffer.apply(SPYGLASS), arm, 0.7f);
                Vector3f eye = camAt(c);
                FarmSkits.billboard(buffer.apply(ENDER_EYE), local, eye.x, eye.y, eye.z, 0.22f, right, up);
            }
            case "Freelook" -> FarmSkits.held(buffer.apply(HOE), arm, 1f);
            case "PiP" -> {
                Identifier painting = PAINTINGS[((int) (c / 2.25f) + round * 2) % PAINTINGS.length];
                float pop = backOut(Math.min(1f, (c % 2.25f) / 0.3f));
                Vector3f center = local.transformPosition(-1.2f, 2f + (float) Math.sin(time * 2) * 0.05f, 1.2f, new Vector3f());
                panel(buffer.apply(ITEM_FRAME), center, 0.55f * pop, 0.55f * pop, right, up, 0f, 0f, 1f, 1f);
                Vector3f front = new Vector3f(center).add(new Vector3f(right).cross(up).mul(0.01f));
                panel(buffer.apply(painting), front, 0.45f * pop, 0.45f * pop, right, up, 0f, 0f, 1f, 1f);
            }
            case "Fun" -> {
                for (int i = 0; i < 3; i++) {
                    if (rockets[i] < 0f) continue;
                    FarmSkits.billboard(buffer.apply(ROCKET), local, rocketX[i], rockets[i] * 6f, rocketZ[i], 0.2f, right, up);
                }
            }
            case "Ungrab Mouse" -> {
                // the post and its knot, and the lead to your hand while it holds
                Matrix4f post = new Matrix4f(local).translate(POST_AT.x - 0.125f, 0f, POST_AT.z - 0.125f).scale(0.25f, 1.2f, 0.25f);
                cube(buffer, post, POST, POST, POST);
                ModelBoxes.box(buffer.apply(KNOT), new Matrix4f(local).translate(POST_AT.x, 1.15f, POST_AT.z).scale(1f / 16f),
                        32, 32, false, -3, -4, -3, 3, 4, 3, 0, 0, 6, 8, 6);
                if (leashed(c)) {
                    Vector3f from = new Vector3f(POST_AT.x, 1.15f, POST_AT.z);
                    Vector3f to = toFarm.transformPosition(figure.leftArmFrame().transformPosition(0f, -11f, 0f, new Vector3f()));
                    rope(buffer.apply(LEAD), local, from, to, right, up, 0.03f);
                }
            }
            case "Skybox" -> {
                double a = Math.toRadians(c / 9f * 360f);
                float sx = (float) Math.cos(a) * 9f, sy = 1.5f + (float) Math.sin(a) * 7f;
                // vanilla adds these onto the sky; drawn solid, only their bright middles, without the black around them
                if (sy > -0.5f) {
                    panel(buffer.apply(SUN), local.transformPosition(sx, sy, 9f, new Vector3f()), 0.7f, 0.7f, right, up,
                            0.375f, 0.375f, 0.625f, 0.625f);
                }
                float mx = -sx, my = 1.5f - (float) Math.sin(a) * 7f;
                if (my > -0.5f) {
                    panel(buffer.apply(MOON), local.transformPosition(mx, my, 9f, new Vector3f()), 0.55f, 0.55f, right, up,
                            0.375f, 0.375f, 0.625f, 0.625f);
                }
            }
            case "HUD Colors", "Menu Colors" -> FarmSkits.held(buffer.apply(dye(c, "Menu Colors".equals(focus))), arm, 0.8f);
            case "Miscellaneous" -> circuit(buffer, local, c);
            case "Discord" -> {
                Matrix4f base = new Matrix4f(local).translate(BELL_AT.x, 0f, BELL_AT.z);
                cube(buffer, new Matrix4f(base).translate(-0.6f, 0f, -0.08f).scale(0.16f, 2.3f, 0.16f), LOG, LOG, LOG);
                cube(buffer, new Matrix4f(base).translate(0.44f, 0f, -0.08f).scale(0.16f, 2.3f, 0.16f), LOG, LOG, LOG);
                cube(buffer, new Matrix4f(base).translate(-0.6f, 2.15f, -0.08f).scale(1.2f, 0.16f, 0.16f), LOG, LOG, LOG);
                Matrix4f bell = new Matrix4f(base).translate(0f, 2.15f, 0f).rotateX(swing * 0.4f).scale(1f / 16f);
                ModelBoxes.box(buffer.apply(BELL), bell, 32, 32, false, -3, -9, -3, 3, -2, 3, 0, 0, 6, 7, 6);
                ModelBoxes.box(buffer.apply(BELL), bell, 32, 32, false, -4, -11, -4, 4, -9, 4, 0, 13, 8, 2, 8);
            }
            default -> {
            }
        }
    }

    // the miscellaneous circuit: a lever on stone, a run of dust, a repeater and the lamp at the end
    private void circuit(Function<Identifier, SceneClone.Buffer> buffer, Matrix4f local, float c) {
        float p = power(c);
        Matrix4f block = new Matrix4f(local).translate(WIRE_X0 - 0.5f, 0f, WIRE_Z - 0.5f);
        cube(buffer, block, STONE, STONE, STONE);
        float angle = c > 0.6f && c < 3.6f ? 0.6f : -0.6f;
        Matrix4f lever = new Matrix4f(local).translate(WIRE_X0, 1f, WIRE_Z).rotateX(angle).scale(1f / 16f);
        ModelBoxes.box(buffer.apply(COBBLE), new Matrix4f(local).translate(WIRE_X0, 1f, WIRE_Z).scale(1f / 16f), 16, 16, false,
                -2, 0, -4, 2, 3, 4, 0, 0, 4, 3, 8);
        ModelBoxes.box(buffer.apply(LEVER), lever, 16, 16, false, -1, 0, -1, 1, 10, 1, 7, 6, 2, 10, 2);
        int dots = 6;
        for (int i = 0; i < dots; i++) {
            float x = WIRE_X0 + 0.7f + i * 0.5f;
            float lit = p * (dots + 1) - i;
            int power = lit > 0f ? Math.max(1, 15 - i * 2) : 0;
            dust(buffer.apply(DUST), local, x, WIRE_Z, power);
        }
        Matrix4f rep = new Matrix4f(local).translate(WIRE_X0 + 3.4f - 0.5f, 0.01f, WIRE_Z - 0.5f);
        SceneActors.face(buffer.apply(p > 0.8f ? REPEATER_ON : REPEATER), rep, 0, 0.125f, 0, 1, 0.125f, 0, 1, 0.125f, 1, 0,
                0.125f, 1, 1f, 1f, 1f);
        Matrix4f lamp = new Matrix4f(local).translate(WIRE_X0 + 4.1f - 0.5f, 0f, WIRE_Z - 0.5f);
        Identifier face = p >= 1f ? LAMP_ON : LAMP;
        cube(buffer, lamp, face, face, face);
    }

    // a dot of redstone dust flat on the ground, coloured for its power like the wire
    private static void dust(SceneClone.Buffer out, Matrix4f local, float x, float z, int power) {
        float f = power / 15f;
        float r = f * 0.6f + (f > 0f ? 0.4f : 0.3f);
        float g = Math.max(0f, Math.min(1f, f * f * 0.7f - 0.5f));
        float b = Math.max(0f, Math.min(1f, f * f * 0.6f - 0.7f));
        int rgb = Math.round(r * 255) << 16 | Math.round(g * 255) << 8 | Math.round(b * 255);
        int color = SceneClone.rgba(rgb, 1f, 255);
        Matrix4f m = new Matrix4f(local).translate(x - 0.35f, 0.015f, z - 0.35f).scale(0.7f, 1f, 0.7f);
        Vector3f a = m.transformPosition(0, 0, 0, new Vector3f()), bb = m.transformPosition(1, 0, 0, new Vector3f());
        Vector3f cc = m.transformPosition(1, 0, 1, new Vector3f()), d = m.transformPosition(0, 0, 1, new Vector3f());
        out.vertex(a.x, a.y, a.z, 0f, 0f, color);
        out.vertex(bb.x, bb.y, bb.z, 1f, 0f, color);
        out.vertex(cc.x, cc.y, cc.z, 1f, 1f, color);
        out.vertex(a.x, a.y, a.z, 0f, 0f, color);
        out.vertex(cc.x, cc.y, cc.z, 1f, 1f, color);
        out.vertex(d.x, d.y, d.z, 0f, 1f, color);
    }

    // a single chest as ChestModel builds it, its lid hinged at the back and lifted by open
    private static void chest(SceneClone.Buffer out, Matrix4f base, float open) {
        Matrix4f m = new Matrix4f(base).translate(-0.5f, 0f, -0.5f).scale(1f / 16f);
        ModelBoxes.box(out, m, 64, 64, false, 1, 0, 1, 15, 10, 15, 0, 19, 14, 10, 14);
        Matrix4f lid = new Matrix4f(m).translate(0f, 9f, 1f).rotateX(-open * 1.2f);
        ModelBoxes.box(out, lid, 64, 64, false, 1, 0, 0, 15, 5, 14, 0, 0, 14, 5, 14);
        ModelBoxes.box(out, lid, 64, 64, false, 7, -2, 14, 9, 2, 15, 0, 0, 2, 4, 1);
    }

    // a farmland tile with wheat at a growth 0..1; below 0 leaves the soil bare
    private void crop(Function<Identifier, SceneClone.Buffer> buffer, Matrix4f local, float x, float z, float grown) {
        Matrix4f m = new Matrix4f(local).translate(x - 0.5f, 0.01f, z - 0.5f);
        SceneActors.face(buffer.apply(FARMLAND), m, 0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1, 1f, 1f, 1f);
        if (grown < 0f) return;
        SceneClone.Buffer out = buffer.apply(WHEAT[Math.min(7, (int) (grown * 7.999f))]);
        SceneActors.face(out, m, 0.1f, 1, 0.1f, 0.9f, 1, 0.9f, 0.9f, 0, 0.9f, 0.1f, 0, 0.1f, 1f, 1f, 1f);
        SceneActors.face(out, m, 0.9f, 1, 0.1f, 0.1f, 1, 0.9f, 0.1f, 0, 0.9f, 0.9f, 0, 0.1f, 1f, 1f, 0.9f);
    }

    // a unit cube, its top, sides and bottom each with their own texture and the usual face shading
    private static void cube(Function<Identifier, SceneClone.Buffer> buffer, Matrix4f m, Identifier top, Identifier side,
                             Identifier bottom) {
        SceneActors.face(buffer.apply(top), m, 0, 1, 0, 1, 1, 0, 1, 1, 1, 0, 1, 1, 1f, 1f, 1f);
        SceneActors.face(buffer.apply(bottom), m, 0, 0, 1, 1, 0, 1, 1, 0, 0, 0, 0, 0, 1f, 1f, 0.5f);
        SceneClone.Buffer s = buffer.apply(side);
        SceneActors.face(s, m, 1, 1, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 1f, 1f, 0.8f);
        SceneActors.face(s, m, 0, 1, 1, 1, 1, 1, 1, 0, 1, 0, 0, 1, 1f, 1f, 0.8f);
        SceneActors.face(s, m, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0, 0, 1f, 1f, 0.6f);
        SceneActors.face(s, m, 1, 1, 1, 1, 1, 0, 1, 0, 0, 1, 0, 1, 1f, 1f, 0.6f);
    }

    // a camera-facing rectangle in the buffer's space showing part of a texture
    private static void panel(SceneClone.Buffer out, Vector3f c, float hw, float hh, Vector3f right, Vector3f up,
                              float u0, float v0, float u1, float v1) {
        float rx = right.x * hw, ry = right.y * hw, rz = right.z * hw, ux = up.x * hh, uy = up.y * hh, uz = up.z * hh;
        int color = 0xFFFFFFFF;
        out.vertex(c.x - rx + ux, c.y - ry + uy, c.z - rz + uz, u0, v0, color);
        out.vertex(c.x + rx + ux, c.y + ry + uy, c.z + rz + uz, u1, v0, color);
        out.vertex(c.x + rx - ux, c.y + ry - uy, c.z + rz - uz, u1, v1, color);
        out.vertex(c.x - rx + ux, c.y - ry + uy, c.z - rz + uz, u0, v0, color);
        out.vertex(c.x + rx - ux, c.y + ry - uy, c.z + rz - uz, u1, v1, color);
        out.vertex(c.x - rx - ux, c.y - ry - uy, c.z - rz - uz, u0, v1, color);
    }

    // a line of text in minecraft's font facing the camera on a dark plate, like a name tag
    private static void text(Function<Identifier, SceneClone.Buffer> buffer, Matrix4f local, String text, float x, float y,
                             float z, float height, int rgb, Vector3f right, Vector3f up) {
        Vector3f c = local.transformPosition(x, y, z, new Vector3f());
        float px = height / 8f;
        float width = 0f;
        for (int i = 0; i < text.length(); i++) width += (glyphWidth(text.charAt(i)) + 1) * px;
        width -= px;
        Vector3f toCam = new Vector3f(right).cross(up).normalize();
        SceneClone.Buffer plate = buffer.apply(BLACK);
        panel(plate, new Vector3f(c).fma(-0.01f, toCam), width / 2f + px * 2f, height * 0.65f, right, up, 0f, 0f, 0.1f, 0.1f);
        SceneClone.Buffer glyphs = buffer.apply(FONT);
        int color = SceneClone.rgba(rgb, 1f, 255);
        int shadow = SceneClone.rgba(rgb, 0.25f, 255);
        float cursor = -width / 2f;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            int w = glyphWidth(ch);
            if (ch < 256 && ch != ' ') {
                float u0 = (ch % 16) / 16f, v0 = (ch / 16) / 16f;
                // glyphs sit at the left of their 8 pixel cell
                Vector3f at = new Vector3f(c).fma(cursor + height / 2f, right);
                glyph(glyphs, new Vector3f(at).fma(px, right).fma(-px, up).fma(0.005f, toCam), height / 2f, right, up,
                        u0, v0, shadow);
                glyph(glyphs, new Vector3f(at).fma(0.01f, toCam), height / 2f, right, up, u0, v0, color);
            }
            cursor += (w + 1) * px;
        }
    }

    // pixel widths of minecraft's default font for the narrow characters; the rest are five wide
    private static int glyphWidth(char ch) {
        return switch (ch) {
            case 'i', '!', '.', ',', ':', ';', '|', '\'' -> 1;
            case 'l', '`' -> 2;
            case 't', 'I', ' ', '(', ')', '[', ']', '"', '*' -> 3;
            case 'f', 'k', '<', '>', '{', '}' -> 4;
            case '@', '~' -> 6;
            default -> 5;
        };
    }

    private static void glyph(SceneClone.Buffer out, Vector3f c, float half, Vector3f right, Vector3f up, float u0, float v0,
                              int color) {
        float u1 = u0 + 1f / 16f, v1 = v0 + 1f / 16f;
        float rx = right.x * half, ry = right.y * half, rz = right.z * half, ux = up.x * half, uy = up.y * half, uz = up.z * half;
        out.vertex(c.x - rx + ux, c.y - ry + uy, c.z - rz + uz, u0, v0, color);
        out.vertex(c.x + rx + ux, c.y + ry + uy, c.z + rz + uz, u1, v0, color);
        out.vertex(c.x + rx - ux, c.y + ry - uy, c.z + rz - uz, u1, v1, color);
        out.vertex(c.x - rx + ux, c.y - ry + uy, c.z - rz + uz, u0, v0, color);
        out.vertex(c.x + rx - ux, c.y + ry - uy, c.z + rz - uz, u1, v1, color);
        out.vertex(c.x - rx - ux, c.y - ry - uy, c.z - rz - uz, u0, v1, color);
    }

    // a sagging rope between two farm points, as a thin strip facing the camera
    private static void rope(SceneClone.Buffer out, Matrix4f local, Vector3f from, Vector3f to, Vector3f right, Vector3f up,
                             float width) {
        Vector3f toCam = new Vector3f(right).cross(up).normalize();
        Vector3f prev = null;
        int n = 12;
        for (int i = 0; i <= n; i++) {
            float k = i / (float) n;
            Vector3f p = new Vector3f(from).lerp(to, k);
            p.y -= (float) Math.sin(Math.PI * k) * 0.15f;
            Vector3f w = local.transformPosition(p, new Vector3f());
            if (prev != null) {
                Vector3f side = new Vector3f(w).sub(prev).cross(toCam);
                if (side.lengthSquared() < 1e-9f) side.set(right);
                side.normalize(width);
                int color = 0xFFFFFFFF;
                Vector3f a = new Vector3f(prev).add(side), b = new Vector3f(w).add(side), cc = new Vector3f(w).sub(side),
                        d = new Vector3f(prev).sub(side);
                out.vertex(a.x, a.y, a.z, 0f, 0f, color);
                out.vertex(b.x, b.y, b.z, 1f, 0f, color);
                out.vertex(cc.x, cc.y, cc.z, 1f, 0.1f, color);
                out.vertex(a.x, a.y, a.z, 0f, 0f, color);
                out.vertex(cc.x, cc.y, cc.z, 1f, 0.1f, color);
                out.vertex(d.x, d.y, d.z, 0f, 0.1f, color);
            }
            prev = w;
        }
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

    private static Identifier mc(String path) {
        return Identifier.withDefaultNamespace(path);
    }
}
