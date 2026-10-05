package dev.aether.ui.gui;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;

// Theme, AetherConfig and the profile managers read the config dir in static initialisers, which the
// uninitialised test loader leaves null; this points it at a temp dir once per test jvm
public final class TestConfigDir {
    private TestConfigDir() {
    }

    public static void ensure() {
        try {
            FabricLoader loader = FabricLoader.getInstance();
            Field configDir = loader.getClass().getDeclaredField("configDir");
            configDir.setAccessible(true);
            if (configDir.get(loader) == null) {
                configDir.set(loader, Files.createTempDirectory("aether-gui-test"));
            }
        } catch (ReflectiveOperationException | IOException e) {
            throw new IllegalStateException("could not point the test loader at a config dir", e);
        }
    }
}
