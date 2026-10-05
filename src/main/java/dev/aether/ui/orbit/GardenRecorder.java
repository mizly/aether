package dev.aether.ui.orbit;

import dev.aether.macro.MacroState;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;

// keeps the plot pictures up to date while you play: each plot is photographed whenever it is loaded and its
// picture is old. reads blocks only
public final class GardenRecorder {
    private static final long PLOT_STALE_MS = 45_000L;
    private static final int PLOTS = 25;

    private static int ticks;
    private static boolean garden;
    private static int nextPlot;

    private GardenRecorder() {
    }

    public static void tick(Minecraft client) {
        if (client.level == null || client.player == null) return;
        ticks++;
        if (ticks % 60 == 0) garden = ClientUtils.getCurrentLocation() == MacroState.Location.GARDEN;
        if (!garden || ticks % 20 != 0) return;
        // one plot a second keeps the cost out of sight
        for (int tries = 0; tries < PLOTS; tries++) {
            int plot = nextPlot;
            nextPlot = (nextPlot + 1) % PLOTS;
            if (PlotMiniatures.age(plot) > PLOT_STALE_MS && PlotMiniatures.record(client, plot)) return;
        }
    }
}
