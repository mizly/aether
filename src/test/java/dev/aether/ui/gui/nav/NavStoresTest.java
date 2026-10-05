package dev.aether.ui.gui.nav;

import com.google.gson.JsonObject;
import dev.aether.ui.MainGUIRegistry;
import dev.aether.ui.gui.TestConfigDir;
import dev.aether.ui.settings.DropdownSetting;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.MultiDropdownSetting;
import dev.aether.ui.settings.RangeSliderSetting;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.ui.settings.SliderSetting;
import dev.aether.ui.settings.TextSetting;
import dev.aether.ui.settings.ToggleSetting;
import dev.aether.ui.theme.Theme;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class NavStoresTest {
    private static final SettingKey KNOB = new SettingKey(new GroupKey("page", "Group", 0), "Knob", 0);
    private static final SettingKey OTHER = new SettingKey(new GroupKey("page", "Group", 1), "Knob", 2);

    @BeforeAll
    static void configDir() {
        TestConfigDir.ensure();
    }

    @Test
    void pinsAndChangesRoundTripThroughTheThemeFileButNeverItsExport() {
        JsonObject saved = Theme.GUI_STATE;
        try {
            Theme.GUI_STATE = new JsonObject();
            PinStore pins = new PinStore(() -> Theme.GUI_STATE, Theme::saveTheme);
            ChangeLog changes = new ChangeLog(() -> Theme.GUI_STATE, Theme::saveTheme, () -> 1_000L);
            pins.pin(KNOB);
            pins.pin(OTHER);
            pins.pin(KNOB);
            changes.record(KNOB, "false", "true");

            Theme.GUI_STATE = new JsonObject();
            assertEquals(List.of(), pins.pins());
            Theme.loadTheme();
            assertEquals(List.of(KNOB, OTHER), pins.pins());
            assertEquals(List.of(new ChangeLog.Change(KNOB, "false", "true", 1_000L)), changes.recent());
            assertFalse(Theme.exportJson().contains("guiState"), "pins and changes stay out of shared themes");
            assertFalse(Theme.exportJson().contains("\"pins\""));

            assertFalse(pins.toggle(KNOB));
            assertEquals(List.of(OTHER), pins.pins());
            assertTrue(pins.toggle(KNOB));
            assertEquals(List.of(OTHER, KNOB), pins.pins());
        } finally {
            Theme.GUI_STATE = saved;
            Theme.saveTheme();
        }
    }

    @Test
    void changesMergeQuickEditsCapAndUndoIntoTheLiveSetting() {
        AtomicLong now = new AtomicLong(10_000L);
        JsonObject state = new JsonObject();
        ChangeLog log = new ChangeLog(() -> state, () -> {}, now::get);
        log.record(KNOB, "1", "2");
        now.addAndGet(500L);
        log.record(KNOB, "2", "3");
        assertEquals(List.of(new ChangeLog.Change(KNOB, "1", "3", 10_500L)), log.recent(), "a drag is one change");
        now.addAndGet(500L);
        log.record(KNOB, "3", "1");
        assertEquals(List.of(), log.recent(), "back where it started");
        now.addAndGet(10_000L);
        for (int i = 0; i < ChangeLog.LIMIT + 5; i++) {
            now.addAndGet(5_000L);
            log.record(i % 2 == 0 ? KNOB : OTHER, "a" + i, "b" + i);
        }
        assertEquals(ChangeLog.LIMIT, log.recent().size());

        AtomicBoolean value = new AtomicBoolean(true);
        ModulesTab.SubTab tab = new ModulesTab.SubTab("Tab", "", List.of(SettingGroup.alwaysOn("Group", "")
                .add(new ToggleSetting("Knob", value::get, value::set))));
        MainGUIRegistry.Snapshot snapshot = new MainGUIRegistry.Snapshot(1L, List.of(
                new MainGUIRegistry.ModuleSection("farming", "Farming", List.of(tab))), List.of(), List.of(), List.of());
        NavModel model = NavModel.build(snapshot, Placement.builder().categories(Categories.ALL)
                .pages(Categories.FARMING, pages -> pages.subTab("Tab", null)).build());
        SettingKey key = new SettingKey(new GroupKey("tab", "Group", 0), "Knob", 0);
        log.clear();
        log.record(key, "false", "true");
        log.record(OTHER, "x", "y");
        assertTrue(log.undoLast(model).isEmpty(), "a setting that is gone drops its entry");
        assertEquals(1, log.recent().size());
        assertEquals(key, log.undoLast(model).orElseThrow().key());
        assertFalse(value.get());
        assertEquals(List.of(), log.recent());
    }

    @Test
    void settingValuesReadWriteAndDisplay() {
        AtomicReference<Float> slider = new AtomicReference<>(2.5f);
        SliderSetting s = new SliderSetting("Slider", 0f, 10f, slider::get, slider::set);
        assertEquals("2.5", SettingValues.read(s));
        assertTrue(SettingValues.write(s, "4"));
        assertEquals(4f, slider.get());

        AtomicReference<Float> lo = new AtomicReference<>(1f);
        AtomicReference<Float> hi = new AtomicReference<>(3f);
        RangeSliderSetting range = new RangeSliderSetting("Range", 0f, 10f, lo::get, hi::get, (l, h) -> {
            lo.set(l);
            hi.set(h);
        });
        assertEquals("1;3", SettingValues.read(range));
        assertEquals("1 – 3", SettingValues.display(range, "1;3"));
        assertTrue(SettingValues.write(range, "2;5"));
        assertEquals(2f, lo.get());
        assertEquals(5f, hi.get());

        AtomicInteger index = new AtomicInteger(1);
        DropdownSetting dropdown = new DropdownSetting("Pick", List.of("Alpha", "Beta"), index::get, index::set);
        assertEquals("1", SettingValues.read(dropdown));
        assertEquals("Beta", SettingValues.display(dropdown, "1"));
        assertFalse(SettingValues.write(dropdown, "7"), "options changed since");
        assertTrue(SettingValues.write(dropdown, "0"));
        assertEquals(0, index.get());

        AtomicInteger mask = new AtomicInteger(0b101);
        MultiDropdownSetting multi = new MultiDropdownSetting("Many", List.of("A", "B", "C"), mask::get, mask::set);
        assertEquals("0,2", SettingValues.read(multi));
        assertTrue(SettingValues.write(multi, "1"));
        assertEquals(0b010, mask.get());

        AtomicReference<String> text = new AtomicReference<>("hello");
        TextSetting t = new TextSetting("Text", "", text::get, text::set);
        assertTrue(SettingValues.write(t, "bye"));
        assertEquals("bye", text.get());
        assertFalse(SettingValues.write(t, null));
    }
}
