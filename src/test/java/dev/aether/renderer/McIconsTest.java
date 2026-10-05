package dev.aether.renderer;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class McIconsTest {

    private static final String ITEM = "minecraft:textures/item/";
    private static final String BLOCK = "minecraft:textures/block/";

    @Test
    void resolvesGeneratedItemsToFlatSprites() {
        assertEquals(new McIcon.Sprite(ITEM + "wheat.png"), McIcons.of("minecraft:wheat"));
        assertEquals(McIcons.of("minecraft:wheat"), McIcons.of("  Wheat "));
        assertEquals(new McIcon.Sprite(ITEM + "diamond_hoe.png"), McIcons.of("diamond_hoe"));
        assertEquals(new McIcon.Sprite(BLOCK + "red_mushroom.png"), McIcons.of("red_mushroom"));
        assertEquals(new McIcon.Sprite(BLOCK + "sunflower_front.png"), McIcons.of("sunflower"));
    }

    @Test
    void resolvesCubeTemplatesToIsoBlocks() {
        assertEquals(new McIcon.Block(BLOCK + "pumpkin_top.png", BLOCK + "pumpkin_side.png", BLOCK + "pumpkin_side.png", 0f),
                McIcons.of("minecraft:pumpkin"));
        assertEquals(new McIcon.Block(BLOCK + "oak_planks.png", BLOCK + "oak_planks.png", BLOCK + "oak_planks.png", 0f),
                McIcons.of("oak_planks"));
        assertEquals(new McIcon.Block(BLOCK + "hay_block_top.png", BLOCK + "hay_block_side.png", BLOCK + "hay_block_side.png", 0f),
                McIcons.of("hay_block"));
        // orientable: the front faces north, which is the right-hand face in the inventory
        assertEquals(new McIcon.Block(BLOCK + "crafting_table_top.png", BLOCK + "crafting_table_side.png",
                BLOCK + "crafting_table_front.png", 0f), McIcons.of("crafting_table"));
    }

    @Test
    void cactusKeepsItsInsetSides() {
        McIcon.Block cactus = assertInstanceOf(McIcon.Block.class, McIcons.of("minecraft:cactus"));
        assertEquals(BLOCK + "cactus_top.png", cactus.top());
        assertEquals(BLOCK + "cactus_side.png", cactus.left());
        assertEquals(BLOCK + "cactus_side.png", cactus.right());
        assertEquals(1f / 16f, cactus.sideInset());
    }

    @Test
    void curatedItemsPinTheirIdleFrames() {
        assertEquals(new McIcon.Sprite(ITEM + "compass_16.png"), McIcons.of("compass"));
        assertEquals(new McIcon.Sprite(ITEM + "clock_00.png"), McIcons.of("clock"));
        assertEquals(new McIcon.Sprite(ITEM + "recovery_compass_16.png"), McIcons.of("recovery_compass"));
    }

    @Test
    void glassPanesUseTheirGlassTextureFlat() {
        for (String colour : List.of("lime", "orange", "red", "gray", "black", "white", "light_gray")) {
            assertEquals(new McIcon.Sprite(BLOCK + colour + "_stained_glass.png"),
                    McIcons.of(colour + "_stained_glass_pane"), colour);
        }
    }

    @Test
    void specialModelsGetTheirEntityStandIns() {
        assertEquals(new McIcon.Head(McIcons.STEVE_SKIN), McIcons.of("player_head"));
        assertEquals(new McIcon.Special(McIcon.Special.Kind.CHEST, "minecraft:textures/entity/chest/normal.png"),
                McIcons.of("chest"));
        assertEquals(new McIcon.Special(McIcon.Special.Kind.CHEST, "minecraft:textures/entity/chest/trapped.png"),
                McIcons.of("trapped_chest"));
        assertEquals(new McIcon.Special(McIcon.Special.Kind.BED, "minecraft:textures/entity/bed/red.png"),
                McIcons.of("red_bed"));
    }

    @Test
    void elementModelsKeepTheirModelAndTints() {
        assertEquals(new McIcon.Model("minecraft:block/grass_block", List.of(McIcons.GRASS_TINT)), McIcons.of("grass_block"));
        assertEquals(new McIcon.Model("minecraft:block/composter", List.of()), McIcons.of("composter"));
        assertEquals(new McIcon.Model("minecraft:block/daylight_detector", List.of()), McIcons.of("daylight_detector"));
        assertInstanceOf(McIcon.Model.class, McIcons.of("oak_button"));
    }

    @Test
    void oakButtonIsASmallCentredPlankBoxLikeTheInventoryDrawsIt() {
        List<IsoBlockPainter.Face> faces = IsoBlockPainter.faces(McIcons.drawable(McIcons.OAK_BUTTON));
        assertFalse(faces.isEmpty());
        float minX = 1f, minY = 1f, maxX = 0f, maxY = 0f;
        for (IsoBlockPainter.Face face : faces) {
            assertEquals(BLOCK + "oak_planks.png", face.texture());
            for (float[] uv : new float[][]{{face.u0(), face.v0()}, {face.u1(), face.v0()}, {face.u0(), face.v1()}, {face.u1(), face.v1()}}) {
                float x = face.a() * uv[0] + face.c() * uv[1] + face.e();
                float y = face.b() * uv[0] + face.d() * uv[1] + face.f();
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }
        // a 6x4x4 element under block.json's 30/225 rotation at 0.625 scale: (6 + 4) * cos 45 * 0.625 / 16 wide
        assertEquals(10f * (float) Math.cos(Math.PI / 4) * 0.625f / 16f, maxX - minX, 0.01f);
        assertEquals(0.5f, (minX + maxX) / 2f, 0.01f);
        assertEquals(0.5f, (minY + maxY) / 2f, 0.02f);
    }

    @Test
    void barnSkinsAreIsoBlocks() {
        for (McIcon skin : List.of(McIcons.DARK_OAK_PLANKS, McIcons.SPRUCE_PLANKS, McIcons.QUARTZ_BLOCK,
                McIcons.SPRUCE_LOG, McIcons.RED_SANDSTONE, McIcons.LIGHT_BLUE_TERRACOTTA)) {
            assertInstanceOf(McIcon.Block.class, McIcons.drawable(skin), skin.toString());
        }
        assertEquals(new McIcon.Block(BLOCK + "spruce_log_top.png", BLOCK + "spruce_log.png", BLOCK + "spruce_log.png", 0f),
                McIcons.drawable(McIcons.SPRUCE_LOG));
    }

    @Test
    void layeredItemsCarryTheirDefaultTints() {
        assertEquals(new McIcon.Layered(List.of(
                new McIcon.Sprite(ITEM + "filled_map.png", 0xFFFFFFFF),
                new McIcon.Sprite(ITEM + "filled_map_markings.png", 0xFF46402E))), McIcons.of("filled_map"));
    }

    @Test
    void displayContextSelectsUseTheirGuiModel() {
        assertEquals(new McIcon.Sprite(ITEM + "spyglass.png"), McIcons.of("spyglass"));
        assertEquals(new McIcon.Sprite(ITEM + "trident.png"), McIcons.of("trident"));
    }

    @Test
    void unknownIdsResolveToNull() {
        assertNull(McIcons.of("minecraft:not_an_item"));
        assertNull(McIcons.of(""));
        assertNull(McIcons.of(null));
    }

    @Test
    void everyConstantResolvesToSomethingDrawable() throws Exception {
        for (Map.Entry<String, McIcon> constant : constants()) {
            McIcon icon = McIcons.drawable(constant.getValue());
            assertNotNull(icon, constant.getKey());
            assertFalse(IsoBlockPainter.faces(icon).isEmpty(), constant.getKey() + " has visible faces");
        }
    }

    @Test
    void nearlyEveryVanillaItemResolvesAndProjects() throws Exception {
        List<String> unresolved = new ArrayList<>();
        int total = 0;
        URI jar = McIconsTest.class.getResource("/assets/minecraft/items/wheat.json").toURI();
        try (FileSystem zip = FileSystems.newFileSystem(jar, Map.of());
             Stream<Path> items = Files.list(zip.getPath("/assets/minecraft/items"))) {
            for (Path file : items.toList()) {
                String id = file.getFileName().toString().replace(".json", "");
                total++;
                McIcon icon = McIcons.of(id);
                if (icon == null || IsoBlockPainter.faces(icon).isEmpty()) unresolved.add(id);
            }
        }
        System.out.println("[McIconsTest] " + (total - unresolved.size()) + "/" + total + " vanilla items draw; not: " + unresolved);
        assertTrue(total > 1400, "found the vanilla item definitions");
        assertTrue(unresolved.size() < total / 20, "unresolved: " + unresolved);
    }

    static List<Map.Entry<String, McIcon>> constants() throws IllegalAccessException {
        List<Map.Entry<String, McIcon>> constants = new ArrayList<>();
        for (Field field : McIcons.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == McIcon.class) {
                constants.add(Map.entry(field.getName(), (McIcon) field.get(null)));
            }
        }
        return constants;
    }
}
