package dev.aether.ui.gui.nav;

import dev.aether.config.AetherConfig;
import dev.aether.config.FarmType;
import dev.aether.config.RewarpPointPairs;
import dev.aether.ui.MainGUIRegistry;
import dev.aether.ui.gui.TestConfigDir;
import dev.aether.ui.settings.ActionSetting;
import dev.aether.ui.settings.DropdownSetting;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.Setting;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.ToggleSetting;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SearchIndexTest {

    @BeforeAll
    static void registry() {
        TestConfigDir.ensure();
        MainGUIRegistry.refresh();
    }

    private static SettingGroup group(String name, String... settings) {
        SettingGroup group = SettingGroup.alwaysOn(name, "");
        for (String setting : settings) {
            group.add(new ToggleSetting(setting, () -> true, v -> {}));
        }
        return group;
    }

    private static SearchIndex index(NavModel model) {
        return new SearchIndex(() -> model)
                .add(SearchIndex.pages(page -> () -> {}))
                .add(SearchIndex.groups(key -> () -> {}))
                .add(SearchIndex.sections(key -> () -> {}))
                .add(SearchIndex.settings(key -> () -> {}));
    }

    private static NavModel model(List<ModulesTab.SubTab> tabs, Placement placement) {
        return NavModel.build(new MainGUIRegistry.Snapshot(1L, List.of(
                new MainGUIRegistry.ModuleSection("farming", "Farming", tabs)), List.of(), List.of(), List.of()), placement);
    }

    @Test
    void ranksByMatchClassThenPagesBeforeGroupsBeforeSettings() {
        SettingGroup helper = group("Visitor Tools", "Visitor Delay", "Supervisitors", "Visiting Organizer");
        helper.add(new ToggleSetting("Patience", () -> true, v -> {}).describe("Waits for the visitor to settle"));
        List<ModulesTab.SubTab> tabs = List.of(
                new ModulesTab.SubTab("Visitor", "", List.of(group("Visitor", "Unrelated"))),
                new ModulesTab.SubTab("Garden Helper", "", List.of(helper)),
                new ModulesTab.SubTab("Auto Visitor", "", List.of(group("Auto Visitor", "Threshold"))));
        Placement placement = Placement.builder().categories(Categories.ALL)
                .pages(Categories.GARDEN, pages -> pages.subTab("Visitor", null).subTab("Garden Helper", null)
                        .subTab("Auto Visitor", null))
                .build();

        List<SearchResult> results = index(model(tabs, placement)).query("Visitor");
        assertEquals(List.of(
                "PAGE Visitor EXACT",
                "GROUP Visitor Tools PREFIX",
                "SETTING Visitor Delay PREFIX",
                "PAGE Auto Visitor WORD_PREFIX",
                "SETTING Supervisitors CONTAINS",
                "SETTING Patience DESCRIPTION",
                "SETTING Visiting Organizer FUZZY"),
                results.stream().map(r -> r.kind() + " " + r.title() + " " + r.match()).toList());
        assertEquals(List.of("Garden", "Garden Helper", "Visitor Tools"), results.get(2).path());
        assertEquals(new SettingKey(new GroupKey("garden-helper", "Visitor Tools", 0), "Visitor Delay", 0),
                results.get(2).key());
        assertEquals(List.of(), index(model(tabs, placement)).query(" "));
        assertEquals(List.of("Visitor", "Auto Visitor"), index(model(tabs, placement)).query("v").stream()
                .map(SearchResult::title).toList(), "one letter only finds pages and actions");
    }

    @Test
    void findsPagesByOldNamesAndCollapsesMirrors() {
        List<ModulesTab.SubTab> tabs = List.of(
                new ModulesTab.SubTab("Pest Destroyer", "", List.of(group("Combat", "Pest FOV Range", "Swing Speed"))),
                new ModulesTab.SubTab("Humanization", "", List.of(group("FOV/Rotation", "Pest FOV Range", "Farming Yaw Range"))));
        Placement placement = Placement.builder().categories(Categories.ALL)
                .pages(Categories.PESTS, pages -> pages.page("pest-destroyer", "Pest Destroyer",
                        page -> page.from("Pest Destroyer").aliases("Pest Manager")))
                .pages(Categories.SAFETY, pages -> pages.page("humanization", "Humanization",
                        page -> page.from("Humanization").mirror("FOV/Rotation", "Pest FOV Range", "pest-destroyer")))
                .build();
        SearchIndex index = index(model(tabs, placement));

        List<SearchResult> old = index.query("pest manager");
        assertEquals("Pest Destroyer", old.getFirst().title());
        assertEquals(SearchResult.Match.EXACT, old.getFirst().match());

        List<SearchResult> fov = index.query("pest fov");
        assertEquals(1, fov.size());
        assertEquals(List.of("Pests", "Pest Destroyer", "Combat"), fov.getFirst().path());
        assertEquals(List.of("Humanization"), fov.getFirst().alsoOn());
        assertEquals(1, index.query("farming yaw").size(), "a mirror without its owner stays");
    }

    @Test
    void hiddenSettingsAreNeverReturned() {
        boolean[] shown = {false};
        SettingGroup group = group("Group", "Placeholder Knob");
        group.add(new ToggleSetting("Ghost Knob", () -> true, v -> {}).visibleWhen(() -> shown[0]));
        group.add(new ToggleSetting("Duplicate Knob", () -> true, v -> {}));
        List<ModulesTab.SubTab> tabs = List.of(new ModulesTab.SubTab("Tab", "", List.of(group)));
        Placement placement = Placement.builder().categories(Categories.ALL)
                .pages(Categories.FARMING, pages -> pages.page("tab", "Tab", page -> page.from("Tab")
                        .hideSetting("Group", "Duplicate Knob")))
                .build();
        SearchIndex index = index(model(tabs, placement));
        assertEquals(List.of(), index.query("ghost knob"));
        assertEquals(List.of(), index.query("duplicate knob"));
        shown[0] = true;
        assertEquals(1, index.query("ghost knob").size(), "visibility is read at query time");

        NavModel real = NavModel.current();
        assertEquals(List.of(), index(real).query("Add Pet"), "the pet tracker's hidden actions");
    }

    @Test
    void findsPlotNumberInANewlyAddedRewarpPair() {
        NavModel model = NavModel.current();
        SearchIndex index = index(model);
        NavPage rewarp = model.page("rewarp").orElseThrow();
        int pairs = RewarpPointPairs.get().size();
        try {
            ActionSetting add = (ActionSetting) setting(rewarp.groups().getLast(), "Add Rewarp");
            add.execute();
            SettingGroup added = rewarp.groups().getLast();
            assertNotEquals(pairs, RewarpPointPairs.get().size());
            ((DropdownSetting) setting(added, "Rewarp Mode")).setSelectedIndex(0);

            List<SearchResult> results = index.query("Plot Number");
            SettingKey key = (SettingKey) results.stream()
                    .filter(r -> r.key() instanceof SettingKey k && k.group().groupRawName().equals(added.getRawName()))
                    .findFirst().orElseThrow().key();
            assertEquals("rewarp", key.pageId());
            assertSame(setting(added, "Plot Number"), model.setting(key).orElseThrow());
        } finally {
            while (RewarpPointPairs.get().size() > Math.max(1, pairs)) {
                ((ActionSetting) setting(rewarp.groups().getLast(), "Remove Rewarp")).execute();
            }
        }
    }

    @Test
    void findsTheFirstFarmWaypointAfterSwitchingToCustom() {
        NavModel model = NavModel.current();
        SearchIndex index = index(model);
        String farmType = AetherConfig.FARM_TYPE.get();
        DropdownSetting type = (DropdownSetting) setting(model.page("farming-macro").orElseThrow().groups().get(1), "Farm Type");
        try {
            assertEquals(List.of(), index.query("Farm Waypoint #1"));
            type.setSelectedIndex(FarmType.CUSTOM.ordinal());
            List<SearchResult> results = index.query("Farm Waypoint #1");
            assertEquals("Farm Waypoint #1", results.getFirst().title());
            assertEquals(new SettingKey(new GroupKey("farming-macro", "Farm Waypoints", 0), "Farm Waypoint #1", 0),
                    results.getFirst().key());
        } finally {
            type.setSelectedIndex(FarmType.valueOf(farmType).ordinal());
        }
        assertEquals(List.of(), index.query("Farm Waypoint #1"));
    }

    private static Setting setting(SettingGroup group, String rawName) {
        return group.getSettings().stream().filter(s -> s.getRawName().equals(rawName)).findFirst().orElseThrow();
    }
}
