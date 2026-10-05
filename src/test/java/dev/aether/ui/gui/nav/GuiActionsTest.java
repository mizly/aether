package dev.aether.ui.gui.nav;

import com.google.gson.JsonObject;
import dev.aether.config.AetherConfig;
import dev.aether.ui.MainGUIRegistry;
import dev.aether.ui.gui.GuiFrame;
import dev.aether.ui.gui.GuiStyle;
import dev.aether.ui.gui.KeyInput;
import dev.aether.ui.gui.LaunchRequest;
import dev.aether.ui.gui.StyleRegistry;
import dev.aether.ui.gui.TestConfigDir;
import dev.aether.ui.gui.preview.PreviewGuiHost;
import dev.aether.ui.settings.Setting;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.ToggleSetting;
import dev.aether.ui.theme.Theme;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class GuiActionsTest {
    private final PreviewGuiHost host = new PreviewGuiHost();
    private final AtomicLong nanos = new AtomicLong(1_000L);
    private final AtomicReference<MainGUIRegistry.Snapshot> snapshot = new AtomicReference<>();
    private final JsonObject state = new JsonObject();
    private GuiActions actions;
    private String savedStyle;

    @BeforeAll
    static void registry() {
        TestConfigDir.ensure();
        MainGUIRegistry.refresh();
    }

    @BeforeEach
    void create() {
        savedStyle = Theme.GUI_STYLE;
        snapshot.set(MainGUIRegistry.snapshot());
        actions = new GuiActions(host, nanos::get, new StyleRegistry(List.of(style("aurora"), style("terminal"))),
                snapshot::get, PlacementTable::current, new PinStore(() -> state, () -> {}),
                new ChangeLog(() -> state, () -> {}, nanos::get));
        actions.open(null);
        actions.home();
    }

    @AfterEach
    void restore() {
        Theme.GUI_STYLE = savedStyle;
    }

    @Test
    void upClimbsTheHierarchyAndBackWalksHistory() {
        assertFalse(actions.up(), "home closes the screen");
        assertTrue(actions.openCategory("farming"));
        assertTrue(actions.openPage("rewarp"));
        assertEquals(NavLocation.page("farming", "rewarp"), actions.location());
        assertTrue(actions.openPage("hud"));
        assertTrue(actions.up());
        assertEquals(NavLocation.category("display"), actions.location());
        assertTrue(actions.up());
        assertEquals(NavLocation.HOME, actions.location());
        assertFalse(actions.up());

        assertTrue(actions.back());
        assertEquals(NavLocation.category("display"), actions.location());
        assertTrue(actions.back());
        assertEquals(NavLocation.page("display", "hud"), actions.location());
        assertTrue(actions.back());
        assertEquals(NavLocation.page("farming", "rewarp"), actions.location());
        assertFalse(actions.openPage("no-such-page"));
        assertFalse(actions.openCategory("no-such-category"));
    }

    @Test
    void revealIntoADisabledGroupPeeksItOpenScrollsAndFlashes() {
        boolean fastLane = AetherConfig.MACRO_FAST_LANE_SWITCH.get();
        AetherConfig.MACRO_FAST_LANE_SWITCH.set(false);
        try {
            NavPage page = actions.model().page("farming-macro").orElseThrow();
            SettingGroup group = page.groups().stream()
                    .filter(g -> g.getRawName().equals("Fast Lane Switch (Experimental)")).findFirst().orElseThrow();
            assertFalse(group.isEnabled());
            assertFalse(actions.showsChildren(page, group));

            assertTrue(actions.revealSetting("farming-macro", "Fast Lane Switch (Experimental)", "Boundary Axis"));
            SettingKey key = SettingKey.fromAnchor(actions.location().anchor());
            assertEquals("Boundary Axis", key.settingRawName());
            assertEquals(NavLocation.page("farming", "farming-macro").withAnchor(key.anchor()), actions.location());
            assertTrue(actions.peeked(key.group()));
            assertTrue(actions.showsChildren(page, group), "the revealed row is drawn");
            assertEquals(key.anchor(), actions.takeScrollAnchor("farming-macro"));
            assertNull(actions.takeScrollAnchor("farming-macro"), "scrolls once");
            assertEquals(1f, actions.flash(key.anchor()));
            nanos.addAndGet(GuiActions.FLASH_NANOS / 2);
            assertEquals(0.5f, actions.flash(key.anchor()), 0.01f);
            nanos.addAndGet(GuiActions.FLASH_NANOS);
            assertEquals(0f, actions.flash(key.anchor()));

            group.setEnabled(true);
            assertTrue(actions.showsChildren(page, group));
            group.setEnabled(false);
            assertFalse(actions.showsChildren(page, group), "enabling the group ended the peek");
        } finally {
            AetherConfig.MACRO_FAST_LANE_SWITCH.set(fastLane);
        }
    }

    @Test
    void launchRequestsMapOldTargetsAndOpenNullRestoresTheLastPlace() {
        actions.open(new LaunchRequest(null, "Farming Macro", null, null));
        assertEquals(NavLocation.page("farming", "farming-macro"), actions.location());
        assertFalse(actions.back(), "a deep link starts a fresh history");
        actions.open(new LaunchRequest(null, "Strider Fishing", null, null));
        assertEquals(NavLocation.page("macros", "strider-fishing"), actions.location());
        actions.open(new LaunchRequest(null, "Authentication", null, null));
        assertEquals(NavLocation.HOME, actions.location());
        actions.open(new LaunchRequest("hud", null, "Watermark", null));
        assertEquals(new GroupKey("hud", "Watermark", 0), GroupKey.fromAnchor(actions.location().anchor()));

        actions.openPage("rewarp");
        actions.close();
        actions.open(null);
        assertEquals(NavLocation.page("farming", "rewarp"), actions.location());
    }

    @Test
    void aRegistryRebuildKeepsTheLocationByIdAndFallsBack() {
        actions.openPage("hud");
        actions.openPage("rewarp");
        NavPage before = actions.model().page("rewarp").orElseThrow();
        MainGUIRegistry.invalidate();
        MainGUIRegistry.refresh();
        snapshot.set(MainGUIRegistry.snapshot());
        actions.snapshotChanged(snapshot.get());
        assertEquals(NavLocation.page("farming", "rewarp"), actions.location());
        assertNotSame(before, actions.model().page("rewarp").orElseThrow(), "rebuilt against the new snapshot");

        MainGUIRegistry.Snapshot full = snapshot.get();
        List<MainGUIRegistry.ModuleSection> withoutRewarp = full.sections().stream()
                .map(section -> new MainGUIRegistry.ModuleSection(section.id(), section.displayName(),
                        section.subtabs().stream().filter(tab -> !tab.rawName().equals("Rewarp")).toList()))
                .toList();
        snapshot.set(new MainGUIRegistry.Snapshot(full.generation() + 1, withoutRewarp, full.colors(), full.keybinds(),
                full.settings()));
        actions.snapshotChanged(snapshot.get());
        assertEquals(NavLocation.category("farming"), actions.location(), "a gone page falls back to its category");
        assertTrue(actions.back());
        assertEquals(NavLocation.page("display", "hud"), actions.location(), "history entries resolve too");

        snapshot.set(new MainGUIRegistry.Snapshot(full.generation() + 2, List.of(), List.of(), List.of(), List.of()));
        actions.snapshotChanged(snapshot.get());
        assertEquals(NavLocation.HOME, actions.location(), "and to home when the category is gone");
    }

    @Test
    void moduleTogglesStylesPinsAndUndo() {
        boolean rewarp = AetherConfig.ENABLE_REWARP.get();
        try {
            assertTrue(actions.toggleModule("rewarp"));
            assertNotEquals(rewarp, AetherConfig.ENABLE_REWARP.get());
            assertFalse(actions.toggleModule("farming-macro"), "no switch");
        } finally {
            AetherConfig.ENABLE_REWARP.set(rewarp);
        }

        assertTrue(actions.setStyle("terminal"));
        assertEquals("terminal", Theme.GUI_STYLE);
        assertFalse(actions.setStyle("nope"));
        assertEquals("terminal", Theme.GUI_STYLE);

        NavModel model = actions.model();
        NavModel.Target target = model.locate("farming-macro", null, null, "Disable /setspawn").orElseThrow();
        ToggleSetting setting = (ToggleSetting) model.setting(target.setting()).orElseThrow();
        boolean before = setting.getValue();
        try {
            actions.pin(target.setting());
            assertTrue(actions.isPinned(target.setting()));
            String value = SettingValues.read(setting);
            setting.setValue(!before);
            actions.recordChange(target.setting(), setting, value);
            assertEquals(1, actions.changes().recent().size());
            assertTrue(actions.shortcut(null, new KeyInput(GLFW.GLFW_KEY_Z, 0, GLFW.GLFW_MOD_CONTROL, true, false, false)));
            assertEquals(before, setting.getValue());
            assertFalse(actions.undo(), "nothing left");
        } finally {
            setting.setValue(before);
        }

        assertTrue(actions.shortcut(null, new KeyInput(GLFW.GLFW_KEY_ENTER, 0, GLFW.GLFW_MOD_CONTROL, true, false, false)));
        assertEquals(1, host.resumes());
        actions.openHudEditor();
        actions.openMacroMenu();
        assertEquals(1, host.hudEditorOpens());
        assertEquals(1, host.macroMenuOpens());
    }

    @Test
    void searchShortcutsAndQueryClearing() {
        int[] opened = {0};
        GuiActions.SearchOpener opener = (frame, index) -> {
            opened[0]++;
            return true;
        };
        assertFalse(actions.typed(null, "/"));
        actions.setSearchOpener(opener);
        assertTrue(actions.typed(null, "/"));
        assertFalse(actions.typed(null, "a"));
        assertTrue(actions.shortcut(null, new KeyInput(GLFW.GLFW_KEY_F, 0, GLFW.GLFW_MOD_CONTROL, true, false, false)));
        assertEquals(2, opened[0]);

        assertFalse(actions.clearSearch());
        actions.setQuery("rewarp");
        assertTrue(actions.clearSearch());
        assertEquals("", actions.query());

        List<SearchResult> results = actions.search().query("rewarp");
        assertEquals(SearchResult.Kind.PAGE, results.getFirst().kind());
        results.getFirst().run();
        assertEquals(NavLocation.page("farming", "rewarp"), actions.location());
        assertTrue(actions.search().query("style: terminal").stream()
                .anyMatch(r -> r.kind() == SearchResult.Kind.ACTION && r.key().equals("action:style:terminal")));
        assertTrue(actions.search().query("resume").stream().anyMatch(r -> r.key().equals("action:resume")));
    }

    private static GuiStyle style(String id) {
        return new GuiStyle() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String displayName() {
                return id.substring(0, 1).toUpperCase() + id.substring(1);
            }

            @Override
            public void render(GuiFrame frame) {
            }
        };
    }
}
