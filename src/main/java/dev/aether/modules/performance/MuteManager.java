package dev.aether.modules.performance;

import dev.aether.config.AetherConfig;
import net.minecraft.client.Minecraft;

public class MuteManager {
    private static boolean muted = false;

    public static void start(Minecraft mc) {
        if (!AetherConfig.MUTE_GAME.get()) return;
        muted = true;
    }

    public static void stop(Minecraft mc) {
        muted = false;
    }

    public static boolean isMuted() {
        return muted;
    }

    // 0.0 fully mutes
    public static float getVolume() {
        return AetherConfig.MUTE_GAME_VOLUME.get();
    }
}
