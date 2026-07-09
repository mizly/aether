package dev.aether.ui;

import dev.aether.config.AetherConfig;

public final class ComposterSettingsBridge {
    private static final String SOURCE_SACKS = "SACKS";
    private static final String SOURCE_BAZAAR = "BAZAAR";

    private ComposterSettingsBridge() {
    }

    public static void setSourceModeIndex(int index) {
        AetherConfig.AUTO_COMPOSTER_SOURCE_MODE.set(index == 1 ? SOURCE_BAZAAR : SOURCE_SACKS);
        AetherConfig.save();
    }
}
