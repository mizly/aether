package dev.aether.util

import dev.aether.macro.MacroState
import java.util.regex.Pattern

class SkyblockLocation private constructor() {
    companion object {
        private val SKYBLOCK_TITLE = Pattern.compile("(?i)^SKYBLOCK(?:\\s|$)")

        @JvmStatic
        fun resolve(sidebarTitle: String?, hasLobbyItems: Boolean, areaLine: String?): MacroState.Location {
            if (sidebarTitle == null) return MacroState.Location.LIMBO
            val title = TablistUtils.stripColors(sidebarTitle).replace('\u00A0', ' ').trim()
            if (hasLobbyItems || !SKYBLOCK_TITLE.matcher(title).find()) return MacroState.Location.LOBBY
            val area = TablistUtils.stripColors(areaLine).replace('\u00A0', ' ').trim()
            if (area.equals("Area: Garden", ignoreCase = true)) return MacroState.Location.GARDEN
            if (area.equals("Area: Crystal Hollows", ignoreCase = true)) return MacroState.Location.CRYSTAL_HOLLOWS
            return MacroState.Location.HUB
        }
    }
}
