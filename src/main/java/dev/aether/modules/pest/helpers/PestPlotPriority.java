package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Decides which infested plot the destroyer works next. Plot order is a user
 * choice: finish the plot we are standing on before travelling, or clear every
 * other plot first and come back. Both orderings exist to stop the cleaner
 * bouncing between plots while pests are still loaded around it.
 */
final class PestPlotPriority {
    // A plot the tab list still calls infested but that we have swept this many
    // times without finding anything is treated as unreachable, so the run can
    // move on instead of sweeping it forever.
    static final int MAX_CURRENT_PLOT_HOLDS = 3;

    private PestPlotPriority() {
    }

    static boolean otherPlotsFirst() {
        return AetherConfig.PEST_PLOT_PRIORITY.get() == 1;
    }

    static List<String> order(Collection<String> infested, String currentPlot) {
        return order(infested, currentPlot, otherPlotsFirst());
    }

    static List<String> order(
            Collection<String> infested, String currentPlot, boolean otherPlotsFirst) {
        List<String> current = new ArrayList<>();
        List<String> others = new ArrayList<>();
        if (infested != null) {
            for (String plot : infested) {
                if (plot == null) {
                    continue;
                }
                if (PestPlotId.equals(plot, currentPlot)) {
                    current.add(plot);
                } else {
                    others.add(plot);
                }
            }
        }

        List<String> ordered = new ArrayList<>(current.size() + others.size());
        if (otherPlotsFirst) {
            ordered.addAll(others);
            ordered.addAll(current);
        } else {
            ordered.addAll(current);
            ordered.addAll(others);
        }
        return ordered;
    }

    /**
     * Whether the run must stay on the plot it is standing on. Only the
     * current-plot-first ordering holds, and only while the tab list still
     * reports pests here and the plot has not been swept fruitlessly too often.
     */
    static boolean shouldHoldCurrentPlot(
            Collection<String> infested,
            String currentPlot,
            boolean otherPlotsFirst,
            int holdSweeps) {
        if (otherPlotsFirst || !PestPlotId.isUsable(currentPlot)) {
            return false;
        }
        if (holdSweeps >= MAX_CURRENT_PLOT_HOLDS) {
            return false;
        }
        return contains(infested, currentPlot);
    }

    static boolean contains(Collection<String> plots, String plot) {
        if (plots == null) {
            return false;
        }
        for (String candidate : plots) {
            if (PestPlotId.equals(candidate, plot)) {
                return true;
            }
        }
        return false;
    }
}
