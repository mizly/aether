package dev.aether.ui.settings;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlotSettingTest {
    private final List<List<String>> writes = new ArrayList<>();
    private List<String> stored = new ArrayList<>();

    @Test
    void parsingReadsDigitsLikeTheConsumersDo() {
        assertEquals(PlotToken.plot(5), PlotToken.parse("Plot 5"));
        assertEquals(PlotToken.plot(5), PlotToken.parse(" 5"));
        assertEquals(PlotToken.plot(5), PlotToken.parse("05"));
        assertEquals(PlotToken.plot(12), PlotToken.parse("#12"));
        assertEquals(PlotToken.plot(7), PlotToken.parse("Plot - 7"));
        assertEquals(PlotToken.barn(), PlotToken.parse("barn"));
        assertEquals(PlotToken.barn(), PlotToken.parse(" The Barn "));
        assertEquals(PlotToken.unknown("0"), PlotToken.parse("0"));
        assertEquals(PlotToken.unknown("25"), PlotToken.parse("25"));
        assertEquals(PlotToken.unknown("Spawn"), PlotToken.parse(" Spawn "));
        assertNull(PlotToken.parse("  "));
        assertNull(PlotToken.parse(null));
        assertEquals("5", PlotToken.plot(5).text());
        assertEquals("barn", PlotToken.barn().text());
        assertFalse(PlotToken.unknown("x").isKnown());
        assertThrows(IllegalArgumentException.class, () -> PlotToken.plot(25));
        assertThrows(IllegalArgumentException.class, () -> PlotToken.plot(0));
    }

    @Test
    void legacyValuesShowAsTheirPlotAndDuplicatesCollapse() {
        stored = List.of("5", "Plot 5", " 5", "7");
        PlotSetting plots = list(PlotSetting.Mode.MULTI);
        assertEquals(List.of(PlotToken.plot(5), PlotToken.plot(7)), plots.tokens());
        assertTrue(plots.isSelected(PlotToken.plot(5)));
        assertTrue(writes.isEmpty(), "reading never rewrites the stored value");
        plots.toggle(PlotToken.plot(9));
        assertEquals(List.of(List.of("5", "7", "9")), writes);
    }

    @Test
    void multiKeepsTheStoredOrderAndAppends() {
        stored = List.of("12", "3");
        PlotSetting plots = list(PlotSetting.Mode.MULTI);
        plots.toggle(PlotToken.plot(1));
        assertEquals(List.of("12", "3", "1"), stored);
        plots.toggle(PlotToken.plot(3));
        assertEquals(List.of("12", "1"), stored);
        plots.select(PlotToken.plot(12));
        assertEquals(List.of("12", "1"), stored);
    }

    @Test
    void orderedClicksSetTheVisitOrderAndRemovalRenumbers() {
        PlotSetting greenhouses = list(PlotSetting.Mode.ORDERED);
        greenhouses.toggle(PlotToken.plot(3));
        greenhouses.toggle(PlotToken.plot(1));
        greenhouses.toggle(PlotToken.plot(2));
        assertEquals(List.of("3", "1", "2"), stored);
        assertEquals(2, greenhouses.order(PlotToken.plot(1)));
        assertEquals(0, greenhouses.order(PlotToken.plot(9)));
        greenhouses.toggle(PlotToken.plot(1));
        assertEquals(List.of("3", "2"), stored);
        assertEquals(2, greenhouses.order(PlotToken.plot(2)));
    }

    @Test
    void unknownEntriesSurviveEditsUntilRemoved() {
        stored = List.of("Spawn", "5");
        PlotSetting plots = list(PlotSetting.Mode.MULTI);
        assertEquals(List.of(PlotToken.unknown("Spawn"), PlotToken.plot(5)), plots.tokens());
        assertEquals(List.of(PlotToken.plot(5)), plots.selection());
        plots.toggle(PlotToken.plot(7));
        assertEquals(List.of("Spawn", "5", "7"), stored);
        plots.remove(PlotToken.unknown("Spawn"));
        assertEquals(List.of("5", "7"), stored);
        int before = writes.size();
        plots.remove(PlotToken.unknown("never there"));
        assertEquals(before, writes.size());
    }

    @Test
    void theBarnIsOnlyAPlotWhereItIsAllowed() {
        stored = List.of("barn", "4");
        PlotSetting noBarn = list(PlotSetting.Mode.MULTI);
        assertEquals(List.of(PlotToken.unknown("barn"), PlotToken.plot(4)), noBarn.tokens());
        assertFalse(noBarn.isSelectable(PlotToken.barn()));
        noBarn.toggle(PlotToken.barn());
        assertTrue(writes.isEmpty());

        PlotSetting withBarn = list(PlotSetting.Mode.MULTI).allowBarn();
        assertTrue(withBarn.isSelected(PlotToken.barn()));
        withBarn.toggle(PlotToken.barn());
        assertEquals(List.of("4"), stored);
        withBarn.toggle(PlotToken.barn());
        assertEquals(List.of("4", "barn"), stored);
    }

    @Test
    void restrictionsLimitWhatCanBePicked() {
        PlotSetting even = list(PlotSetting.Mode.ORDERED).restrictTo(token -> token.number() % 2 == 0);
        even.toggle(PlotToken.plot(3));
        assertTrue(writes.isEmpty());
        even.toggle(PlotToken.plot(4));
        assertEquals(List.of("4"), stored);
        assertFalse(even.isSelectable(PlotToken.unknown("4x")));
    }

    @Test
    void aSingleValueMapsItsEmptyValueBothWays() {
        String[] value = {"0"};
        PlotSetting traps = PlotSetting.single("Pest Traps Plot", () -> value[0], v -> value[0] = v, "0").allowBarn();
        assertEquals(PlotSetting.Mode.SINGLE, traps.mode());
        assertTrue(traps.isEmpty());
        value[0] = " 0 ";
        assertTrue(traps.isEmpty());
        value[0] = "";
        assertTrue(traps.isEmpty());
        value[0] = "Plot 5";
        assertEquals(List.of(PlotToken.plot(5)), traps.tokens());
        traps.toggle(PlotToken.plot(9));
        assertEquals("9", value[0]);
        traps.toggle(PlotToken.barn());
        assertEquals("barn", value[0]);
        traps.clear();
        assertEquals("0", value[0]);
        value[0] = "Spawn";
        assertEquals(List.of(PlotToken.unknown("Spawn")), traps.tokens());
        traps.toggle(PlotToken.plot(2));
        assertEquals("2", value[0]);
    }

    @Test
    void anEmptyListClearsToAnEmptyList() {
        stored = List.of("1", "2");
        PlotSetting plots = list(PlotSetting.Mode.MULTI).emptyMeaning(PlotSetting.EmptyMeaning.ALL);
        assertEquals(PlotSetting.EmptyMeaning.ALL, plots.emptyMeaning());
        plots.clear();
        assertEquals(List.of(), stored);
        assertTrue(plots.isEmpty());
    }

    @Test
    void theOldMenuEditsASingleAsTextAndAListByIndex() {
        String[] value = {"0"};
        PlotSetting single = PlotSetting.single("Drop at Plot TP", () -> value[0], v -> value[0] = v, "0")
                .describe("Plot to teleport to before dropping junk.");
        TextSetting text = (TextSetting) PlotSetting.asLegacy(single);
        assertSame(text, PlotSetting.asLegacy(single));
        assertEquals("Drop at Plot TP", text.getRawName());
        assertEquals("Plot to teleport to before dropping junk.", text.getDescription());
        assertEquals("", text.getValue());
        text.setValue("Plot 9");
        assertEquals("9", value[0]);
        assertEquals("9", text.getValue());
        text.setValue("");
        assertEquals("0", value[0]);

        stored = List.of("Plot 5", "");
        PlotSetting multi = list(PlotSetting.Mode.MULTI);
        ListSetting rows = (ListSetting) PlotSetting.asLegacy(multi);
        assertEquals(List.of("5", ""), rows.getValues());
        rows.setValues(List.of("5", "", " 7", "Spawn"));
        assertEquals(List.of("5", "", "7", "Spawn"), stored);

        ToggleSetting other = new ToggleSetting("Other", () -> true, v -> { });
        assertSame(other, PlotSetting.asLegacy(other));
    }

    @Test
    void plotSettingsHaveTheirOwnTypeAndFallbackDescription() {
        PlotSetting plots = list(PlotSetting.Mode.MULTI);
        assertEquals(SettingType.PLOT, plots.getType());
        assertEquals("Picks the garden plots used for Plots.", plots.getDescription());
    }

    private PlotSetting list(PlotSetting.Mode mode) {
        return new PlotSetting("Plots", mode, () -> stored, values -> {
            stored = new ArrayList<>(values);
            writes.add(List.copyOf(values));
        });
    }
}
