package dev.aether.ui.gui.plot;

import dev.aether.ui.settings.PlotToken;

import java.util.HashMap;
import java.util.Map;

// one frame's read of the garden: the plot you stand on (0 = barn, -1 unknown), pests by plot (-1 = infested,
// count unknown) and the last Configure Plots menu read on this profile with its slots parsed, or null
public record GardenFacts(int currentPlot, Map<Integer, Integer> pests, PlotMenuSnapshot menu,
                          Map<Integer, PlotInfo> infos) {
    public static final int UNKNOWN = GardenPlotData.UNKNOWN_PLOT;
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
        infos = infos == null ? Map.of() : Map.copyOf(infos);
    }

    public GardenFacts(int currentPlot, Map<Integer, Integer> pests, PlotMenuSnapshot menu) {
        this(currentPlot, pests, menu, parse(menu));
    }

    // asks each source once, so a frame never mixes two different reads
    public static GardenFacts read(GardenPlotData data) {
        int current = data.currentPlot();
        Map<Integer, Integer> pests = new HashMap<>();
        for (Integer plot : data.infestedPlots()) {
            pests.put(plot, -1);
        }
        int here = data.pestCount();
        if (current > PlotToken.BARN && here > 0) {
            pests.put(current, here);
        }
        return new GardenFacts(current, pests, data.snapshot());
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
        return infos.get(plot);
    }

    public boolean onGarden() {
        return currentPlot != UNKNOWN;
    }

    private static Map<Integer, PlotInfo> parse(PlotMenuSnapshot menu) {
        Map<Integer, PlotInfo> infos = new HashMap<>();
        if (menu != null) {
            menu.plots().forEach((plot, slot) -> infos.put(plot, PlotInfo.of(plot, slot)));
        }
        return infos;
    }
}
