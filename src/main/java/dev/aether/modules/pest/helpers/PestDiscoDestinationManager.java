package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
/**
 * Resolves the hold-destination plot: a plot the destroyer teleports to, holds
 * position on, and finishes on after a single kill. Disco holds at the plot-TP
 * landing spot; discoless etherwarps once off that spot first. Disco wins when
 * both modes are enabled.
 */
public final class PestDiscoDestinationManager {

    public enum Mode {
        NONE,
        DISCO,
        DISCOLESS
    }

    private PestDiscoDestinationManager() {
    }

    public static Mode getMode() {
        if (AetherConfig.PEST_DISCO_DESTINATION_MODE.get()
                && isUsablePlot(AetherConfig.PEST_DISCO_DESTINATION_PLOT.get())) {
            return Mode.DISCO;
        }
        if (AetherConfig.PEST_DISCOLESS_MODE.get()
                && isUsableDiscolessPlots(AetherConfig.PEST_DISCOLESS_PLOT.get())) {
            return Mode.DISCOLESS;
        }
        return Mode.NONE;
    }

    public static boolean isEnabled() {
        return isConfigured();
    }

    public static boolean isConfigured() {
        return getMode() != Mode.NONE;
    }

    /** Discoless must fire one etherwarp off the plot-TP landing spot before holding. */
    public static boolean requiresEtherwarpEntry() {
        return getMode() == Mode.DISCOLESS;
    }

    public static String getConfiguredPlot() {
        return switch (getMode()) {
            case DISCO -> normalizePlot(AetherConfig.PEST_DISCO_DESTINATION_PLOT.get());
            case DISCOLESS -> "";
            case NONE -> "";
        };
    }

    public static boolean matchesPlot(String plot) {
    if (getMode() == Mode.DISCOLESS) {
        String normalizedPlot = normalizePlot(plot);

        for (String configuredPlot : normalizePlotForDiscoless(AetherConfig.PEST_DISCOLESS_PLOT.get())) {
            if (normalizedPlot.equals(configuredPlot)) {
                return true;
            }
        }

        return false;
    }
    return isConfigured() && normalizePlot(plot).equals(getConfiguredPlot());
}
    public static boolean shouldForcePlotTeleport(String plot) {
        return matchesPlot(plot);
    }

    public static LinkedHashSet<String> prioritizePlots(Set<String> plots) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        if (plots == null || plots.isEmpty()) {
            return ordered;
        }

        String discoPlot = getConfiguredPlot();
        if (isConfigured() && containsPlot(plots, discoPlot)) {
            ordered.add(discoPlot);
        }

        for (String plot : plots) {
            String normalized = normalizePlot(plot);
            if (isUsablePlot(normalized)) {
                ordered.add(normalized);
            }
        }
        return ordered;
    }

    public static String selectPrimaryPlot(Set<String> plots, String fallback) {
        LinkedHashSet<String> prioritized = prioritizePlots(plots);
        if (!prioritized.isEmpty()) {
            return prioritized.iterator().next();
        }
        String normalizedFallback = normalizePlot(fallback);
        return isUsablePlot(normalizedFallback) ? normalizedFallback : null;
    }

    public static boolean isUsablePlot(String plot) {
        String normalized = normalizePlot(plot);
        return !normalized.isEmpty() && !normalized.equals("0") && !normalized.equalsIgnoreCase("unknown");
    }

    private static boolean containsPlot(Set<String> plots, String targetPlot) {
        if (targetPlot == null || targetPlot.isBlank()) {
            return false;
        }
        for (String plot : plots) {
            if (normalizePlot(plot).equals(targetPlot)) {
                return true;
            }
        }
        return false;
    }

    public static String normalizePlot(String plot) {
        if (plot == null) {
            return "";
        }
        String digits = plot.trim().replaceAll("\\D", "");
        return digits.isEmpty() ? plot.trim() : digits;
    }
    
    public static List<String> normalizePlotForDiscoless(List<String> plotdiscoless) {
    List<String> normalizedPlots = new ArrayList<>();

    for (String plot : plotdiscoless) {
        if (plot == null) {
            continue;
        }
        String digits = plot.trim().replaceAll("\\D", "");
        if (digits.isEmpty() || digits.equals("0")) {
            continue;
        }
        normalizedPlots.add(digits);
        }

    return normalizedPlots;
    }

    public static boolean isUsableDiscolessPlots(List<String> plots) {
        List<String> normalized = normalizePlotForDiscoless(plots);
        return !normalized.isEmpty();
    }
}
    
    