package dev.aether.ui.orbit.panel;

import dev.aether.ui.MainGUIRegistry;
import dev.aether.ui.gui.Icon;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.util.AetherLang;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// categories and pages built from the registry snapshot; a first-cut placement that the shared nav model replaces
final class PanelNav {

    record Category(String id, String rawName, String rawDescription, Icon icon, List<Page> pages) {
        String name() {
            return AetherLang.localize(rawName);
        }

        String description() {
            return AetherLang.localize(rawDescription);
        }
    }

    record Page(String id, String categoryId, ModulesTab.SubTab tab, Icon icon) {
        String name() {
            return tab.name();
        }

        String description() {
            return tab.description() == null ? "" : tab.description();
        }

        boolean hasToggle() {
            return tab.hasToggle();
        }

        boolean enabled() {
            return tab.isEnabled();
        }

        boolean runtime() {
            return tab.toggleKind() == ModulesTab.ToggleKind.RUNTIME;
        }
    }

    private record CategoryDef(String id, String rawName, String rawDescription, String item) {
    }

    private static final List<CategoryDef> CATEGORIES = List.of(
            new CategoryDef("farming", "Farming", "The farming macro, rewarp points, loadouts and breaks", "wheat"),
            new CategoryDef("pests", "Pests", "Find, kill and trap pests while you farm", "silverfish_spawn_egg"),
            new CategoryDef("garden", "Garden", "Visitors, greenhouse, composter and inventory helpers", "composter"),
            new CategoryDef("macros", "Other Macros", "Fishing, mining and event macros", "fishing_rod"),
            new CategoryDef("safety", "Safety", "Failsafes and human-like behaviour", "totem_of_undying"),
            new CategoryDef("display", "Display", "HUD, profit tracking and visual tweaks", "painting"),
            new CategoryDef("client", "Client", "Interface, integrations, keybinds and updates", "comparator"));

    private static final Map<String, String> PLACEMENT = Map.ofEntries(
            Map.entry("Farming Macro", "farming"), Map.entry("Rewarp", "farming"),
            Map.entry("Auto Loadout", "farming"), Map.entry("Dynamic Rest", "farming"),
            Map.entry("Pest Manager", "pests"), Map.entry("Dynamic Pests", "pests"),
            Map.entry("Auto Sprayonator", "pests"), Map.entry("Auto Pest Exchange", "pests"),
            Map.entry("Auto Visitor", "garden"), Map.entry("Auto Greenhouse", "garden"),
            Map.entry("Auto Composter", "garden"), Map.entry("Auto Supercraft", "garden"),
            Map.entry("Farming QOL", "garden"),
            Map.entry("Strider Fishing", "macros"), Map.entry("Metal Detector", "macros"),
            Map.entry("Auto Carnival (Shootout)", "macros"),
            Map.entry("Humanization", "safety"),
            Map.entry("HUD Colors", "display"),
            Map.entry("Miscellaneous", "client"), Map.entry("Auto Update", "client"), Map.entry("Discord", "client"));

    private static final Map<String, String> SECTION_FALLBACK = Map.of(
            "farming", "farming", "fishing", "macros", "other", "client", "failsafes", "safety",
            "failsafes_farming", "safety", "failsafes_fishing", "safety", "visuals", "display");

