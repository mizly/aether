package dev.aether.ui.settings;

import dev.aether.config.entries.BooleanEntry;
import dev.aether.config.entries.IntEntry;
import org.junit.jupiter.api.Test;

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
}
