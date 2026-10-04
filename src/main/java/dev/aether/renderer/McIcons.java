package dev.aether.renderer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// item icons by id: a small curated table first, then the item's own items/<id>.json and model chain, so any vanilla
// item (and anything a resource pack adds) resolves the way the inventory draws it
public final class McIcons {

    public static final McIcon WHEAT = item("wheat");
    public static final McIcon WHEAT_SEEDS = item("wheat_seeds");
    public static final McIcon CARROT = item("carrot");
    public static final McIcon POTATO = item("potato");
    public static final McIcon PUMPKIN = item("pumpkin");
    public static final McIcon MELON = item("melon");
    public static final McIcon MELON_SLICE = item("melon_slice");
    public static final McIcon SUGAR_CANE = item("sugar_cane");
    public static final McIcon CACTUS = item("cactus");
    public static final McIcon COCOA_BEANS = item("cocoa_beans");
    public static final McIcon NETHER_WART = item("nether_wart");
    public static final McIcon RED_MUSHROOM = item("red_mushroom");
    public static final McIcon BROWN_MUSHROOM = item("brown_mushroom");
    public static final McIcon SUNFLOWER = item("sunflower");
    public static final McIcon ROSE_BUSH = item("rose_bush");

    public static final McIcon LIME_STAINED_GLASS_PANE = item("lime_stained_glass_pane");
    public static final McIcon ORANGE_STAINED_GLASS_PANE = item("orange_stained_glass_pane");
    public static final McIcon RED_STAINED_GLASS_PANE = item("red_stained_glass_pane");
    public static final McIcon GRAY_STAINED_GLASS_PANE = item("gray_stained_glass_pane");
    public static final McIcon BLACK_STAINED_GLASS_PANE = item("black_stained_glass_pane");
    public static final McIcon WHITE_STAINED_GLASS_PANE = item("white_stained_glass_pane");
    public static final McIcon LIGHT_GRAY_STAINED_GLASS_PANE = item("light_gray_stained_glass_pane");
    public static final McIcon WHITE_STAINED_GLASS = item("white_stained_glass");
    public static final McIcon GLASS = item("glass");

    public static final McIcon OAK_BUTTON = item("oak_button");
    public static final McIcon OAK_PLANKS = item("oak_planks");
    public static final McIcon DARK_OAK_PLANKS = item("dark_oak_planks");
    public static final McIcon OAK_SIGN = item("oak_sign");
    public static final McIcon CRAFTING_TABLE = item("crafting_table");
    public static final McIcon CHEST = item("chest");
    public static final McIcon HOPPER = item("hopper");
    public static final McIcon COMPOSTER = item("composter");
    public static final McIcon TARGET = item("target");
    public static final McIcon GRASS_BLOCK = item("grass_block");
    public static final McIcon HAY_BLOCK = item("hay_block");
    public static final McIcon SPONGE = item("sponge");
    public static final McIcon DAYLIGHT_DETECTOR = item("daylight_detector");
    public static final McIcon RED_BED = item("red_bed");
    public static final McIcon BELL = item("bell");
    public static final McIcon BARRIER = item("barrier");

    public static final McIcon ARROW = item("arrow");
    public static final McIcon COMPASS = item("compass");
    public static final McIcon RECOVERY_COMPASS = item("recovery_compass");
    public static final McIcon CLOCK = item("clock");
    public static final McIcon SPYGLASS = item("spyglass");
    public static final McIcon FILLED_MAP = item("filled_map");
    public static final McIcon ENDER_PEARL = item("ender_pearl");
    public static final McIcon ENDER_EYE = item("ender_eye");
    public static final McIcon DIAMOND_HOE = item("diamond_hoe");
    public static final McIcon GOLDEN_HOE = item("golden_hoe");
    public static final McIcon NETHERITE_HOE = item("netherite_hoe");
    public static final McIcon IRON_PICKAXE = item("iron_pickaxe");
    public static final McIcon DIAMOND_SHOVEL = item("diamond_shovel");
    public static final McIcon DIAMOND_SWORD = item("diamond_sword");
    public static final McIcon WOODEN_SWORD = item("wooden_sword");
    public static final McIcon FISHING_ROD = item("fishing_rod");
    public static final McIcon BRUSH = item("brush");
    public static final McIcon LEAD = item("lead");
    public static final McIcon TRIPWIRE_HOOK = item("tripwire_hook");
    public static final McIcon LAVA_BUCKET = item("lava_bucket");

