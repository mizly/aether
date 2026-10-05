package dev.aether.ui.gui.plot;

import dev.aether.config.AetherConfig;
import dev.aether.ui.gui.TestConfigDir;
import dev.aether.ui.settings.PlotSetting;
import dev.aether.ui.settings.PlotToken;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlotSettingsTest {
    @BeforeAll
    static void configDir() {
        TestConfigDir.ensure();
    }

    @Test
    void teleportTargetsKeepZeroAsNoPlot() {
        AetherConfig.PEST_TRAPS_PLOT.set("0");
        PlotSetting traps = PlotSettings.teleport("Pest Traps Plot", AetherConfig.PEST_TRAPS_PLOT);
        assertTrue(traps.isEmpty());
        assertEquals(PlotSetting.EmptyMeaning.STAY, traps.emptyMeaning());
        assertTrue(traps.allowsBarn());
        assertEquals(List.of(AetherConfig.PEST_TRAPS_PLOT), traps.bindings());

        AetherConfig.PEST_TRAPS_PLOT.set(" Plot 5");
        assertEquals(List.of(PlotToken.plot(5)), traps.selection(), "legacy text reads as the plot the consumer matches");
        assertEquals(" Plot 5", AetherConfig.PEST_TRAPS_PLOT.get(), "reading never rewrites");
        traps.toggle(PlotToken.barn());
        assertEquals("barn", AetherConfig.PEST_TRAPS_PLOT.get());
        traps.clear();
        assertEquals("0", AetherConfig.PEST_TRAPS_PLOT.get());
    }

    @Test
    void listsWriteBareDigitsAndKeepUnknownEntries() {
        AetherConfig.AOTV_ROOF_PLOTS.set(List.of("Plot 5", "roof"));
        PlotSetting roof = PlotSettings.list("AOTV Roof Plots", PlotSetting.Mode.MULTI, AetherConfig.AOTV_ROOF_PLOTS,
                PlotSetting.EmptyMeaning.ALL);
        assertFalse(roof.allowsBarn());
        roof.toggle(PlotToken.plot(12));
        assertEquals(List.of("5", "roof", "12"), AetherConfig.AOTV_ROOF_PLOTS.get());
        roof.remove(PlotToken.unknown("roof"));
        assertEquals(List.of("5", "12"), AetherConfig.AOTV_ROOF_PLOTS.get());
        roof.clear();
        assertEquals(List.of(), AetherConfig.AOTV_ROOF_PLOTS.get(), "empty is every plot for the roof list");
    }

    @Test
    void greenhousesAreOrderedAndTitledForTheMenu() {
        AetherConfig.GREENHOUSE_PLOTS.set(List.of());
        PlotSetting greenhouses = PlotSettings.greenhouses("Plots", AetherConfig.GREENHOUSE_PLOTS);
        assertEquals(PlotSetting.Mode.ORDERED, greenhouses.mode());
        assertEquals("Greenhouse Plots", greenhouses.menuTitle());
        greenhouses.toggle(PlotToken.plot(9));
        greenhouses.toggle(PlotToken.plot(1));
        assertEquals(List.of("9", "1"), AetherConfig.GREENHOUSE_PLOTS.get());
    }
}
