package dev.aether.ui.gui.plot;

import dev.aether.ui.settings.PlotSetting;
import dev.aether.ui.settings.PlotSetting.EmptyMeaning;
import dev.aether.ui.settings.PlotSetting.Mode;
import dev.aether.ui.settings.PlotToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlotPickerModelTest {
    private List<String> stored = new ArrayList<>();

    @AfterEach
    void forgetGarden() {
        GardenPlotData.install(null);
    }

    @Test
    void withoutAMenuEveryPlotIsAGrayPaneCountingItsNumber() {
        PlotPickerModel model = new PlotPickerModel(multi(EmptyMeaning.NONE));
        PlotPickerModel.PlotLook five = model.look(5, GardenFacts.NONE);
        assertEquals("minecraft:gray_stained_glass_pane", five.itemId());
        assertEquals(5, five.count());
        assertTrue(five.unknown());
        assertEquals(1f, five.alpha());
        PlotPickerModel.PlotLook barn = model.look(0, GardenFacts.NONE);
        assertEquals("minecraft:dark_oak_planks", barn.itemId());
        assertEquals(0, barn.count());
        assertFalse(barn.selectable(), "multi plot lists never take the barn");
        assertEquals(PlotPickerModel.DIMMED, barn.alpha());
        List<String> tooltip = model.tooltip(5, GardenFacts.NONE);
        assertEquals("§fPlot §7- §b5", tooltip.getFirst());
        assertTrue(tooltip.contains("§7Open §e/desk §7› §aConfigure Plots"));
    }

    @Test
    void oneMeaningPerChannel() {
        stored = new ArrayList<>(List.of("3"));
        PlotPickerModel model = new PlotPickerModel(multi(EmptyMeaning.NONE));
        GardenFacts facts = new GardenFacts(7, Map.of(7, 2, 12, -1), menu());
        PlotPickerModel.PlotLook three = model.look(3, facts);
        assertTrue(three.marked());
        assertTrue(three.included());
        assertEquals(1f, three.alpha());
        assertEquals("minecraft:nether_wart", three.itemId());
        assertEquals(0, three.count(), "a read menu shows hypixel's icon without numbers");

        PlotPickerModel.PlotLook seven = model.look(7, facts);
        assertFalse(seven.marked());
        assertEquals(PlotPickerModel.DIMMED, seven.alpha(), "unpicked plots fade once something is picked");
        assertTrue(seven.current());
        assertEquals(2, seven.pests());
        assertTrue(model.tooltip(7, facts).contains("§aYou are here"));
        assertTrue(model.tooltip(7, facts).contains("§cInfested: §f2 pests"));
        assertTrue(model.tooltip(12, facts).contains("§cInfested"));
        assertEquals("§aPlot §7- §bS 4", model.tooltip(4, facts).getFirst(), "custom names come from the menu");
    }

    @Test
    void multiAddsAndRemovesInClickOrder() {
        PlotPickerModel model = new PlotPickerModel(multi(EmptyMeaning.NONE));
        assertTrue(model.click(12, GardenFacts.NONE));
        assertTrue(model.click(3, GardenFacts.NONE));
        assertEquals(List.of("12", "3"), stored);
        assertTrue(model.click(12, GardenFacts.NONE));
        assertEquals(List.of("3"), stored);
        assertFalse(model.click(0, GardenFacts.NONE), "the barn is not offered");
        assertEquals("Plot 3", PlotPickerModel.summary(model.setting()));
        assertTrue(model.useTool(PlotPickerModel.Tool.CLEAR, GardenFacts.NONE));
        assertEquals(List.of(), stored);
        assertEquals("None", PlotPickerModel.summary(model.setting()));
    }

    @Test
    void allMeansEveryPlotUntilOneIsPicked() {
        PlotPickerModel model = new PlotPickerModel(multi(EmptyMeaning.ALL));
        assertTrue(model.allMode());
        assertEquals("All plots", PlotPickerModel.summary(model.setting()));
        PlotPickerModel.PlotLook nine = model.look(9, GardenFacts.NONE);
        assertTrue(nine.included());
        assertFalse(nine.marked());
        assertEquals(1f, nine.alpha());
        assertEquals("minecraft:filled_map", tool(model, PlotPickerModel.Tool.MODE).itemId());

        model.click(9, GardenFacts.NONE);
        assertFalse(model.allMode());
        assertEquals(List.of("9"), stored);
        assertEquals(PlotPickerModel.DIMMED, model.look(4, GardenFacts.NONE).alpha());
        assertEquals("minecraft:hopper", tool(model, PlotPickerModel.Tool.MODE).itemId());

        model.click(9, GardenFacts.NONE);
        assertTrue(model.allMode(), "taking the last plot out shows every plot as included again");

        model.useTool(PlotPickerModel.Tool.MODE, GardenFacts.NONE);
        assertFalse(model.allMode());
        assertEquals(List.of(), stored, "switching to only selected writes nothing until a plot is picked");
        model.click(2, GardenFacts.NONE);
        model.useTool(PlotPickerModel.Tool.MODE, GardenFacts.NONE);
        assertTrue(model.allMode());
        assertEquals(List.of(), stored, "all plots is the empty list");
    }

    @Test
    void orderedPicksOnlyGreenhousesOnceTheMenuIsKnown() {
        GardenFacts facts = new GardenFacts(GardenFacts.UNKNOWN, Map.of(), menu());
        GardenPlotData.install(GardenPlotData.of(facts));
        PlotPickerModel model = new PlotPickerModel(PlotSettings.restrictToGreenhouses(
                new PlotSetting("Plots", Mode.ORDERED, this::read, this::write)));
        assertFalse(model.click(5, facts));
        assertTrue(model.tooltip(5, facts).contains("§cOnly greenhouse plots can be picked."));
        assertTrue(model.click(9, facts));
        assertTrue(model.click(1, facts));
        assertEquals(List.of("9", "1"), stored);
        assertEquals(2, model.look(1, facts).order());
        assertTrue(model.tooltip(1, facts).contains("§aSelected §8#2"));
        assertEquals("Plots 9 › 1", PlotPickerModel.summary(model.setting()));
        model.click(9, facts);
        assertEquals(1, model.look(1, facts).order(), "removing a stop renumbers the rest");

        GardenPlotData.install(null);
        assertTrue(model.click(5, GardenFacts.NONE), "without a menu any plot can be a greenhouse");
    }

    @Test
    void singleTeleportsSkipPlotsYouCannotReach() {
        stored = new ArrayList<>(List.of("0"));
        PlotSetting traps = PlotSetting.single("Pest Traps Plot", () -> stored.getFirst(),
                value -> stored = new ArrayList<>(List.of(value)), "0").allowBarn().emptyMeaning(EmptyMeaning.STAY);
        PlotPickerModel model = new PlotPickerModel(traps);
        GardenFacts facts = new GardenFacts(GardenFacts.UNKNOWN, Map.of(), menu());
        assertEquals("Stay where I am", PlotPickerModel.summary(traps));
        assertFalse(model.click(11, facts), "oak button: not unlocked yet");
        assertFalse(model.click(21, facts), "red pane: locked");
        assertTrue(model.tooltip(21, facts).contains("§cYou don't own this plot yet."));
        assertTrue(model.click(0, facts));
        assertEquals(List.of("barn"), stored);
        assertTrue(model.click(5, facts));
        assertEquals(List.of("5"), stored);
        assertFalse(model.click(5, facts), "picking the picked plot again keeps it");
        assertTrue(model.useTool(PlotPickerModel.Tool.CLEAR, facts));
        assertEquals(List.of("0"), stored, "no plot stays \"0\" for the old consumers");
    }

    @Test
    void rewarpPlotHasNoEmptyStateAndAsksForAPlot() {
        stored = new ArrayList<>(List.of("0"));
        PlotSetting rewarp = PlotSetting.single("Plot Number", () -> stored.getFirst(),
                value -> stored = new ArrayList<>(List.of(value)), "0").allowBarn();
        PlotPickerModel model = new PlotPickerModel(rewarp);
        assertTrue(PlotPickerModel.invalid(rewarp));
        assertEquals("Pick a plot", PlotPickerModel.summary(rewarp));
        assertTrue(model.tools(GardenFacts.NONE).stream().noneMatch(t -> t.tool() == PlotPickerModel.Tool.CLEAR));
        GardenFacts onSix = new GardenFacts(6, Map.of(), null);
        assertTrue(model.useTool(PlotPickerModel.Tool.CURRENT, onSix));
        assertEquals(List.of("6"), stored);
        assertFalse(PlotPickerModel.invalid(rewarp));
        assertEquals("Plot 6", PlotPickerModel.summary(rewarp));
    }

    @Test
    void summariesNameUnknownEntriesAndTheBarn() {
        stored = new ArrayList<>(List.of("Spawn", "barn", " 5"));
        PlotSetting setting = new PlotSetting("x", Mode.MULTI, () -> stored, v -> stored = new ArrayList<>(v)).allowBarn();
        assertEquals("Plots 5, The Barn, unknown: Spawn", PlotPickerModel.summary(setting));
        stored = new ArrayList<>(List.of("12", "3"));
        assertEquals("Plots 3, 12", PlotPickerModel.summary(setting), "lists read in plot order");
    }

    @Test
    void helpExplainsTheEmptyMeaning() {
        List<String> ballsack = tool(new PlotPickerModel(multi(EmptyMeaning.ALL)), PlotPickerModel.Tool.HELP).tooltip();
        assertTrue(ballsack.contains("§7runs on every plot."));
        List<String> leaveOne = tool(new PlotPickerModel(multi(EmptyMeaning.NONE)), PlotPickerModel.Tool.HELP).tooltip();
        assertTrue(leaveOne.contains("§7does nothing."));
        assertEquals("§aHelp", leaveOne.getFirst());
    }

    private PlotPickerModel.ToolLook tool(PlotPickerModel model, PlotPickerModel.Tool tool) {
        return model.tools(GardenFacts.NONE).stream().filter(t -> t.tool() == tool).findFirst().orElseThrow();
    }

    private PlotSetting multi(EmptyMeaning meaning) {
        return new PlotSetting("Plots", Mode.MULTI, this::read, this::write).emptyMeaning(meaning);
    }

    private List<String> read() {
        return stored;
    }

    private void write(List<String> values) {
        stored = new ArrayList<>(values);
    }

    // the user's garden from their screenshot: greenhouses on 1 and 9, wart presets, oak buttons, one locked corner
    static PlotMenuSnapshot menu() {
        Map<Integer, PlotMenuItem> items = new HashMap<>();
        for (int plot = 1; plot <= 24; plot++) {
            items.put(plot, new PlotMenuItem("minecraft:lime_stained_glass_pane", "§aPlot §7- §b" + plot, List.of()));
        }
        items.put(0, new PlotMenuItem("minecraft:spruce_planks", "§aThe Barn", List.of()));
        items.put(1, new PlotMenuItem("minecraft:white_stained_glass", "§aPlot §7- §b1", List.of("§7Greenhouse Plot")));
        items.put(9, new PlotMenuItem("minecraft:white_stained_glass", "§aPlot §7- §b9", List.of("§7Greenhouse Plot")));
        items.put(2, new PlotMenuItem("minecraft:nether_wart", "§aPlot §7- §b2", List.of()));
        items.put(3, new PlotMenuItem("minecraft:nether_wart", "§aPlot §7- §b3", List.of()));
        items.put(4, new PlotMenuItem("minecraft:wheat", "§aPlot §7- §bS 4", List.of()));
        items.put(11, new PlotMenuItem("minecraft:oak_button", "§ePlot §8- §b11", List.of("§7Cost:", "§aCompost §8x2")));
        items.put(21, new PlotMenuItem("minecraft:red_stained_glass_pane", "§ePlot §8- §b21", List.of("§7Cost:")));
        return new PlotMenuSnapshot(1L, items);
    }
}
