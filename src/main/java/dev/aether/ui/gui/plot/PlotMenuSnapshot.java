package dev.aether.ui.gui.plot;

import java.util.List;
import java.util.Map;

// the Configure Plots chest as last read, slot contents by plot number with the barn at 0
public record PlotMenuSnapshot(Map<Integer, Slot> plots, long readAtMillis) {
    public PlotMenuSnapshot {
        plots = Map.copyOf(plots);
    }

    public Slot slot(int plot) {
        return plots.get(plot);
    }

    // item id as "minecraft:lime_stained_glass_pane", name and lore with their formatting codes
    public record Slot(String itemId, String name, List<String> lore) {
        public Slot {
            lore = List.copyOf(lore);
        }
    }
}