    public static final McIcon ARMOR_STAND = item("armor_stand");
    public static final McIcon PAINTING = item("painting");
    public static final McIcon ITEM_FRAME = item("item_frame");
    public static final McIcon TOTEM_OF_UNDYING = item("totem_of_undying");
    public static final McIcon PLAYER_HEAD = item("player_head");
    public static final McIcon NAME_TAG = item("name_tag");
    public static final McIcon COMPARATOR = item("comparator");
    public static final McIcon REDSTONE = item("redstone");
    public static final McIcon GLOWSTONE_DUST = item("glowstone_dust");
    public static final McIcon GOLD_INGOT = item("gold_ingot");
    public static final McIcon GOLD_NUGGET = item("gold_nugget");
    public static final McIcon FIREWORK_ROCKET = item("firework_rocket");
    public static final McIcon KNOWLEDGE_BOOK = item("knowledge_book");
    public static final McIcon WRITABLE_BOOK = item("writable_book");
    public static final McIcon ENCHANTED_BOOK = item("enchanted_book");
    public static final McIcon MUSIC_DISC_CAT = item("music_disc_cat");
    public static final McIcon SLIME_BALL = item("slime_ball");
    public static final McIcon BONE_MEAL = item("bone_meal");
    public static final McIcon HONEY_BOTTLE = item("honey_bottle");

    public static final McIcon LIME_DYE = item("lime_dye");
    public static final McIcon GRAY_DYE = item("gray_dye");
    public static final McIcon BROWN_DYE = item("brown_dye");
    public static final McIcon GREEN_DYE = item("green_dye");
    public static final McIcon SILVERFISH_SPAWN_EGG = item("silverfish_spawn_egg");
    public static final McIcon VILLAGER_SPAWN_EGG = item("villager_spawn_egg");
    public static final McIcon STRIDER_SPAWN_EGG = item("strider_spawn_egg");

    // the inventory's fixed grass colour, the colormap at temperature 0.5 and downfall 1.0
    static final int GRASS_TINT = 0xFF7CBD6B;

    static final String STEVE_SKIN = "minecraft:textures/entity/player/wide/steve.png";

    private static final Identifier CUBE = Identifier.withDefaultNamespace("block/cube");

    private static final ItemTransform BLOCK_GUI = new ItemTransform(new Vector3f(30f, 225f, 0f), new Vector3f(),
            new Vector3f(0.625f, 0.625f, 0.625f));

    // dynamic models (compass needle, clock face) pin their idle frame whatever a pack dispatches on;
    // cactus is its own three-element model, kept here as the inset cube it draws as
    private static final Map<String, McIcon> CURATED = Map.of(
            "minecraft:compass", new McIcon.Sprite("minecraft:textures/item/compass_16.png"),
            "minecraft:recovery_compass", new McIcon.Sprite("minecraft:textures/item/recovery_compass_16.png"),
            "minecraft:clock", new McIcon.Sprite("minecraft:textures/item/clock_00.png"),
            "minecraft:cactus", new McIcon.Block("minecraft:textures/block/cactus_top.png",
                    "minecraft:textures/block/cactus_side.png", "minecraft:textures/block/cactus_side.png", 1f / 16f));

    private static final Map<String, Optional<McIcon>> cache = new ConcurrentHashMap<>();

    private McIcons() {}

