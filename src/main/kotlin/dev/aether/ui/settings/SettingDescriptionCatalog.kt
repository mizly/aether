package dev.aether.ui.settings

import dev.aether.util.AetherLang

internal class SettingDescriptionCatalog private constructor() {
    companion object {
        private val EXPLICIT: Map<String, String> = mapOf(
            "Pest Loadout Swap Time (~170s for eq swap, ~5s for no eq swap)" to "Pest cooldown time left on the scoreboard before Pest Destroyer swaps to the next loadout. This is NOT the same as cooldown time, it's cooldown time REMAINING. PLEASE DO NOT PUT WRONG VALUES AND COMPLAIN.\n Timing is same for Finnegan.",
            "Pest Threshold" to "Number of pests required before Pest Destroyer starts automatically.",
            "Walk Mode" to "Walk instead of fly to pests. Will likely not work unless you have a glass roof and AOTV to roof.",
            "Leave One Pest Alive" to "Preserves one pest on selected plots (Earthworm Shard).",
            "Sunset Pests" to "Swaps to night for farming, and day for killing pests. Useful for the Sunset enchantment, or spawning Fireflies.",
            "Change Time Directly Before Pest Spawn" to "Swaps to night only for spawning pests. Useful for spawning Fireflies, while still keeping daytime for extra Overbloom on crops.",
            "AOTV Between Distant Pests" to "Uses AOTV between distant pests.",
            "Etherwarp Directly Near Pests" to "Etherwarp to land on a safe block beside a distant pest.",
            "Etherwarp Minimum Distance (Blocks)" to "Minimum distance before Pest Destroyer considers a direct etherwarp to the target pest.",
            "Next Pest Turn Speed" to "Controls how quickly the camera turns when handing off to the next pest or aiming etherwarp.",
            "Smart AOTV Routing" to "Chooses AOTV using horizontal and vertical travel cost, line of sight, and the configured start and stop distances.",
            "AOTV Start Distance (Blocks)" to "Minimum travel distance at which Smart AOTV Routing begins considering AOTV.",
            "AOTV Stop Distance (Blocks)" to "Distance from the target where Smart AOTV Routing stops chaining teleports.",
            "Confirm AOTV Between Pests" to "Checks whether player moved before clicking AOTV again.",
            "Optimized Route ESP" to "Draws the planned Pest Destroyer route from the current target through nearby pests.",
            "Optimized Route Color" to "Sets the color used for the optimized Pest Destroyer route.",
            "Highlight" to "Draws a highlight around detected pests.",
            "Tracer" to "Draws a line toward detected pests.",
            "UI Scale" to "Changes the overall size of the Aether menu.",
            "Text Scale" to "Changes text size without changing the full menu scale.",
            "Limit FPS" to "Caps Minecraft's frame rate while macro is active.",
            "Auto Reconnect" to "Automatically attempts to reconnect after disconnects.",
            "Edit HUD Layout" to "Opens the HUD editor so Aether overlays can be moved and resized.",
            "Language" to "Chooses the language used by the Aether interface.",
            "Save Preset" to "Saves the current values as a reusable preset.",
            "Reset Session" to "Clears the current session statistics.",
            "Webhook URL" to "Sets the notification webhook destination.",
            "Bot Token" to "Sets the integration token. Keep this value private.",
            "Pathfinder Max Jump Height" to "Sets the maximum jump height for pathfinder.",
            "Warp Grace Period" to "Allows position checks to settle briefly after a warp."
        )

        @JvmStatic fun describe(setting: Setting?): String {
            if (setting == null) return ""
            val raw = setting.getRawName() ?: setting.getName()
            val explicit = EXPLICIT[raw]
            return AetherLang.localize(explicit ?: fallback(setting.getType(), raw))
        }

        private fun fallback(type: SettingType, raw: String?): String {
            val subject = if (raw == null || raw.isBlank()) "this setting" else raw
            return when (type) {
                SettingType.TOGGLE -> "Turns " + subject + " on or off."
                SettingType.SLIDER -> "Adjusts " + subject + "."
                SettingType.RANGE_SLIDER -> "Sets the minimum and maximum values for " + subject + "."
                SettingType.DROPDOWN -> "Chooses the mode used for " + subject + "."
                SettingType.DROPDOWN_LIST -> "Sets the ordered choices used for " + subject + "."
                SettingType.MULTI_DROPDOWN -> "Selects the entries included in " + subject + "."
                SettingType.LIST -> "Manages the saved entries used by " + subject + "."
                SettingType.TEXT -> "Sets the text used for " + subject + "."
                SettingType.COLOR -> "Sets the color used for " + subject + "."
                SettingType.POSITION -> "Sets the in-world position used for " + subject + "."
                SettingType.KEYBIND -> "Changes the keyboard key used for " + subject + "."
                SettingType.ACTION -> "Runs the “" + subject + "” action immediately."
                SettingType.INFO -> "Displays current information for " + subject + "."
                SettingType.SECTION -> "Groups settings related to " + subject + "."
            }
        }
    }
}
