package dev.aether.ui.gui.plot;

import java.util.Set;

// live garden knowledge for the plot picker; NONE is what a fresh install and the preview harness see
public interface GardenPlotData {
    GardenPlotData NONE = new GardenPlotData() {
    };

    int UNKNOWN_PLOT = -1;

    // plot number, 0 for the barn, UNKNOWN_PLOT outside the garden
    default int currentPlot() {
        return UNKNOWN_PLOT;
    }

    // plots the tab list reports pests on
    default Set<Integer> infestedPlots() {
        return Set.of();
    }

    // pests on the current plot, from the sidebar; other plots' counts are never shown anywhere
    default int pestCount() {
        return 0;
    }

    // the Configure Plots menu as last read, or null until the player opens it once
    default PlotMenuSnapshot snapshot() {
        return null;
    }
}