    // "wheat" or "minecraft:pumpkin"; null for ids no item definition knows, or that nothing sensible can draw
    public static McIcon of(String itemId) {
        if (itemId == null || itemId.isBlank()) return null;
        return cached(new McIcon.Item(itemId).id());
    }

    // drops resolved icons and parsed models; textures are McTextures.invalidate's job
    public static void invalidate() {
        cache.clear();
        McModels.invalidate();
    }

    static McIcon drawable(McIcon icon) {
        return icon instanceof McIcon.Item item ? cached(item.id()) : icon;
    }

    private static McIcon cached(String id) {
        return cache.computeIfAbsent(id, McIcons::resolve).orElse(null);
    }

    // a model sprite id ("minecraft:block/stone") as a texture id ("minecraft:textures/block/stone.png")
    static String texture(Identifier sprite) {
        return sprite.getNamespace() + ":textures/" + sprite.getPath() + ".png";
    }

    private static McIcon item(String path) {
        return new McIcon.Item("minecraft:" + path);
    }

    private static Optional<McIcon> resolve(String id) {
        McIcon curated = CURATED.get(id);
        if (curated != null) return Optional.of(curated);
        Identifier item = Identifier.tryParse(id);
        JsonObject definition = item == null ? null : readItemDefinition(item);
        if (definition == null) {
            System.err.println("[Aether] No item definition for icon " + id);
            return Optional.empty();
        }
        return Optional.ofNullable(fromItemModel(object(definition, "model")));
    }

    private static JsonObject readItemDefinition(Identifier item) {
        Identifier location = item.withPath(path -> "items/" + path + ".json");
        try (InputStream in = McAssets.open(location)) {
            if (in == null) return null;
            JsonElement json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return json.isJsonObject() ? json.getAsJsonObject() : null;
        } catch (IOException | RuntimeException e) {
            System.err.println("[Aether] Could not read item definition " + item + ": " + e.getMessage());
            return null;
        }
    }

    // walks the item model tree to what an inventory slot shows with no live state: the gui case of display-context
    // selects, the fallback of other selects, the false branch of conditions and the lowest range entry
    private static McIcon fromItemModel(JsonObject node) {
        if (node == null) return null;
        return switch (type(node)) {
            case "model" -> fromModel(string(node, "model"), tints(node));
            case "special" -> fromSpecial(node);
            case "select" -> fromItemModel(selectCase(node));
            case "condition" -> fromItemModel(object(node, "on_false"));
            case "range_dispatch" -> fromItemModel(lowestEntry(node));
            case "composite" -> firstDrawable(node.getAsJsonArray("models"));
            default -> null;
        };
    }

    private static JsonObject selectCase(JsonObject node) {
        JsonArray cases = node.getAsJsonArray("cases");
        if ("display_context".equals(path(string(node, "property"))) && cases != null) {
            for (JsonElement entry : cases) {
                JsonElement when = entry.getAsJsonObject().get("when");
                boolean gui = when != null && (when.isJsonArray()
                        ? when.getAsJsonArray().contains(new JsonPrimitive("gui"))
                        : "gui".equals(when.getAsString()));
                if (gui) return object(entry.getAsJsonObject(), "model");
            }
        }
        JsonObject fallback = object(node, "fallback");
        if (fallback != null || cases == null || cases.isEmpty()) return fallback;
        return object(cases.get(0).getAsJsonObject(), "model");
    }

    private static JsonObject lowestEntry(JsonObject node) {
        JsonArray entries = node.getAsJsonArray("entries");
        JsonObject lowest = null;
        float threshold = Float.POSITIVE_INFINITY;
        if (entries != null) {
            for (JsonElement entry : entries) {
                JsonObject candidate = entry.getAsJsonObject();
                float value = candidate.has("threshold") ? candidate.get("threshold").getAsFloat() : 0f;
                if (value < threshold) {
                    threshold = value;
                    lowest = object(candidate, "model");
                }
            }
        }
        return lowest != null ? lowest : object(node, "fallback");
    }

