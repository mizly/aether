package dev.aether.ui.gui.nav;

import java.util.List;
import java.util.Map;

// the seven top-level categories every style navigates by, and where each registry section falls through to
public final class Categories {
    public static final String FARMING = "farming";
    public static final String PESTS = "pests";
    public static final String GARDEN = "garden";
    public static final String MACROS = "macros";
    public static final String SAFETY = "safety";
    public static final String DISPLAY = "display";
    public static final String CLIENT = "client";

    // the old menu's colours, keybinds and settings tabs have no section id, so they get these
    public static final String COLORS_SECTION = "colors";
    public static final String KEYBINDS_SECTION = "keybinds";
    public static final String SETTINGS_SECTION = "settings";

    public static final List<Placement.CategorySpec> ALL = List.of(
            new Placement.CategorySpec(FARMING, "Farming",
                    "The farming macro, rewarp, loadouts and breaks", "minecraft:wheat", 100),
            new Placement.CategorySpec(PESTS, "Pests",
                    "Pest Destroyer, traps, ESP and sprays", "minecraft:silverfish_spawn_egg", 200),
            new Placement.CategorySpec(GARDEN, "Garden",
                    "Visitors, greenhouse, composter and selling", "minecraft:composter", 300),
            new Placement.CategorySpec(MACROS, "Other Macros",
                    "Fishing, mining and carnival macros", "minecraft:fishing_rod", 400),
            new Placement.CategorySpec(SAFETY, "Safety",
                    "Failsafes and humanization", "minecraft:totem_of_undying", 500),
            new Placement.CategorySpec(DISPLAY, "Display",
                    "HUD, stats, cosmetics and camera tools", "minecraft:painting", 600),
            new Placement.CategorySpec(CLIENT, "Client",
                    "General settings, integrations, appearance, keybinds and profiles", "minecraft:comparator", 700));

    public static final Map<String, String> SECTION_CATEGORIES = Map.ofEntries(
            Map.entry("farming", FARMING),
            Map.entry("fishing", MACROS),
            Map.entry("other", MACROS),
            Map.entry("failsafes", SAFETY),
            Map.entry("failsafes_farming", SAFETY),
            Map.entry("failsafes_fishing", SAFETY),
            Map.entry("visuals", DISPLAY),
            Map.entry(COLORS_SECTION, CLIENT),
            Map.entry(KEYBINDS_SECTION, CLIENT),
            Map.entry(SETTINGS_SECTION, CLIENT));

    private Categories() {
    }
}
