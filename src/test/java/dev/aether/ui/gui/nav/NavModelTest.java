package dev.aether.ui.gui.nav;

import dev.aether.ui.MainGUIRegistry;
import dev.aether.ui.gui.TestConfigDir;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.ToggleSetting;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class NavModelTest {

    @BeforeAll
    static void registry() {
        TestConfigDir.ensure();
        MainGUIRegistry.refresh();
    }

    @Test
    void theFirstCutPlacesEveryRegisteredSubTabIntoTheSevenCategories() {
        NavModel model = NavModel.build(MainGUIRegistry.snapshot(), PlacementTable.current());
        assertEquals(List.of("farming", "pests", "garden", "macros", "safety", "display", "client"),
                model.categories().stream().map(NavCategory::id).toList());
        assertEquals(List.of(), model.pages().stream().filter(NavPage::fallthrough).map(NavPage::id).toList(),
                "the first cut names every subtab");
        Set<ModulesTab.SubTab> drawn = new HashSet<>();
        for (NavPage page : model.pages()) {
            for (PageBlock block : page.blocks()) {
                drawn.add(block.source());
            }
        }
        for (ModulesTab.SubTab subTab : model.subTabs()) {
            assertTrue(drawn.contains(subTab), subTab.rawName() + " is on a page");
            assertTrue(model.pageForSubTab(subTab.rawName()).isPresent(), subTab.rawName() + " has an owner page");
        }
        assertEquals("farming", model.page("farming-macro").orElseThrow().categoryId());
        assertEquals("macros", model.page("strider-fishing").orElseThrow().categoryId());
        assertEquals("safety", model.page("ghost-block").orElseThrow().categoryId());
        assertEquals("client", model.page("keybinds").orElseThrow().categoryId());
        assertTrue(model.page(PlacementTable.APPEARANCE).orElseThrow().custom());
        assertTrue(model.page(PlacementTable.PROFILES).orElseThrow().custom());
        assertTrue(model.page("pip").orElseThrow().toggle().runtime());
        assertNotNull(model.page("pest-manager").orElseThrow().toggle(), "a lone toggled subtab keeps its switch");
        assertNull(model.page("farming-macro").orElseThrow().toggle());
        model.categories().forEach(category -> assertNotNull(category.icon(), category.id()));
    }

    @Test
    void oldLaunchTargetsResolveByRawName() {
        NavModel model = NavModel.current();
        assertEquals("farming-macro", model.locate(null, "Farming Macro", null, null).orElseThrow().location().pageId());
        assertEquals("strider-fishing", model.locate(null, "Strider Fishing", null, null).orElseThrow().location().pageId());
        assertEquals("rewarp", model.locate("rewarp", null, null, null).orElseThrow().location().pageId());
        assertTrue(model.locate(null, "Authentication", null, null).isEmpty());

        NavModel.Target target = model.locate(null, "Pest Manager", "Pest Destroyer", null).orElseThrow();
        assertEquals(new GroupKey("pest-manager", "Pest Destroyer", 0), target.group());
        assertEquals(target.group(), GroupKey.fromAnchor(target.location().anchor()));
        assertSame(model.group(target.group()).orElseThrow().getRawName(), "Pest Destroyer");
    }

    @Test
    void settingKeysRoundTripThroughAnchorsAndResolveLive() {
        NavModel model = NavModel.current();
        NavModel.Target target = model.locate(null, "Farming Macro", null, "Farm Type").orElseThrow();
        SettingKey key = target.setting();
        assertNotNull(key);
        assertEquals(key, SettingKey.fromAnchor(key.anchor()));
        assertEquals("Farm Type", model.setting(key).orElseThrow().getRawName());
        assertNull(SettingKey.fromAnchor(key.group().anchor()));
        assertNull(GroupKey.fromAnchor(key.anchor()));
    }

    @Test
    void unplacedSubTabsFallThroughToTheirSectionsCategory() {
        ModulesTab.SubTab placed = new ModulesTab.SubTab("Placed Thing", "", List.of(group("Alpha")));
        ModulesTab.SubTab later = new ModulesTab.SubTab("Added Later", "a new feature", List.of(group("Beta")));
        ModulesTab.SubTab stranger = new ModulesTab.SubTab("Stranger", "", List.of(group("Gamma")));
        MainGUIRegistry.Snapshot snapshot = new MainGUIRegistry.Snapshot(7L, List.of(
                new MainGUIRegistry.ModuleSection("farming", "Farming", List.of(placed, later)),
                new MainGUIRegistry.ModuleSection("brand_new", "Brand New", List.of(stranger))),
                List.of(), List.of(), List.of());
        Placement placement = Placement.builder()
                .categories(Categories.ALL)
                .sections(Categories.SECTION_CATEGORIES)
                .pages(Categories.PESTS, pages -> pages.subTab("Placed Thing", "minecraft:stone"))
                .build();

        NavModel model = NavModel.build(snapshot, placement);
        NavPage fallthrough = model.page("added-later").orElseThrow();
        assertTrue(fallthrough.fallthrough());
        assertEquals("farming", fallthrough.categoryId());
        assertEquals("a new feature", fallthrough.rawDescription());
        assertEquals("pests", model.page("placed-thing").orElseThrow().categoryId());
        NavPage strangerPage = model.page("stranger").orElseThrow();
        assertEquals("section-brand_new", strangerPage.categoryId());
        assertEquals(List.of("farming", "pests", "section-brand_new"),
                model.categories().stream().map(NavCategory::id).toList());
    }

    @Test
    void mergedPagesHaveHeadedBlocksWithTheirOwnSwitchesAndGroupsReadLive() {
        AtomicBoolean firstOn = new AtomicBoolean(true);
        AtomicBoolean secondOn = new AtomicBoolean(false);
        List<SettingGroup> liveGroups = new ArrayList<>(List.of(group("One")));
        ModulesTab.SubTab first = new ModulesTab.SubTab("First", "", firstOn::get, firstOn::set, liveGroups);
        ModulesTab.SubTab second = new ModulesTab.SubTab("Second", "", secondOn::get, secondOn::set,
                List.of(group("Two"), group("Three")));
        MainGUIRegistry.Snapshot snapshot = new MainGUIRegistry.Snapshot(1L, List.of(
                new MainGUIRegistry.ModuleSection("farming", "Farming", List.of(first, second))),
                List.of(), List.of(), List.of());
        Placement placement = Placement.builder()
                .categories(Categories.ALL)
                .pages(Categories.FARMING, pages -> pages
                        .page("merged", "Merged", page -> page.from("First").from("Second", "Two")
                                .toggleFromSubTab("First"))
                        .page("split", "Split", page -> page.from("Second", "Three")))
                .build();

        NavModel model = NavModel.build(snapshot, placement);
        NavPage merged = model.page("merged").orElseThrow();
        assertEquals(List.of("first", "second", "second-more"), merged.blocks().stream().map(PageBlock::id).toList());
        assertTrue(merged.blocks().getFirst().headed());
        assertNull(merged.blocks().getFirst().toggle(), "the page switch is First's switch");
        assertNotNull(merged.blocks().get(1).toggle());
        assertTrue(merged.isEnabled());
        merged.toggle().toggle();
        assertFalse(firstOn.get());
        assertEquals(List.of("One", "Two"), merged.groups().stream().map(SettingGroup::getRawName).toList());
        assertEquals(List.of(), merged.blocks().get(2).groups(), "every group of Second is placed");

        liveGroups.add(group("Added"));
        assertEquals(List.of("One", "Added", "Two"), merged.groups().stream().map(SettingGroup::getRawName).toList());
        NavPage split = model.page("split").orElseThrow();
        assertEquals(1, split.blocks().size());
        assertFalse(split.blocks().getFirst().headed());
        assertNull(split.toggle(), "AUTO gives a page a switch only when it has one whole source");
        assertEquals("merged", model.pageForSubTab("Second").orElseThrow().id(), "first page that takes Second");
    }

    @Test
    void groupTogglesCanBecomeThePageToggle() {
        AtomicBoolean on = new AtomicBoolean(false);
        List<SettingGroup> groups = new ArrayList<>();
        groups.add(SettingGroup.of("Switched", "", on::get, on::set).add(new ToggleSetting("Knob", () -> true, v -> {})));
        ModulesTab.SubTab tab = new ModulesTab.SubTab("Tab", "", groups);
        MainGUIRegistry.Snapshot snapshot = new MainGUIRegistry.Snapshot(1L, List.of(
                new MainGUIRegistry.ModuleSection("farming", "Farming", List.of(tab))), List.of(), List.of(), List.of());
        Placement placement = Placement.builder().categories(Categories.ALL)
                .pages(Categories.FARMING, pages -> pages.page("tab", "Tab", page -> page.from("Tab")
                        .toggleFromGroup("Tab", "Switched")))
                .build();
        NavPage page = NavModel.build(snapshot, placement).page("tab").orElseThrow();
        assertEquals(NavPage.GroupMode.PAGE_TOGGLE, page.groupMode(groups.getFirst()));
        assertFalse(page.isEnabled());
        page.toggle().set(true);
        assertTrue(on.get());
        groups.set(0, SettingGroup.of("Switched", "", () -> false, v -> {}));
        assertFalse(page.isEnabled(), "the switch follows the rebuilt group");
    }

    @Test
    void placementRejectsBrokenTables() {
        assertThrows(IllegalStateException.class, () -> Placement.builder().categories(Categories.ALL)
                .pages(Categories.FARMING, pages -> pages.subTab("A", null).subTab("A", null)).build());
        assertThrows(IllegalStateException.class, () -> Placement.builder().categories(Categories.ALL)
                .pages("nowhere", pages -> pages.subTab("A", null)).build());
        assertThrows(IllegalStateException.class, () -> Placement.builder().categories(Categories.ALL)
                .pages(Categories.FARMING, pages -> pages.page("a", "A", page -> page.from("A").requires("b"))).build());
    }

    private static SettingGroup group(String name) {
        return SettingGroup.alwaysOn(name, "").add(new ToggleSetting(name + " Knob", () -> true, v -> {}));
    }
}
