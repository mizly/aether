package dev.aether.modules.pest.helpers;

import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.pest.PestManager;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.util.Set;

/** Chooses the next visible pest, plot transition, or tracker probe. */
final class PestHuntController {
    interface Context {
        boolean tryLeaveOneOnCurrentPlot(Minecraft client);

        void setState(PestDestroyer.State state);

        void finish(Minecraft client);
    }

    private PestHuntController() {
    }

    static void checkNext(
            Minecraft client,
            PestDestroyerRuntime runtime,
            PestTargetController.Context targetContext,
            Context context) {
        ClientUtils.setKeyMappingState(client.options.keyUse, false);
        ClientUtils.setKeyMappingState(client.options.keyDown, false);
        ClientUtils.setKeyMappingState(client.options.keyUp, false);
        PathfindingManager.stop();

        if (context.tryLeaveOneOnCurrentPlot(client)) {
            return;
        }

        Entity pest = PestTargetController.nextQueuedPest(client, runtime);
        if (pest == null) {
            PestTargetController.rebuildQueue(client, runtime, targetContext);
            pest = PestTargetController.nextQueuedPest(client, runtime);
        }
        if (pest != null) {
            PestTargetController.engage(client, runtime, targetContext, pest);
            return;
        }

        Set<String> rawInfested = PestManager.getInfestedPlotsFromTab(client);
        if (rawInfested.isEmpty()) {
            if (PestCompletionGuard.isInStartupGrace(runtime.activatedAt)) {
                ClientUtils.sendDebugMessage(
                        "[PestDestroyer] Empty infested-plot tab data during startup grace. Retrying location scan.");
                context.setState(PestDestroyer.State.GET_LOCATION);
                return;
            }
            ClientUtils.sendDebugMessage(
                    "[PestDestroyer] No infested plots in tab. Finishing.");
            context.finish(client);
            return;
        }

        Set<String> infested =
                PestLeaveOneController.filterSkippedPlots(runtime, rawInfested);
        if (infested.isEmpty()) {
            ClientUtils.sendDebugMessage(
                    "PestDestroyer: all remaining infested plots are skipped leave-one plots. Finishing.");
            context.finish(client);
            return;
        }

        runtime.navigation.plotQueue.clear();
        String currentPlot =
                PestPlotNavigator.getEffectivePlot(client, runtime.navigation);
        infested.stream()
                .filter(plot -> PestPlotId.equals(plot, currentPlot))
                .findFirst()
                .ifPresent(runtime.navigation.plotQueue::add);
        infested.stream()
                .filter(plot -> !PestPlotId.equals(plot, currentPlot))
                .forEach(runtime.navigation.plotQueue::add);
        String firstPlot = runtime.navigation.plotQueue.getFirst();

        if (!PestPlotId.equals(firstPlot, currentPlot)) {
            runtime.navigation.currentPlotIdx = 0;
            runtime.navigation.plotTpSent = false;
            runtime.navigation.getLocationAttempts = 0;
            runtime.navigation.waypointCycleCount = 0;
            ClientUtils.sendDebugMessage(
                    "[PestDestroyer] Not on first infested plot (current="
                            + currentPlot
                            + ", target="
                            + firstPlot
                            + "). Teleporting to Plot "
                            + firstPlot);
            context.setState(PestDestroyer.State.TELEPORT_TO_PLOT);
            return;
        }

        ClientUtils.sendDebugMessage(
                "[PestDestroyer] No visible pests on Plot "
                        + firstPlot
                        + ". Sweeping the plot...");
        context.setState(PestDestroyer.State.GET_LOCATION);
    }
}
