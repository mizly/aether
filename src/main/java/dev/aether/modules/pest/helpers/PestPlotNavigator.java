package dev.aether.modules.pest.helpers;

import dev.aether.modules.pest.PestManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.CommandUtils;
import dev.aether.util.GardenPlots;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

final class PestPlotNavigator {
    // Quadrant sweep of a 96x96 garden plot, measured from the plot-centre anchor.
    private static final double[][] SCAN_OFFSETS = {
            {30.0, 30.0},
            {-30.0, 30.0},
            {-30.0, -30.0},
            {30.0, -30.0},
    };
    // Leaving the plot makes the tab-list plot check teleport us back, so sweeps stay well inside it.
    private static final double SCAN_EDGE_INSET = 8.0;
    private static final double TARGET_PLOT_MARGIN = 3.0;

    private PestPlotNavigator() {
    }

    static int scanPointCount() {
        return SCAN_OFFSETS.length;
    }

    /** Next plot sweep point, or null once the whole plot has been covered. */
    static Vec3 nextScanWaypoint(Minecraft client, PestNavigationState navigationState) {
        if (client == null || client.player == null) {
            return null;
        }
        GardenPlots.Bounds bounds = currentPlotBounds(client, navigationState);
        if (navigationState.plotAnchor == null) {
            navigationState.plotAnchor = bounds != null
                    ? new Vec3(bounds.centerX(), client.player.getY(), bounds.centerZ())
                    : client.player.position();
        }
        if (navigationState.scanPointIdx >= SCAN_OFFSETS.length) {
            return null;
        }
        double[] offset = SCAN_OFFSETS[navigationState.scanPointIdx++];
        Vec3 anchor = navigationState.plotAnchor;
        double x = anchor.x + offset[0];
        double z = anchor.z + offset[1];
        if (bounds != null) {
            if (!bounds.contains(client.player.getX(), client.player.getZ(), TARGET_PLOT_MARGIN)) {
                ClientUtils.sendDebugMessage("[PestDestroyer] Sweeping plot "
                        + getEffectivePlot(client, navigationState) + " from outside its bounds.");
            }
            x = bounds.clampX(x, SCAN_EDGE_INSET);
            z = bounds.clampZ(z, SCAN_EDGE_INSET);
        }
        // Sweep at the current altitude so a roof AOTV's vantage point is not thrown away.
        return new Vec3(x, client.player.getY(), z);
    }

    /** Bounds of the plot being cleaned, or null when the plot number is unknown. */
    static GardenPlots.Bounds currentPlotBounds(Minecraft client, PestNavigationState navigationState) {
        String plot = getEffectivePlot(client, navigationState);
        return PestPlotId.isUsable(plot) ? GardenPlots.boundsForPlot(plot) : null;
    }

    /**
     * Pests on a neighbouring plot are in entity range but chasing them leaves the
     * plot, which the tab-list plot check answers with a teleport back.
     */
    static Predicate<Entity> currentPlotFilter(
            Minecraft client, PestNavigationState navigationState) {
        GardenPlots.Bounds bounds = currentPlotBounds(client, navigationState);
        if (bounds == null) {
            return entity -> true;
        }
        return entity -> entity != null
                && bounds.contains(entity.getX(), entity.getZ(), TARGET_PLOT_MARGIN);
    }

    static String getNextPlotTarget(PestNavigationState navigationState) {
        if (navigationState.plotQueue.isEmpty() || navigationState.currentPlotIdx >= navigationState.plotQueue.size()) {
            return null;
        }
        return navigationState.plotQueue.get(navigationState.currentPlotIdx);
    }

    static boolean tryNextPlot(Minecraft client, PestNavigationState navigationState) {
        Set<String> infested = filterSkippedPlots(
                PestManager.getInfestedPlotsFromTab(client), navigationState);
        if (infested.isEmpty()) {
            return false;
        }

        navigationState.plotQueue.clear();
        navigationState.plotQueue.addAll(infested);
        navigationState.currentPlotIdx = 0;

        String firstPlot = navigationState.plotQueue.get(0);
        String currentPlot = getEffectivePlot(client, navigationState);
        if (!plotsEqual(firstPlot, currentPlot)) {
            navigationState.plotTpSent = false;
            navigationState.getLocationAttempts = 0;
            navigationState.waypointCycleCount = 0;
            ClientUtils.sendDebugMessage("[PestDestroyer] Moving to new first infested plot: " + firstPlot);
            return true;
        }

        return false;
    }

    static String getEffectivePlot(Minecraft client, PestNavigationState navigationState) {
        if (navigationState.trustedPlot != null
                && System.currentTimeMillis() < navigationState.trustedPlotExpiresAt) {
            return navigationState.trustedPlot;
        }

        String score = ClientUtils.getCurrentPlot();
        if (score != null && !score.equals("Unknown")) {
            return score;
        }

        String freshPlotChat = CommandUtils.getFreshKnownPlotChat();
        if (freshPlotChat != null) {
            return freshPlotChat;
        }

        return "Unknown";
    }

    static boolean plotsEqual(String first, String second) {
        return PestPlotId.equals(first, second);
    }

    private static Set<String> filterSkippedPlots(Set<String> plots, PestNavigationState navigationState) {
        Set<String> filtered = new LinkedHashSet<>();
        for (String plot : plots) {
            if (!navigationState.leaveOneSkippedPlots.contains(PestPlotId.normalize(plot))) {
                filtered.add(plot);
            }
        }
        return filtered;
    }

}
