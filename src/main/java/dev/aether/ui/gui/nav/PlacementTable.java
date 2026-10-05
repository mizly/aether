package dev.aether.ui.gui.nav;

// the placement the gui uses. first cut: one page per registry subtab, sorted into the seven categories;
// the reorganisation replaces this data through the same builder
public final class PlacementTable {
    public static final String APPEARANCE = "appearance";
    public static final String PROFILES = "profiles";

    private static final Placement CURRENT = build();

    private PlacementTable() {
    }

    public static Placement current() {
        return CURRENT;
    }

    private static Placement build() {
        return Placement.builder()
                .categories(Categories.ALL)
                .sections(Categories.SECTION_CATEGORIES)
                .pages(Categories.FARMING, pages -> pages
                        .subTab("Farming Macro", "minecraft:diamond_hoe")
                        .subTab("Rewarp", "minecraft:ender_pearl")
                        .subTab("Auto Loadout", "minecraft:armor_stand")
                        .page("dynamic-rest", "Dynamic Rest", page -> page.icon("minecraft:red_bed")
                                .from("Dynamic Rest").aliases("Breaks")))
                .pages(Categories.PESTS, pages -> pages
                        .page("pest-manager", "Pest Manager", page -> page.icon("minecraft:iron_sword")
                                .from("Pest Manager").aliases("Pest Destroyer", "Pests"))
                        .subTab("Auto Sprayonator", "minecraft:splash_potion")
                        .subTab("Dynamic Pests", "minecraft:compass")
                        .subTab("Auto Pest Exchange", "minecraft:emerald"))
                .pages(Categories.GARDEN, pages -> pages
                        .subTab("Auto Visitor", "minecraft:villager_spawn_egg")
                        .subTab("Auto Greenhouse", "minecraft:glass")
                        .subTab("Auto Composter", "minecraft:composter")
                        .subTab("Auto Supercraft", "minecraft:crafting_table")
                        .page("farming-qol", "Farming QOL", page -> page.icon("minecraft:chest")
                                .from("Farming QOL").aliases("Inventory & Selling", "Auto Sell")))
                .pages(Categories.MACROS, pages -> pages
                        .subTab("Strider Fishing", "minecraft:fishing_rod")
                        .subTab("Metal Detector", "minecraft:iron_pickaxe")
                        .subTab("Auto Carnival (Shootout)", "minecraft:bow"))
                .pages(Categories.SAFETY, pages -> pages
                        .page("failsafe-settings", "Failsafe Settings", page -> page.icon("minecraft:bell")
                                .from("Failsafe Settings").aliases("Failsafes", "General Failsafes"))
                        .subTab("GUI Opened", "minecraft:chest")
                        .subTab("Rotation", "minecraft:compass")
                        .subTab("World Change", "minecraft:obsidian")
                        .subTab("Inventory Slot Changed", "minecraft:hopper")
                        .page("bps", "BPS", page -> page.icon("minecraft:clock").from("BPS")
                                .aliases("Farming Failsafes"))
                        .subTab("Dirt Check", "minecraft:dirt")
                        .subTab("Ghost Block", "minecraft:structure_void")
                        .page("player-nearby", "Player Nearby", page -> page.icon("minecraft:player_head")
                                .from("Player Nearby").aliases("Fishing Failsafes"))
                        .subTab("TP Check", "minecraft:chorus_fruit")
                        .subTab("Humanization", "minecraft:feather"))
                .pages(Categories.DISPLAY, pages -> pages
                        .page("hud", "HUD", page -> page.icon("minecraft:item_frame").from("HUD")
                                .aliases("Visuals"))
                        .subTab("HUD Colors", "minecraft:cyan_dye")
                        .subTab("Profit Tracker", "minecraft:gold_ingot")
                        .subTab("Nick Hider", "minecraft:name_tag")
                        .subTab("Freecam", "minecraft:ender_eye")
                        .subTab("Freelook", "minecraft:spyglass")
                        .page("pip", "PiP", page -> page.icon("minecraft:filled_map").from("PiP")
                                .runtimeToggle())
                        .subTab("Fun", "minecraft:firework_rocket")
                        .page("ungrab-mouse", "Ungrab Mouse", page -> page.icon("minecraft:lead")
                                .from("Ungrab Mouse").runtimeToggle())
                        .subTab("Skybox", "minecraft:sunflower")
                        .subTab("Menu Scene", "minecraft:spyglass"))
                .pages(Categories.CLIENT, pages -> pages
                        .page("miscellaneous", "Miscellaneous", page -> page.icon("minecraft:redstone")
                                .from("Miscellaneous").aliases("General"))
                        .subTab("Auto Update", "minecraft:experience_bottle")
                        .subTab("Discord", "minecraft:writable_book")
                        .subTab("Account", "minecraft:player_head")
                        .custom(APPEARANCE, "Appearance", "Style, theme presets, colours and scale",
                                "minecraft:painting", "Colors", "Theme", "Themes", "Theme Profiles")
                        .subTab("Theme Options", "minecraft:brush")
                        .subTab("Menu Colors", "minecraft:magenta_dye")
                        .subTab("Language", "minecraft:book")
                        .subTab("Bootstrap", "minecraft:command_block")
                        .page("keybinds", "Keybinds", page -> page.icon("minecraft:tripwire_hook")
                                .from("Aether").aliases("Aether Keybinds", "Controls"))
                        .custom(PROFILES, "Profiles", "Save, load, share and import config profiles",
                                "minecraft:bookshelf", "Config", "Config Profiles"))
                .build();
    }
}
