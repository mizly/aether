package dev.aether.config;

import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigProfileManagerTest {
    @BeforeAll
    static void configureLoader() throws Exception {
        var loader = FabricLoader.getInstance();
        var configDir = loader.getClass().getDeclaredField("configDir");
        configDir.setAccessible(true);
        if (configDir.get(loader) == null) configDir.set(loader, Files.createTempDirectory("aether-profile-test"));
    }

    @Test
    void loadingAnOldProfileLeavesTheOutgoingProfileUntouched() throws Exception {
        String saved = Config.toJsonString();
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("aether").resolve("profiles");
        try {
            AetherConfig.FISHING_MACRO_ROD_SLOT.set(7);
            ConfigProfileManager.save("clobber_a");
            Files.writeString(dir.resolve("clobber_b.json"),
                    "{\"striderFishingRandomLook\": true, \"fishingMacroRodSlot\": 3}");

            assertTrue(ConfigProfileManager.load("clobber_b"));

            assertEquals(3, AetherConfig.FISHING_MACRO_ROD_SLOT.get());
            String a = Files.readString(dir.resolve("clobber_a.json"));
            assertEquals(7, JsonParser.parseString(a).getAsJsonObject().get("fishingMacroRodSlot").getAsInt());
        } finally {
            ConfigProfileManager.delete("clobber_a");
            ConfigProfileManager.delete("clobber_b");
            assertTrue(Config.loadFromJson(saved));
        }
    }
}
