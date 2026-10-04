package dev.aether.ui.settings;

import dev.aether.config.entries.BooleanEntry;
import dev.aether.config.entries.IntEntry;
import dev.aether.ui.gui.Icon;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SettingModelTest {
    @Test
    void onlyRealTextCountsAsAnExplicitDescription() {
        ToggleSetting plain = new ToggleSetting("Some Toggle Nobody Described", () -> true, v -> { });
        assertNull(plain.explicitDescription());
        assertEquals("Turns Some Toggle Nobody Described on or off.", plain.getDescription());

        ToggleSetting catalogued = new ToggleSetting("Human Target Switch", () -> true, v -> { });
        assertNotNull(catalogued.explicitDescription());
        assertEquals(catalogued.explicitDescription(), catalogued.getDescription());

        ToggleSetting described = new ToggleSetting("Human Target Switch", () -> true, v -> { })
                .describe("Flicks onto each new pest.");
        assertEquals("Flicks onto each new pest.", described.explicitDescription());
        assertEquals("Flicks onto each new pest.", described.getDescription());
    }

    @Test
    void sectionsDescribeThemselves() {
        SectionSetting section = new SectionSetting("Targeting", "Which pests to go after");
        assertEquals("Which pests to go after", section.explicitDescription());
        assertEquals("Which pests to go after", section.getDescription());
        SectionSetting bare = new SectionSetting("Combat", null);
        assertNull(bare.explicitDescription());
        assertEquals("", bare.getDescription());
    }

    @Test
    void fluentCallsKeepTheConcreteTypeAndBindingsAccumulate() {
        BooleanEntry enabled = new BooleanEntry("testEnabled", false);
        IntEntry low = new IntEntry("testLow", 1);
        IntEntry high = new IntEntry("testHigh", 2);
        RangeSliderSetting range = new RangeSliderSetting("Delay", 0f, 10f, () -> 1f, () -> 2f, (a, b) -> { })
                .withDecimals(0)
                .bind(low)
                .bind(high)
                .mirrorOf("pest-destroyer")
                .visibleWhen(() -> false)
                .withSuffix("ms");
        assertEquals(List.of(low, high), range.bindings());
        assertEquals("pest-destroyer", range.mirrorOf());
        assertFalse(range.isVisible());
        assertEquals("ms", range.getSuffix());
        assertThrows(UnsupportedOperationException.class, () -> range.bindings().add(enabled));

        ToggleSetting toggle = new ToggleSetting("Plain", () -> true, v -> { });
        assertEquals(List.of(), toggle.bindings());
        assertNull(toggle.mirrorOf());
        assertTrue(toggle.isVisible());
        assertEquals("Plain", toggle.getRawName());
    }

    @Test
    void optionIconsAreKeyedByIndex() {
        Icon wheat = Icon.item("wheat");
        Icon cane = Icon.item("minecraft:sugar_cane");
        DropdownSetting farm = new DropdownSetting("Farm Type", List.of("S-Shape", "S-Shape (Cane)", "Custom"), () -> 0, i -> { })
                .optionIcons(Arrays.asList(wheat, cane, null));
        assertEquals(wheat, farm.optionIcon(0));
        assertEquals(Icon.item("SUGAR_CANE"), farm.optionIcon(1));
        assertNull(farm.optionIcon(2));
        assertNull(farm.optionIcon(3));
        assertNull(farm.optionIcon(-1));

        DropdownListSetting crops = new DropdownListSetting("Crops", List.of("Wheat", "Cane"), List::of, v -> { })
                .optionIcons(List.of(wheat, cane));
        assertEquals(cane, crops.optionIcon(1));
        assertEquals(cane, crops.getAddPicker().optionIcon(1));

        MultiDropdownSetting keys = new MultiDropdownSetting("Keys", List.of("W", "A"), () -> 0, v -> { })
                .optionIcons(List.of(wheat));
        assertEquals(wheat, keys.optionIcon(0));
        assertNull(keys.optionIcon(1));
    }

    @Test
    void destructiveDropdownsAskForConfirmation() {
        DropdownSetting plain = new DropdownSetting("Mode", List.of("A", "B"), () -> 0, i -> { });
        assertFalse(plain.confirmsChange());
        assertTrue(plain.confirmChange().confirmsChange());
    }
}