    private static McIcon firstDrawable(JsonArray models) {
        if (models == null) return null;
        for (JsonElement model : models) {
            McIcon icon = model.isJsonObject() ? fromItemModel(model.getAsJsonObject()) : null;
            if (icon != null) return icon;
        }
        return null;
    }

    private static McIcon fromModel(String modelId, List<Integer> tints) {
        McModels.Resolved model = modelId == null ? null : McModels.resolve(modelId);
        if (model == null) return null;
        if (model.generated()) return flat(model, tints);
        if (!model.elements().isEmpty()) {
            if (isPlainCube(model)) {
                return new McIcon.Block(texture(model.sprite("up")), texture(model.sprite("east")),
                        texture(model.sprite("north")), 0f);
            }
            return new McIcon.Model(model.id().toString(), tints);
        }
        Identifier fallback = model.sprite("layer0") != null ? model.sprite("layer0") : model.sprite("particle");
        return fallback == null ? null : new McIcon.Sprite(texture(fallback));
    }

    private static boolean isPlainCube(McModels.Resolved model) {
        return CUBE.equals(model.elementsFrom()) && BLOCK_GUI.equals(model.gui()) && !model.frontLight()
                && model.sprite("up") != null && model.sprite("east") != null && model.sprite("north") != null;
    }

    private static McIcon flat(McModels.Resolved model, List<Integer> tints) {
        List<McIcon.Sprite> layers = new ArrayList<>();
        for (int layer = 0; ; layer++) {
            Identifier sprite = model.sprite("layer" + layer);
            if (sprite == null) break;
            layers.add(new McIcon.Sprite(texture(sprite), layer < tints.size() ? tints.get(layer) : McIcon.UNTINTED));
        }
        if (layers.isEmpty()) return null;
        return layers.size() == 1 ? layers.getFirst() : new McIcon.Layered(layers);
    }

    private static McIcon fromSpecial(JsonObject node) {
        JsonObject model = object(node, "model");
        if (model == null) return null;
        return switch (type(model)) {
            case "player_head" -> new McIcon.Head(STEVE_SKIN);
            case "chest" -> new McIcon.Special(McIcon.Special.Kind.CHEST, entityTexture(model, "entity/chest/", "normal"));
            case "bed" -> new McIcon.Special(McIcon.Special.Kind.BED, entityTexture(model, "entity/bed/", "red"));
            default -> null;
        };
    }

    private static String entityTexture(JsonObject model, String folder, String fallback) {
        String texture = string(model, "texture");
        Identifier id = Identifier.tryParse(texture == null ? fallback : texture);
        if (id == null) id = Identifier.withDefaultNamespace(fallback);
        return id.getNamespace() + ":textures/" + folder + id.getPath() + ".png";
    }

    // vanilla only reads live item state for these; without an item stack every source shows its default colour
    private static List<Integer> tints(JsonObject node) {
        JsonArray array = node.getAsJsonArray("tints");
        if (array == null) return List.of();
        List<Integer> tints = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            JsonObject tint = element.getAsJsonObject();
            int argb = switch (type(tint)) {
                case "constant" -> intValue(tint, "value", McIcon.UNTINTED);
                case "grass" -> GRASS_TINT;
                default -> intValue(tint, "default", McIcon.UNTINTED);
            };
            tints.add(0xFF000000 | argb);
        }
        return tints;
    }

    private static String type(JsonObject node) {
        String type = string(node, "type");
        return type == null ? "" : path(type);
    }

    private static String path(String id) {
        if (id == null) return null;
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }

    private static String string(JsonObject node, String key) {
        JsonElement value = node.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static JsonObject object(JsonObject node, String key) {
        JsonElement value = node.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static int intValue(JsonObject node, String key, int fallback) {
        JsonElement value = node.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsInt() : fallback;
    }
}
