package dev.aether.ui.gui.plot;

import dev.aether.ui.settings.PlotToken;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

// the Configure Plots menu as last read on one skyblock profile: the 24 plots and the barn by plot number
public final class PlotMenuSnapshot {
    private final long capturedAt;
    private final Map<Integer, PlotMenuItem> items;
    private final Map<Integer, PlotInfo> infos = new TreeMap<>();

    public PlotMenuSnapshot(long capturedAt, Map<Integer, PlotMenuItem> items) {
        this.capturedAt = capturedAt;
        Map<Integer, PlotMenuItem> copy = new TreeMap<>();
        items.forEach((plot, item) -> {
            if (plot != null && item != null && plot >= PlotToken.BARN && plot <= PlotToken.MAX_PLOT) {
                copy.put(plot, item);
                infos.put(plot, PlotInfo.of(plot, item));
            }
        });
        this.items = Collections.unmodifiableMap(copy);
    }

    public long capturedAt() {
        return capturedAt;
    }

    public Map<Integer, PlotMenuItem> items() {
        return items;
    }

    // null when that slot was not read
    public PlotInfo info(int plot) {
        return infos.get(plot);
    }

    // every plot slot and the barn held an item, so the menu had finished loading
    public boolean complete() {
        return items.size() == PlotToken.MAX_PLOT + 1;
    }

    public boolean isGreenhouse(int plot) {
        PlotInfo info = infos.get(plot);
        return info != null && info.status() == PlotStatus.GREENHOUSE;
    }

    public boolean sameItems(PlotMenuSnapshot other) {
        return other != null && items.equals(other.items);
    }
}
