package dev.aether.ui.gui.plot;

import dev.aether.ui.settings.PlotToken;

import java.util.HashMap;
import java.util.Map;

// what the picker knows about the garden at one moment: the plot you stand on (0 = barn, -1 unknown), pests
// by plot (-1 = infested, count unknown) and the last Configure Plots menu read on this profile, or null
public record GardenFacts(int currentPlot, Map<Integer, Integer> pests, PlotMenuSnapshot menu) {
    public static final int UNKNOWN = -1;
    public static final GardenFacts NONE = new GardenFacts(UNKNOWN, Map.of(), null);

    public GardenFacts {
        Map<Integer, Integer> copy = new HashMap<>();
        if (pests != null) {
            pests.forEach((plot, count) -> {
                if (plot != null && count != null && count != 0) {
                    copy.put(plot, count);
                }
            });
        }
        pests = Map.copyOf(copy);
        if (currentPlot < PlotToken.BARN || currentPlot > PlotToken.MAX_PLOT) {
            currentPlot = UNKNOWN;
        }
    }

    public boolean infested(int plot) {
        return pests.containsKey(plot);
    }

    public int pestCount(int plot) {
        return pests.getOrDefault(plot, 0);
    }

    public boolean hasMenu() {
        return menu != null;
    }

    public PlotInfo info(int plot) {
        return menu == null ? null : menu.info(plot);
    }

    public boolean onGarden() {
        return currentPlot != UNKNOWN;
    }
}
