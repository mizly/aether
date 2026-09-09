package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.pest.PestManager;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Encapsulates the optional "leave one pest alive" plot policy. */
final class PestLeaveOneController {
    private static final Pattern PLOT_PEST_COUNT =
            Pattern.compile("(?i)\\bplot\\s*[-:#]?\\s*(\\d+)\\D+?x\\s*(\\d+)\\b");

    interface Context {
        String getEffectivePlot(Minecraft client);

        void setState(PestDestroyer.State state);
    }

    private PestLeaveOneController() {
    }

    static boolean shouldFinishForAliveCount(
            Minecraft client,
            PestDestroyerRuntime runtime,
            int aliveCount) {
        if (aliveCount < 0) {
            return false;
        }
        if (aliveCount == 0) {
            runtime.navigation.leaveOneSkippedPlots.clear();
            return true;
        }
        if (!AetherConfig.LEAVE_ONE_PEST_ALIVE.get()) {
            resetTracking(runtime);
            return false;
        }
        pruneRememberedPlots(runtime);
        return shouldFinishForCounts(
                aliveCount, runtime.navigation.leaveOneSkippedPlots.size());
    }

    static boolean tryLeaveCurrentPlot(
            Minecraft client,
            PestDestroyerRuntime runtime,
            Context context) {
        if (!AetherConfig.LEAVE_ONE_PEST_ALIVE.get()) {
            return false;
        }

        String currentPlot = context.getEffectivePlot(client);
        if (!isWhitelistedPlot(currentPlot)) {
            resetTracking(runtime);
            return false;
        }

        String normalized = PestPlotId.normalize(currentPlot);
        if (runtime.navigation.leaveOneSkippedPlots.contains(normalized)) {
            return false;
        }

        int plotPests = parsePlotPestCount(ClientUtils.getSidebarLines(), currentPlot);
        if (!normalized.equals(runtime.navigation.leaveOneTrackedPlot)) {
            runtime.navigation.leaveOneTrackedPlot = normalized;
            runtime.navigation.leaveOneRemainingKills = -1;
            runtime.navigation.leaveOneUnbudgetedKills = 0;
            runtime.navigation.leaveOneReservedEntityId = -1;
        }
        if (runtime.navigation.leaveOneRemainingKills < 0 && plotPests > 0) {
            runtime.navigation.leaveOneRemainingKills = remainingKillBudget(
                    plotPests, runtime.navigation.leaveOneUnbudgetedKills);
            ClientUtils.sendDebugMessage(
                    "PestDestroyer: Plot "
                            + currentPlot
                            + " leave-one kill budget is "
                            + runtime.navigation.leaveOneRemainingKills
                            + " after "
                            + runtime.navigation.leaveOneUnbudgetedKills
                            + " early kill(s).");
        }

        return (plotPests == 1 || runtime.navigation.leaveOneRemainingKills == 0)
                && finishCurrentPlot(client, runtime, context, currentPlot);
    }

    static boolean recordTrackedKill(
            Minecraft client,
            PestDestroyerRuntime runtime,
            Context context) {
        String currentPlot = context.getEffectivePlot(client);
        if (!isWhitelistedPlot(currentPlot)) {
            return false;
        }
        String normalized = PestPlotId.normalize(currentPlot);
        if (!normalized.equals(runtime.navigation.leaveOneTrackedPlot)) {
            runtime.navigation.leaveOneTrackedPlot = normalized;
            runtime.navigation.leaveOneRemainingKills = -1;
            runtime.navigation.leaveOneUnbudgetedKills = 0;
            runtime.navigation.leaveOneReservedEntityId = -1;
        }
        if (runtime.navigation.leaveOneRemainingKills < 0) {
            runtime.navigation.leaveOneUnbudgetedKills++;
            return false;
        }
        runtime.navigation.leaveOneRemainingKills =
                consumeKillBudget(runtime.navigation.leaveOneRemainingKills);
        return runtime.navigation.leaveOneRemainingKills == 0
                && finishCurrentPlot(client, runtime, context, currentPlot);
    }

    static boolean isTrackingPlot(PestDestroyerRuntime runtime, String plot) {
        return runtime.navigation.leaveOneRemainingKills >= 0
                && PestPlotId.equals(runtime.navigation.leaveOneTrackedPlot, plot);
    }

    static Set<String> filterSkippedPlots(
            PestDestroyerRuntime runtime,
            Set<String> infested) {
        pruneRememberedPlots(runtime);
        Set<String> filtered = new LinkedHashSet<>();
        for (String plot : infested) {
            if (!runtime.navigation.leaveOneSkippedPlots.contains(PestPlotId.normalize(plot))) {
                filtered.add(plot);
            }
        }
        return filtered;
    }

    static void forgetPlot(PestDestroyerRuntime runtime, String plot) {
        String normalized = PestPlotId.normalize(plot);
        runtime.navigation.leaveOneSkippedPlots.remove(normalized);
        if (normalized.equals(runtime.navigation.leaveOneTrackedPlot)) {
            resetTracking(runtime);
        }
    }

