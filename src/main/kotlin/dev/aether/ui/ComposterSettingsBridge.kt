package dev.aether.ui

import dev.aether.config.AetherConfig

class ComposterSettingsBridge private constructor() {
    companion object {
        private const val SOURCE_SACKS = "SACKS"
        private const val SOURCE_BAZAAR = "BAZAAR"

        @JvmStatic
        fun setSourceModeIndex(index: Int) {
            AetherConfig.AUTO_COMPOSTER_SOURCE_MODE.set(if (index == 1) SOURCE_BAZAAR else SOURCE_SACKS)
            AetherConfig.save()
        }
    }
}
