package dev.aether.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class HumanizationPresetManagerTest {
    @BeforeAll
    static void configureLoader() throws Exception {
        var loader = FabricLoader.getInstance();
        var configDir = loader.getClass().getDeclaredField("configDir");
        configDir.setAccessible(true);
        if (configDir.get(loader) == null) configDir.set(loader, Files.createTempDirectory("aether-preset-test"));
    }

    @Test
    void efficientAppliesEveryBundledSettingWithoutClampingOrChangingUnrelatedOptions() throws Exception {
        AetherConfig.HUMANIZATION_PRESET.get();
        String saved = Config.toJsonString();
        try {
            AetherConfig.PEST_HUNTING.set(true);
            AetherConfig.SHOW_PEST_TARGET_HUD.set(false);
            AetherConfig.PEST_MAX_TURN_SPEED.set(800f);
            AetherConfig.PEST_HUNTING_MAX_TURN_SPEED.set(800f);
            HumanizationPresetManager.applyPresetByIndex(2);
            JsonObject expected;
            try (var input = getClass().getResourceAsStream("/assets/aether/humanization-presets/efficient.json")) {
                assertNotNull(input);
                expected = JsonParser.parseString(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            }
            JsonObject actual = JsonParser.parseString(Config.toJsonString()).getAsJsonObject();
            expected.entrySet().forEach(entry -> assertEquals(entry.getValue(), actual.get(entry.getKey()), entry.getKey()));
            assertEquals("EFFICIENT", AetherConfig.HUMANIZATION_PRESET.get());
            assertTrue(AetherConfig.PEST_HUNTING.get());
            assertFalse(AetherConfig.SHOW_PEST_TARGET_HUD.get());
        } finally {
            assertTrue(Config.loadFromJson(saved));
        }
    }

    @Test
    void normalPresetMatchesTheDefaults() throws Exception {
        AetherConfig.HUMANIZATION_PRESET.get();
        String saved = Config.toJsonString();
        try {
            Config.reset();
            JsonObject defaults = JsonParser.parseString(Config.toJsonString()).getAsJsonObject();
            bundledPreset("normal").entrySet().forEach(entry ->
                    assertEquals(entry.getValue(), defaults.get(entry.getKey()), entry.getKey()));
        } finally {
            assertTrue(Config.loadFromJson(saved));
        }
    }

    @Test
    void safeAppliesEveryBundledSettingWithoutClamping() throws Exception {
        AetherConfig.HUMANIZATION_PRESET.get();
        String saved = Config.toJsonString();
        try {
            AetherConfig.PEST_HUNTING.set(true);
            AetherConfig.SHOW_PEST_TARGET_HUD.set(false);
            HumanizationPresetManager.applyPresetByIndex(0);
            JsonObject actual = JsonParser.parseString(Config.toJsonString()).getAsJsonObject();
            bundledPreset("safe").entrySet().forEach(entry ->
                    assertEquals(entry.getValue(), actual.get(entry.getKey()), entry.getKey()));
            assertEquals("SAFE", AetherConfig.HUMANIZATION_PRESET.get());
            assertTrue(AetherConfig.PEST_HUNTING.get());
            assertFalse(AetherConfig.SHOW_PEST_TARGET_HUD.get());
        } finally {
            assertTrue(Config.loadFromJson(saved));
        }
    }

    private JsonObject bundledPreset(String id) throws Exception {
        try (var input = getClass().getResourceAsStream("/assets/aether/humanization-presets/" + id + ".json")) {
            assertNotNull(input);
            return JsonParser.parseString(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }
}
