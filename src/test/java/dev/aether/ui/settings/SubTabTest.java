package dev.aether.ui.settings;

import dev.aether.ui.gui.Icon;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SubTabTest {
    @Test
    void keepsTheRawNameBesideTheLocalisedOneAndDerivesAStableId() {
        ModulesTab.SubTab tab = new ModulesTab.SubTab("Auto Carnival (Shootout)", "Shoots targets", List.of());
        assertEquals("Auto Carnival (Shootout)", tab.rawName());
        assertEquals("Shoots targets", tab.rawDescription());
        assertEquals("auto-carnival-shootout", tab.id());
        assertEquals("", ModulesTab.SubTab.idFor(null));
        assertEquals("pest-esp", ModulesTab.SubTab.idFor("  Pest ESP! "));
    }

    @Test
    void copiesKeepTheNamesTheyWereGivenAndTheLiveGroupList() {
        List<SettingGroup> groups = new ArrayList<>();
        boolean[] enabled = {false};
        ModulesTab.SubTab tab = new ModulesTab.SubTab("PiP", "Picture in picture", () -> enabled[0], v -> enabled[0] = v, groups);
        ModulesTab.SubTab copy = tab.withIcon(Icon.item("filled_map")).withToggleKind(ModulesTab.ToggleKind.RUNTIME);
        assertSame(tab.name(), copy.name());
        assertSame(tab.description(), copy.description());
        assertSame(groups, copy.groups());
        assertEquals(Icon.item("minecraft:filled_map"), copy.icon());
        assertEquals(ModulesTab.ToggleKind.RUNTIME, copy.toggleKind());
        assertEquals(tab.id(), copy.id());
        assertNull(tab.icon());
        assertEquals(ModulesTab.ToggleKind.FEATURE, tab.toggleKind());

        copy.toggle();
        assertTrue(enabled[0]);
        assertTrue(tab.isEnabled());
    }

    @Test
    void subTabsWithoutAToggleAlwaysCountAsEnabled() {
        ModulesTab.SubTab tab = new ModulesTab.SubTab("Farming Macro", "Farms", List.of());
        assertFalse(tab.hasToggle());
        assertTrue(tab.isEnabled());
        tab.toggle();
        assertTrue(tab.isEnabled());
    }
}