    static void clearRememberedPlots(PestDestroyerRuntime runtime) {
        runtime.navigation.leaveOneSkippedPlots.clear();
        resetTracking(runtime);
    }

    static boolean shouldFinishForCounts(int aliveCount, int rememberedPlotCount) {
        return aliveCount == 0 || rememberedPlotCount > 0 && aliveCount <= rememberedPlotCount;
    }

    static int parsePlotPestCount(List<String> lines, String plot) {
        String normalizedPlot = PestPlotId.normalize(plot);
        for (String line : lines) {
            Matcher matcher = PLOT_PEST_COUNT.matcher(line);
            if (matcher.find() && PestPlotId.normalize(matcher.group(1)).equals(normalizedPlot)) {
                return Integer.parseInt(matcher.group(2));
            }
        }
        return -1;
    }

    static int killBudgetForCount(int pestCount) {
        return pestCount > 0 ? pestCount - 1 : -1;
    }

    static int consumeKillBudget(int remainingKills) {
        return remainingKills > 0 ? remainingKills - 1 : remainingKills;
    }

    static int remainingKillBudget(int pestCount, int earlyKills) {
        return Math.max(0, killBudgetForCount(pestCount) - Math.max(0, earlyKills));
    }

    private static boolean finishCurrentPlot(
            Minecraft client,
            PestDestroyerRuntime runtime,
            Context context,
            String currentPlot) {
        runtime.navigation.leaveOneSkippedPlots.add(PestPlotId.normalize(currentPlot));
        resetTracking(runtime);
        ClientUtils.sendDebugMessage(
                "PestDestroyer: leaving one pest alive on whitelisted plot "
                        + currentPlot
                        + ".");
        moveToNextActivePlotOrFinish(client, runtime, context);
        return true;
    }

    private static void moveToNextActivePlotOrFinish(
            Minecraft client,
            PestDestroyerRuntime runtime,
            Context context) {
        ClientUtils.setKeyMappingState(client.options.keyUse, false);
        ClientUtils.setKeyMappingState(client.options.keyDown, false);
        ClientUtils.setKeyMappingState(client.options.keyAttack, false);
        ClientUtils.setKeyMappingState(client.options.keyUp, false);
        PathfindingManager.stop();
        runtime.currentTarget = null;
        runtime.lockedTargetEntityId = -1;
        runtime.pestTargetQueue.clear();
        runtime.zeroPestTabTicks = 0;
        runtime.navigation.plotTpSent = false;
        runtime.navigation.getLocationAttempts = 0;
        runtime.navigation.waypointCycleCount = 0;
        Set<String> activeInfested = filterSkippedPlots(
                runtime,
                PestManager.getInfestedPlotsFromTab(client));
        runtime.navigation.plotQueue.clear();
        runtime.navigation.plotQueue.addAll(activeInfested);
        runtime.navigation.currentPlotIdx = 0;

        if (runtime.navigation.plotQueue.isEmpty()) {
            context.setState(PestDestroyer.State.FINISH);
            return;
        }

        String nextPlot = runtime.navigation.plotQueue.getFirst();
        String currentPlot = context.getEffectivePlot(client);
        if (!PestPlotId.equals(nextPlot, currentPlot)) {
            ClientUtils.sendDebugMessage(
                    "PestDestroyer: moving from skipped plot "
                            + currentPlot
                            + " to plot "
                            + nextPlot
                            + ".");
            context.setState(PestDestroyer.State.TELEPORT_TO_PLOT);
            return;
        }
        context.setState(PestDestroyer.State.CHECK_NEXT);
    }

    private static void pruneRememberedPlots(PestDestroyerRuntime runtime) {
        if (!AetherConfig.LEAVE_ONE_PEST_ALIVE.get()) {
            runtime.navigation.leaveOneSkippedPlots.clear();
            resetTracking(runtime);
            return;
        }
        Set<String> configured = new LinkedHashSet<>();
        for (String plot : AetherConfig.LEAVE_ONE_PEST_PLOTS.get()) {
            configured.add(PestPlotId.normalize(plot));
        }
        runtime.navigation.leaveOneSkippedPlots.retainAll(configured);
    }

    private static void resetTracking(PestDestroyerRuntime runtime) {
        runtime.navigation.leaveOneTrackedPlot = null;
        runtime.navigation.leaveOneRemainingKills = -1;
        runtime.navigation.leaveOneUnbudgetedKills = 0;
        runtime.navigation.leaveOneReservedEntityId = -1;
    }

    private static boolean isWhitelistedPlot(String plot) {
        String normalized = PestPlotId.normalize(plot);
        if (normalized.isEmpty() || normalized.equals("unknown")) {
            return false;
        }
        return AetherConfig.LEAVE_ONE_PEST_PLOTS.get().stream()
                .map(PestPlotId::normalize)
                .anyMatch(normalized::equals);
    }
}
