package dev.aether.modules.pest.helpers;

import dev.aether.modules.pest.PestManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.CommandUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashSet;
import java.util.Set;

final class PestPlotNavigator {
    private PestPlotNavigator() {
    }

    static void onFireworkParticle(PestNavigationState navigationState, double x, double y, double z) {
        if (!navigationState.isCapturingFirework) {
            return;
        }
        Vec3 pos = new Vec3(x, y, z);
        if (navigationState.fireworkFirstPos == null) {
            navigationState.fireworkFirstPos = pos;
        }
        navigationState.fireworkLastPos = pos;
        navigationState.fireworkParticleCount++;
    }

    static Vec3 calculateWaypoint(PestNavigationState navigationState, double extrapolateDistance) {
        if (navigationState.fireworkFirstPos == null
                || navigationState.fireworkLastPos == null
                || navigationState.fireworkParticleCount < 2) {
            return null;
        }
        Vec3 direction = navigationState.fireworkLastPos.subtract(navigationState.fireworkFirstPos);
        if (direction.lengthSqr() < 0.01) {
            return null;
        }
        direction = direction.normalize();
        return navigationState.fireworkLastPos.add(direction.scale(extrapolateDistance));
    }

    static String getNextPlotTarget(PestNavigationState navigationState) {
        if (navigationState.plotQueue.isEmpty() || navigationState.currentPlotIdx >= navigationState.plotQueue.size()) {
            return null;
        }
        return navigationState.plotQueue.get(navigationState.currentPlotIdx);
    }

    static boolean tryNextPlot(Minecraft client, PestNavigationState navigationState) {
        Set<String> infested = filterSkippedPlots(
                PestDiscoDestinationManager.prioritizePlots(PestManager.getInfestedPlotsFromTab(client)),
                navigationState);
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

    @Deprecated
    static boolean tryNextPlotExcluding(Minecraft client, PestNavigationState navigationState, String currentPlot) {
        Set<String> infested = filterSkippedPlots(
                PestDiscoDestinationManager.prioritizePlots(PestManager.getInfestedPlotsFromTab(client)),
                navigationState);
        if (infested.isEmpty()) {
            return false;
        }

        for (String plot : infested) {
            if (!plotsEqual(plot, currentPlot)) {
                navigationState.plotQueue.clear();
                navigationState.plotQueue.addAll(infested);
                navigationState.currentPlotIdx = navigationState.plotQueue.indexOf(plot);
                navigationState.plotTpSent = false;
                navigationState.getLocationAttempts = 0;
                navigationState.waypointCycleCount = 0;
                ClientUtils.sendDebugMessage("[PestDestroyer] Skipping to next available plot: " + plot);
                return true;
            }
        }
        return false;
    }

    static String getEffectivePlot(Minecraft client, PestNavigationState navigationState) {
        if (navigationState.trustedPlot != null
                && System.currentTimeMillis() < navigationState.trustedPlotExpiresAt) {
            return navigationState.trustedPlot;
        }

        String score = ClientUtils.getCurrentPlot(client);
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
        return normalizePlot(first).equals(normalizePlot(second));
    }

    private static Set<String> filterSkippedPlots(Set<String> plots, PestNavigationState navigationState) {
        Set<String> filtered = new LinkedHashSet<>();
        for (String plot : plots) {
            if (!navigationState.leaveOneSkippedPlots.contains(normalizePlot(plot))) {
                filtered.add(plot);
            }
        }
        return filtered;
    }

    private static String normalizePlot(String plot) {
        if (plot == null) {
            return "";
        }
        String normalized = plot.trim().toLowerCase();
        String digits = normalized.replaceAll("\\D", "");
        return digits.isEmpty() ? normalized : digits;
    }
}
