package dev.aether.config;

import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StriderConfigMigrationTest {
    @BeforeAll
    static void configureLoader() throws Exception {
        var loader = FabricLoader.getInstance();
        var configDir = loader.getClass().getDeclaredField("configDir");
        configDir.setAccessible(true);
        if (configDir.get(loader) == null) configDir.set(loader, Files.createTempDirectory("aether-strider-migration-test"));
    }

    @Test
    void aConfigFromBeforeTheRedesignTurnsRoutesOffAndLowersThePool() throws Exception {
        String saved = Config.toJsonString();
        try {
            Path file = Files.createTempFile("aether-old-strider", ".json");
            Files.writeString(file, """
                    {"striderFishingRandomLook": true, "striderFishingBlockShuffle": true,
                     "striderFishingRestartRoute": "Default_Strider", "striderFishingSoulWhipCount": 10}
                    """);

            assertTrue(AetherConfig.loadFrom(file.toFile()));

            assertEquals("", AetherConfig.STRIDER_FISHING_RESTART_ROUTE.get());
            assertEquals(8, AetherConfig.STRIDER_FISHING_SOUL_WHIP_COUNT.get());
            String written = Files.readString(AetherConfig.getConfigFile().toPath());
            assertFalse(JsonParser.parseString(written).getAsJsonObject().has("striderFishingRandomLook"));
        } finally {
            assertTrue(Config.loadFromJson(saved));
        }
    }

    @Test
    void anImportedOldConfigIsMigratedToo() {
        String saved = Config.toJsonString();
        try {
            assertTrue(AetherConfig.importFromJson("""
                    {"striderFishingRandomLook": false, "striderFishingRestartRoute": "default_strider",
                     "striderFishingSoulWhipCount": 9}
                    """));

            assertEquals("", AetherConfig.STRIDER_FISHING_RESTART_ROUTE.get());
            assertEquals(8, AetherConfig.STRIDER_FISHING_SOUL_WHIP_COUNT.get());
        } finally {
            assertTrue(Config.loadFromJson(saved));
        }
    }

    @Test
    void anOldConfigKeepsAnOwnRouteAndASmallPool() {
        String saved = Config.toJsonString();
        try {
            assertTrue(AetherConfig.importFromJson("""
                    {"striderFishingRandomLook": true, "striderFishingRestartRoute": "my_pit",
                     "striderFishingSoulWhipCount": 6}
                    """));

            assertEquals("my_pit", AetherConfig.STRIDER_FISHING_RESTART_ROUTE.get());
            assertEquals(6, AetherConfig.STRIDER_FISHING_SOUL_WHIP_COUNT.get());
        } finally {
            assertTrue(Config.loadFromJson(saved));
        }
    }

    @Test
    void aConfigSavedAfterTheRedesignIsLeftAlone() {
        String saved = Config.toJsonString();
        try {
            assertTrue(AetherConfig.importFromJson("""
                    {"striderFishingRestartRoute": "default_strider", "striderFishingSoulWhipCount": 10}
                    """));

            assertEquals("default_strider", AetherConfig.STRIDER_FISHING_RESTART_ROUTE.get());
            assertEquals(10, AetherConfig.STRIDER_FISHING_SOUL_WHIP_COUNT.get());
        } finally {
            assertTrue(Config.loadFromJson(saved));
        }
    }
}
