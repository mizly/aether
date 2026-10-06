package dev.aether.ui.orbit;

import dev.aether.Aether;
import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.GardenPlots;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.minecraft.client.Minecraft;

// keeps the plot pictures up to date while you play. the client only ever holds the chunks the server sends near
// you, so every chunk that arrives is photographed into its plot's picture and kept; walking or warping round the
// garden fills them all in over time. reads blocks only
public final class GardenRecorder {
    private static final long PLOT_STALE_MS = 60_000L;
    private static final int PLOTS = 25;

    private static int ticks;
    private static boolean garden;
    private static int nextPlot;
    private static final boolean[] dirty = new boolean[PLOTS];
    private static boolean registered;
    private static boolean warned;

    private GardenRecorder() {
    }

    public static void register() {
        if (registered) return;
        registered = true;
        // a chunk's blocks are in place once it has loaded; the next pass samples it
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
            int plot = GardenPlots.plotAt(chunk.getPos().getMinBlockX() + 8, chunk.getPos().getMinBlockZ() + 8);
            if (plot >= 0) dirty[plot] = true;
        });
    }

    public static void tick(Minecraft client) {
        if (client.level == null || client.player == null) return;
        // only the 3d menu shows the pictures, and a running macro shouldn't pay for them
        if (AetherConfig.TRADITIONAL_GUI.get() || !AetherConfig.RECORD_GARDEN_PLOTS.get()
                || MacroStateManager.getCurrentState() != MacroState.State.OFF) {
            return;
        }
        try {
            record(client);
        } catch (RuntimeException e) {
            // a picture is never worth a crash; the plot is tried again on a later pass
            if (!warned) Aether.LOGGER.warn("Garden recorder skipped a pass", e);
            warned = true;
        }
    }

    private static void record(Minecraft client) {
        ticks++;
        if (ticks % 60 == 0) garden = ClientUtils.getCurrentLocation() == MacroState.Location.GARDEN;
        if (!garden || ticks % 10 != 0) return;
        if (ticks % 200 == 0 && BarnCopy.refresh(client)) return;
        // freshly loaded chunks first, then whatever plot is loaded and getting old; one plot a pass keeps it cheap
        for (int plot = 0; plot < PLOTS; plot++) {
            if (dirty[plot] && PlotMiniatures.record(client, plot)) {
                dirty[plot] = false;
                return;
            }
            dirty[plot] = false;
        }
        for (int tries = 0; tries < PLOTS; tries++) {
            int plot = nextPlot;
            nextPlot = (nextPlot + 1) % PLOTS;
            if (PlotMiniatures.age(plot) > PLOT_STALE_MS && PlotMiniatures.record(client, plot)) return;
        }
    }
}
