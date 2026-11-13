package dev.aether.modules.visuals

import dev.aether.config.AetherConfig

class StreamerModeManager private constructor() {
    companion object {
        @JvmStatic fun isEnabled(): Boolean = AetherConfig.STREAMER_MODE.get()
        @JvmStatic fun setEnabled(enabled: Boolean) {
            AetherConfig.STREAMER_MODE.set(enabled)
            if (enabled) {
                FreecamManager.setEnabled(false)
                UngrabMouseManager.setEnabled(false)
            }
            AetherConfig.save()
        }
    }
}
