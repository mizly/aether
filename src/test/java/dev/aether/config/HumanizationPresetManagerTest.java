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
    void blatantAppliesEveryBundledSettingWithoutClampingOrChangingUnrelatedOptions() throws Exception {
        AetherConfig.HUMANIZATION_PRESET.get();
        String saved = Config.toJsonString();
        try {
            AetherConfig.PEST_HUNTING.set(true);
            AetherConfig.SHOW_PEST_TARGET_HUD.set(false);
            AetherConfig.PEST_MAX_TURN_SPEED.set(800f);
            AetherConfig.PEST_HUNTING_MAX_TURN_SPEED.set(800f);
            HumanizationPresetManager.applyPresetByIndex(2);
            JsonObject expected;
            try (var input = getClass().getResourceAsStream("/assets/aether/humanization-presets/blatant.json")) {
                assertNotNull(input);
                expected = JsonParser.parseString(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            }
            JsonObject actual = JsonParser.parseString(Config.toJsonString()).getAsJsonObject();
            expected.entrySet().forEach(entry -> assertEquals(entry.getValue(), actual.get(entry.getKey()), entry.getKey()));
            assertEquals("BLATANT", AetherConfig.HUMANIZATION_PRESET.get());
            assertTrue(AetherConfig.PEST_HUNTING.get());
            assertFalse(AetherConfig.SHOW_PEST_TARGET_HUD.get());
        } finally {
            assertTrue(Config.loadFromJson(saved));
        }
    }

    @Test
    void legitPresetMatchesTheDefaults() throws Exception {
        AetherConfig.HUMANIZATION_PRESET.get();
        String saved = Config.toJsonString();
        try {
            Config.reset();
            JsonObject defaults = JsonParser.parseString(Config.toJsonString()).getAsJsonObject();
            bundledPreset("legit").entrySet().forEach(entry ->
                    assertEquals(entry.getValue(), defaults.get(entry.getKey()), entry.getKey()));
        } finally {
            assertTrue(Config.loadFromJson(saved));
        }
    }

    @Test
    void extraLegitAppliesEveryBundledSettingWithoutClamping() throws Exception {
        AetherConfig.HUMANIZATION_PRESET.get();
        String saved = Config.toJsonString();
        try {
            AetherConfig.PEST_HUNTING.set(true);
            AetherConfig.SHOW_PEST_TARGET_HUD.set(false);
            HumanizationPresetManager.applyPresetByIndex(0);
            JsonObject actual = JsonParser.parseString(Config.toJsonString()).getAsJsonObject();
            bundledPreset("extra_legit").entrySet().forEach(entry ->
                    assertEquals(entry.getValue(), actual.get(entry.getKey()), entry.getKey()));
            assertEquals("EXTRA_LEGIT", AetherConfig.HUMANIZATION_PRESET.get());
            assertTrue(AetherConfig.PEST_HUNTING.get());
            assertFalse(AetherConfig.SHOW_PEST_TARGET_HUD.get());
        } finally {
            assertTrue(Config.loadFromJson(saved));
        }
    }

    @Test
    void presetsSavedUnderTheOldNamesStillShowTheMatchingPreset() {
        String saved = AetherConfig.HUMANIZATION_PRESET.get();
        try {
            String[][] cases = {{"SAFE", "0"}, {"NORMAL", "1"}, {"EFFICIENT", "1"}, {"EXTRA_LEGIT", "0"},
                    {"LEGIT", "1"}, {"BLATANT", "2"}, {"something else", "1"}};
            for (String[] c : cases) {
                AetherConfig.HUMANIZATION_PRESET.set(c[0]);
                assertEquals(Integer.parseInt(c[1]), HumanizationPresetManager.getSelectedPresetIndex(), c[0]);
            }
        } finally {
            AetherConfig.HUMANIZATION_PRESET.set(saved);
        }
    }

    @Test
    void everyPresetSetsEveryPresetSetting() throws Exception {
        java.util.Set<String> legit = bundledPreset("legit").keySet();
        assertEquals(legit, bundledPreset("extra_legit").keySet());
        assertEquals(legit, bundledPreset("blatant").keySet());
    }

    private JsonObject bundledPreset(String id) throws Exception {
        try (var input = getClass().getResourceAsStream("/assets/aether/humanization-presets/" + id + ".json")) {
            assertNotNull(input);
            return JsonParser.parseString(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }
}
