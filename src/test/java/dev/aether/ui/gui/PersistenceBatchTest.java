package dev.aether.ui.gui;

import dev.aether.config.AetherConfig;
import dev.aether.ui.theme.Theme;
import net.fabricmc.loader.api.FabricLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PersistenceBatchTest {
    private final PersistenceBatch batch = new PersistenceBatch();
    private Path configFile;
    private Path themeFile;
    private boolean savedAutoUpdate;
    private float savedAnimTime;

    @BeforeAll
    static void configDir() {
        TestConfigDir.ensure();
    }

    @BeforeEach
    void remember() {
        configFile = AetherConfig.getConfigFile().toPath();
        themeFile = FabricLoader.getInstance().getConfigDir().resolve("aether_theme.json");
        savedAutoUpdate = AetherConfig.AUTO_UPDATE.get();
        savedAnimTime = Theme.ANIM_TIME_MS;
    }

    @AfterEach
    void restore() {
        batch.end();
        AetherConfig.AUTO_UPDATE.set(savedAutoUpdate);
        AetherConfig.save();
        Theme.ANIM_TIME_MS = savedAnimTime;
        Theme.saveTheme();
    }

    @Test
    void configWritesWaitForTheEndOfTheBatch() throws IOException {
        Files.deleteIfExists(configFile);
        batch.begin();
        AetherConfig.AUTO_UPDATE.set(!savedAutoUpdate);
        AetherConfig.save();
        AetherConfig.AUTO_UPDATE.set(savedAutoUpdate);
        AetherConfig.AUTO_UPDATE.set(!savedAutoUpdate);
        AetherConfig.save();
        assertFalse(Files.exists(configFile));
        batch.end();
        assertTrue(Files.readString(configFile).contains("\"autoUpdate\": " + !savedAutoUpdate));
    }

    @Test
    void themeSavesWaitForTheEndOfTheBatch() throws IOException {
        Files.deleteIfExists(themeFile);
        batch.begin();
        Theme.ANIM_TIME_MS = 432f;
        Theme.saveTheme();
        assertFalse(Files.exists(themeFile));
        batch.end();
        assertTrue(Files.readString(themeFile).contains("432"));
    }

    @Test
    void withoutABatchWritesHappenAtOnce() throws IOException {
        Files.deleteIfExists(configFile);
        Files.deleteIfExists(themeFile);
        AetherConfig.AUTO_UPDATE.set(!savedAutoUpdate);
        AetherConfig.save();
        Theme.saveTheme();
        assertTrue(Files.exists(configFile));
        assertTrue(Files.exists(themeFile));
    }

    @Test
    void beginAndEndAreIdempotent() throws IOException {
        batch.begin();
        batch.begin();
        assertTrue(batch.isOpen());
        batch.end();
        assertFalse(batch.isOpen());
        batch.end();
        Files.deleteIfExists(themeFile);
        Theme.saveTheme();
        assertTrue(Files.exists(themeFile), "a second end must not leave saves batched");
    }

    @Test
    void anUnchangedBatchWritesNothing() throws IOException {
        AetherConfig.flush();
        Files.deleteIfExists(themeFile);
        batch.begin();
        batch.end();
        assertFalse(Files.exists(themeFile));
    }
}
