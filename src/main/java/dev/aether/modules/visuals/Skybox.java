package dev.aether.modules.visuals;

import dev.aether.config.AetherConfig;

import java.util.List;

public final class Skybox {
    public static final List<String> PRESETS = List.of("Cirrus", "Golden Hour", "Twilight", "Aurora", "Starfield");

    private Skybox() {}

    public static boolean isEnabled() {
        return AetherConfig.SKYBOX_ENABLED.get();
    }

    public static int preset() {
        return Math.clamp(AetherConfig.SKYBOX_PRESET.get(), 0, PRESETS.size() - 1);
    }
}
