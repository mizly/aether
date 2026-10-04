package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.ui.gui.TestConfigDir;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

// the plot picker writes "0" for no trap or junk plot and shows legacy roof entries as the plot they name
class PlotConsumerGuardsTest {
    @BeforeAll
    static void configDir() {
        TestConfigDir.ensure();
    }

    @AfterEach
    void reset() {
        AetherConfig.AOTV_ROOF_PLOTS.set(List.of());
    }

    @Test
    void noPlotIsNeverATeleportTarget() {
        assertFalse(PestPlotId.isUsable("0"));
        assertFalse(PestPlotId.isUsable(""));
        assertFalse(PestPlotId.isUsable("  "));
        assertTrue(PestPlotId.isUsable("barn"));
        assertTrue(PestPlotId.isUsable("24"));
    }

    @Test
    void roofPlotsMatchLegacyEntriesLikeThePickerShowsThem() throws Exception {
        Method onPlot = PestDestroyer.class.getDeclaredMethod("shouldAotvToRoofOnPlot", String.class);
        onPlot.setAccessible(true);
        assertEquals(true, onPlot.invoke(null, "5"), "an empty list is every plot");
        AetherConfig.AOTV_ROOF_PLOTS.set(List.of("Plot 5", " 12"));
        assertEquals(true, onPlot.invoke(null, "5"));
        assertEquals(true, onPlot.invoke(null, "12"));
        assertEquals(false, onPlot.invoke(null, "6"));
        assertEquals(false, onPlot.invoke(null, (Object) null));
    }
}
