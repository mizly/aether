package dev.aether.modules.performance

import dev.aether.config.AetherConfig
import net.minecraft.client.Minecraft

class MuteManager private constructor() {
    companion object {
        private var muted = false

        @JvmStatic
        fun start(mc: Minecraft) {
            if (!AetherConfig.MUTE_GAME.get()) return
            muted = true
        }

        @JvmStatic
        fun stop(mc: Minecraft) {
            muted = false
        }

        @JvmStatic
        fun isMuted(): Boolean {
            return muted
        }

        // 0.0 fully mutes
        @JvmStatic
        fun getVolume(): Float {
            return AetherConfig.MUTE_GAME_VOLUME.get()
        }
    }
}
