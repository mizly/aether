package dev.aether.ui.gui.plot;

import dev.aether.ui.settings.PlotToken;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

// the Configure Plots chest as last read, slot contents by plot number with the barn at 0
public record PlotMenuSnapshot(Map<Integer, Slot> plots, long readAtMillis) {
    public PlotMenuSnapshot {
        Map<Integer, Slot> copy = new TreeMap<>();
        plots.forEach((plot, slot) -> {
            if (plot != null && slot != null && plot >= PlotToken.BARN && plot <= PlotToken.MAX_PLOT) {
                copy.put(plot, slot);
            }
        });
        plots = Map.copyOf(copy);
    }

    public Slot slot(int plot) {
        return plots.get(plot);
    }

    // null when that slot was not read
    public PlotInfo info(int plot) {
        Slot slot = plots.get(plot);
        return slot == null ? null : PlotInfo.of(plot, slot);
    }

    // every plot slot and the barn held an item, so the menu had finished loading
    public boolean complete() {
        return plots.size() == PlotToken.MAX_PLOT + 1;
    }

    public boolean isGreenhouse(int plot) {
        PlotInfo info = info(plot);
        return info != null && info.status() == PlotStatus.GREENHOUSE;
    }

    public boolean sameSlots(PlotMenuSnapshot other) {
        return other != null && plots.equals(other.plots);
    }

    // item id as "minecraft:lime_stained_glass_pane", name and lore with their formatting codes
    public record Slot(String itemId, String name, List<String> lore) {
        public Slot {
            itemId = itemId == null ? "minecraft:air" : itemId.trim().toLowerCase(Locale.ROOT);
            if (itemId.indexOf(':') < 0) {
                itemId = "minecraft:" + itemId;
            }
            name = name == null ? "" : name;
            lore = lore == null ? List.of() : List.copyOf(lore);
        }
    }
}