    private static final Map<String, String> PAGE_ITEMS = Map.ofEntries(
            Map.entry("Farming Macro", "diamond_hoe"), Map.entry("Rewarp", "ender_pearl"),
            Map.entry("Auto Loadout", "armor_stand"), Map.entry("Dynamic Rest", "red_bed"),
            Map.entry("Pest Manager", "silverfish_spawn_egg"), Map.entry("Dynamic Pests", "fermented_spider_eye"),
            Map.entry("Auto Sprayonator", "bone_meal"), Map.entry("Auto Pest Exchange", "gold_ingot"),
            Map.entry("Auto Visitor", "emerald"), Map.entry("Auto Greenhouse", "white_stained_glass"),
            Map.entry("Auto Composter", "composter"), Map.entry("Auto Supercraft", "crafting_table"),
            Map.entry("Farming QOL", "chest"), Map.entry("Humanization", "player_head"),
            Map.entry("Miscellaneous", "redstone"), Map.entry("Strider Fishing", "fishing_rod"),
            Map.entry("Auto Update", "knowledge_book"), Map.entry("Metal Detector", "diamond_shovel"),
            Map.entry("Auto Carnival (Shootout)", "bow"), Map.entry("Discord", "bell"),
            Map.entry("Failsafe Settings", "totem_of_undying"), Map.entry("GUI Opened", "chest"),
            Map.entry("Rotation", "compass"), Map.entry("World Change", "ender_eye"),
            Map.entry("Inventory Slot Changed", "hopper"), Map.entry("BPS", "clock"),
            Map.entry("Dirt Check", "dirt"), Map.entry("Ghost Block", "glass"),
            Map.entry("Player Nearby", "player_head"), Map.entry("TP Check", "ender_pearl"),
            Map.entry("HUD", "item_frame"), Map.entry("Profit Tracker", "gold_ingot"),
            Map.entry("Nick Hider", "name_tag"), Map.entry("Freecam", "spyglass"),
            Map.entry("Freelook", "ender_eye"), Map.entry("PiP", "painting"),
            Map.entry("Fun", "firework_rocket"), Map.entry("Ungrab Mouse", "lead"),
            Map.entry("Skybox", "daylight_detector"), Map.entry("HUD Colors", "lime_dye"),
            Map.entry("Menu Colors", "orange_dye"), Map.entry("Aether", "tripwire_hook"),
            Map.entry("Theme Options", "glow_item_frame"), Map.entry("Language", "writable_book"),
            Map.entry("Account", "player_head"), Map.entry("Bootstrap", "comparator"));

    private long generation = Long.MIN_VALUE;
    private List<Category> categories = List.of();
    private final Map<String, Page> pages = new LinkedHashMap<>();

    List<Category> categories() {
        MainGUIRegistry.Snapshot snapshot = MainGUIRegistry.snapshot();
        if (snapshot.generation() != generation) {
            rebuild(snapshot);
        }
        return categories;
    }

    Category category(String id) {
        for (Category category : categories()) {
            if (category.id().equals(id)) {
                return category;
            }
        }
        return null;
    }

    Page page(String id) {
        categories();
        return id == null ? null : pages.get(id);
    }

    long generation() {
        return generation;
    }

    private void rebuild(MainGUIRegistry.Snapshot snapshot) {
        Map<String, List<Page>> byCategory = new LinkedHashMap<>();
        for (CategoryDef def : CATEGORIES) {
            byCategory.put(def.id(), new ArrayList<>());
        }
        pages.clear();
        for (MainGUIRegistry.ModuleSection section : snapshot.sections()) {
            String fallback = SECTION_FALLBACK.getOrDefault(section.id(), "client");
            for (ModulesTab.SubTab tab : section.subtabs()) {
                place(byCategory, PLACEMENT.getOrDefault(tab.rawName(), fallback), tab);
            }
        }
        for (ModulesTab.SubTab tab : snapshot.colors()) {
            place(byCategory, PLACEMENT.getOrDefault(tab.rawName(), "client"), tab);
        }
        for (ModulesTab.SubTab tab : snapshot.settings()) {
            place(byCategory, "client", tab);
        }
        for (ModulesTab.SubTab tab : snapshot.keybinds()) {
            place(byCategory, "client", tab);
        }
        List<Category> built = new ArrayList<>();
        for (CategoryDef def : CATEGORIES) {
            built.add(new Category(def.id(), def.rawName(), def.rawDescription(), Icon.item(def.item()),
                    List.copyOf(byCategory.get(def.id()))));
        }
        categories = List.copyOf(built);
        generation = snapshot.generation();
    }

    private void place(Map<String, List<Page>> byCategory, String categoryId, ModulesTab.SubTab tab) {
        String id = tab.id();
        if (id.isEmpty() || pages.containsKey(id)) {
            id = categoryId + "-" + id + "-" + pages.size();
        }
        Icon icon = tab.icon() != null ? tab.icon() : Icon.item(PAGE_ITEMS.getOrDefault(tab.rawName(), "paper"));
        Page page = new Page(id, categoryId, tab, icon);
        pages.put(id, page);
        byCategory.get(categoryId).add(page);
    }
}
