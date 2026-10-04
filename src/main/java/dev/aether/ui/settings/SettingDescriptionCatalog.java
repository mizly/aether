package dev.aether.ui.settings;

import dev.aether.util.AetherLang;

import java.util.Map;

final class SettingDescriptionCatalog {
    private static final Map<String, String> EXPLICIT = Map.ofEntries(
            Map.entry("Pest Loadout Swap Time (~170s for eq swap, ~5s for no eq swap)", "Pest cooldown time left on the scoreboard before Pest Destroyer swaps to the next loadout. This is NOT the same as cooldown time, it's cooldown time REMAINING. PLEASE DO NOT PUT WRONG VALUES AND COMPLAIN.\n Timing is same for Finnegan."),
            Map.entry("Pest Threshold", "Number of pests required before Pest Destroyer starts automatically."),
            Map.entry("Leave One Pest Alive", "Preserves one pest on selected plots (Earthworm Shard)."),
            Map.entry("Sunset Pests", "Swaps to night for farming, and day for killing pests. Useful for the Sunset enchantment, or spawning Fireflies."),
            Map.entry("AOTV Between Distant Pests", "Uses AOTV between distant pests."),
            Map.entry("Etherwarp Directly Near Pests", "Etherwarp to land on a safe block beside a distant pest."),
            Map.entry("Etherwarp Minimum Distance (Blocks)", "Minimum distance before Pest Destroyer considers a direct etherwarp to the target pest."),
            Map.entry("Next Pest Turn Speed", "Peak camera speed when turning onto a new pest or aiming AOTV and etherwarp. With Human Target Switch on, each turn onto a new pest peaks somewhere between 70% and 100% of it."),
            Map.entry("Smart AOTV Routing", "Chooses AOTV using horizontal and vertical travel cost, line of sight, and the configured start and stop distances."),
            Map.entry("AOTV Start Distance (Blocks)", "Minimum travel distance at which Smart AOTV Routing begins considering AOTV."),
            Map.entry("AOTV Stop Distance (Blocks)", "Distance from the target where Smart AOTV Routing stops chaining teleports."),
            Map.entry("Confirm AOTV Between Pests", "Checks whether player moved before clicking AOTV again."),
            Map.entry("Optimized Route ESP", "Draws the planned Pest Destroyer route from the current target through nearby pests."),
            Map.entry("Optimized Route Color", "Sets the color used for the optimized Pest Destroyer route."),
            Map.entry("Highlight", "Draws a highlight around detected pests."),
            Map.entry("Tracer", "Draws a line toward detected pests."),
            Map.entry("UI Scale", "Changes the overall size of the Aether menu."),
            Map.entry("Text Scale", "Changes text size without changing the full menu scale."),
            Map.entry("Limit FPS", "Caps Minecraft's frame rate while macro is active."),
            Map.entry("Auto Reconnect", "Automatically attempts to reconnect after disconnects."),
            Map.entry("Edit HUD Layout", "Opens the HUD editor so Aether overlays can be moved and resized."),
            Map.entry("Language", "Chooses the language used by the Aether interface."),
            Map.entry("Save Preset", "Saves the current values as a reusable preset."),
            Map.entry("Reset Session", "Clears the current session statistics."),
            Map.entry("Webhook URL", "Sets the notification webhook destination."),
            Map.entry("Bot Token", "Sets the integration token. Keep this value private."),
            Map.entry("Pathfinder Max Jump Height", "Sets the maximum jump height for pathfinder."),
            Map.entry("Warp Grace Period", "Allows position checks to settle briefly after a warp."),
            Map.entry("Restart Route", "Opens the routes area, where the warp and walk to the fishing spot are recorded."),
            Map.entry("Time Nearby", "How long a player has to stay within the distance before the failsafe triggers."),
            Map.entry("Teleport Distance", "How far a single jump in position has to be to count as a teleport."),
            Map.entry("Human Target Switch", "Turns onto each new pest the way a player does: a short reaction, a quick flick that can stop short or swing past, then a correction. Off keeps the smooth tracking turn."),
            Map.entry("Pest Reaction Time", "Pause after a pest dies or a new one is picked before the camera starts turning. A quarter shorter when the next pest is already near the crosshair."),
            Map.entry("Pest Overshoot Chance", "Chance that a big turn onto a new pest swings past it before correcting back."),
            Map.entry("Pest Overshoot Min Turn", "Turns of at least this many degrees can overshoot at the full chance. Below that the chance fades out, reaching zero at half this size, where turns only land a little short or long."),
            Map.entry("Pest Overshoot Amount", "How far an overshoot swings past the pest, as a share of the turn. Lower turn speeds swing less far.")

    );

    private SettingDescriptionCatalog() {
    }

    static String describe(Setting setting) {
        if (setting == null) {
            return "";
        }
        String raw = setting.getRawName() == null ? setting.getName() : setting.getRawName();
        String explicit = EXPLICIT.get(raw);
        return AetherLang.localize(explicit != null ? explicit : fallback(setting.getType(), raw));
    }

    private static String fallback(SettingType type, String raw) {
        String subject = raw == null || raw.isBlank() ? "this setting" : raw;
        return switch (type) {
            case TOGGLE -> "Turns " + subject + " on or off.";
            case SLIDER -> "Adjusts " + subject + ".";
            case RANGE_SLIDER -> "Sets the minimum and maximum values for " + subject + ".";
            case DROPDOWN -> "Chooses the mode used for " + subject + ".";
            case DROPDOWN_LIST -> "Sets the ordered choices used for " + subject + ".";
            case MULTI_DROPDOWN -> "Selects the entries included in " + subject + ".";
            case LIST -> "Manages the saved entries used by " + subject + ".";
            case TEXT -> "Sets the text used for " + subject + ".";
            case COLOR -> "Sets the color used for " + subject + ".";
            case POSITION -> "Sets the in-world position used for " + subject + ".";
            case KEYBIND -> "Changes the keyboard key used for " + subject + ".";
            case ACTION -> "Runs the “" + subject + "” action immediately.";
            case INFO -> "Displays current information for " + subject + ".";
            case SECTION -> "Groups settings related to " + subject + ".";
        };
    }

    private static String lowerFirst(String value) {
        if (value == null || value.isEmpty()) {
            return "this setting";
        }
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }
}
