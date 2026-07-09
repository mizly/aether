package dev.aether.modules.visuals;

import dev.aether.config.AetherConfig;

public final class StreamerModeManager {
    private StreamerModeManager() {
    }

    public static boolean isEnabled() {
        return AetherConfig.STREAMER_MODE.get();
    }

    public static void setEnabled(boolean enabled) {
        AetherConfig.STREAMER_MODE.set(enabled);
        if (enabled) {
            FreecamManager.setEnabled(false);
            PipManager.setEnabled(false);
            UngrabMouseManager.setEnabled(false);
        }
        AetherConfig.save();
    }
}
