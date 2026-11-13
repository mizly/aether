package dev.aether.modules.visuals

import dev.aether.config.AetherConfig

class Skybox private constructor() {
    companion object {
        @JvmField val PRESETS: List<String> = listOf("Cirrus", "Golden Hour", "Twilight", "Aurora", "Starfield")
        @JvmStatic fun isEnabled(): Boolean = AetherConfig.SKYBOX_ENABLED.get()
        @JvmStatic fun preset(): Int = Math.max(0, Math.min(AetherConfig.SKYBOX_PRESET.get(), PRESETS.size - 1))
    }
}
