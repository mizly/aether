package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
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
        // CHECK_NEXT doubles as the final verification scan once the current
        // route runs dry. Stop completely during that check instead of carrying
        // old flight/path input into the scan and appearing to wander randomly.
        ClientUtils.forceReleaseMovementKeys();
        PathfindingManager.stop();

        if (context.tryLeaveOneOnCurrentPlot(client)) {
            return;
        }

        // One Tap may have moved on before a server death was final. Before a
        // route can run dry, make any still-live optimistic target eligible again.
        PestTargetController.reviveVisibleAssumedOneTapTargets(
                client, runtime, targetContext);

        // Refresh the queue from the player's current position every time we
        // choose a new pest. The player may have crossed a large part of the
        // plot since the old queue was built.
        PestTargetController.rebuildQueue(client, runtime, targetContext);
        Entity pest = PestTargetController.nextQueuedPest(client, runtime);
        if (pest == null && runtime.deferredTargets.releaseRetryable()) {
            PestTargetController.rebuildQueue(client, runtime, targetContext);
            pest = PestTargetController.nextQueuedPest(client, runtime);
        }
        if (pest != null) {
            PestTargetController.engage(client, runtime, targetContext, pest);
            return;
        }

        // Give the most recent optimistic One Tap handoff a short chance to
        // disappear before deciding the route is empty. State stays CHECK_NEXT,
        // so this is a lightweight tick wait rather than another movement sweep.
        if (PestTargetController.waitingForOneTapRecheck(runtime)) {
            return;
        }

        if (PestTargetController.onlyGivenUpPestsRemain(client, runtime, targetContext)) {
            ClientUtils.sendMessage(
                    "\u00A7ePest destroyer gave up on the last unreachable pest(s). Back to farming.",
                    false);
            context.finish(client);
            return;
        }

        // Final verification is adaptive: stay still, but do not force the
        // player to wait the entire configured scan duration when the server has
        // already confirmed the clear. A small settle window gives the last
        // kill/tab update time to arrive; after that, a finish-level alive count
        // ends the run immediately. The configured duration is only the maximum
        // amount of time we wait before falling back to the slower recovery
        // checks below.
        long now = System.currentTimeMillis();
        long scanElapsedMs = now - runtime.stateEnteredAt;
        long finalStationaryScanMs = Math.max(500L,
                Math.round(AetherConfig.PEST_FINAL_SCAN_DURATION_SECONDS.get() * 1000.0f));
        final long minimumSettleMs = 350L;

        int aliveNow = PestManager.getPestDestroyerCompletionAliveCountNow(client);
        if (scanElapsedMs >= minimumSettleMs
                && aliveNow >= 0
                && PestDestroyer.shouldFinishForAliveCount(client, aliveNow)) {
            ClientUtils.sendDebugMessage(
                    "[PestDestroyer] Adaptive final scan confirmed clear early. Finishing in place.");
            context.finish(client);
            return;
        }

        if (scanElapsedMs < finalStationaryScanMs) {
            return;
        }

        // If the authoritative count never became available, preserve the
        // previous max-time behavior rather than hanging forever. Otherwise a
        // positive count continues into the existing recovery logic.
        if (aliveNow < 0 || PestDestroyer.shouldFinishForAliveCount(client, aliveNow)) {
            ClientUtils.sendDebugMessage(
                    "[PestDestroyer] Stationary final scan reached its maximum and found no actionable pests. Finishing in place.");
            context.finish(client);
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
                    "[PestDestroyer] " + aliveNow
                            + " pest(s) are still reported alive but no plot is listed. Holding position and rescanning.");
            // Stay in CHECK_NEXT. Do not launch a blind plot sweep from stale or
            // incomplete tab data; a visible pest or refreshed plot entry will
            // be picked up on a later tick.
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

        String currentPlot =
                PestPlotNavigator.getEffectivePlot(client, runtime.navigation);
        boolean otherPlotsFirst = PestPlotPriority.otherPlotsFirst();
        boolean holdCurrent = PestPlotPriority.shouldHoldCurrentPlot(
                infested, currentPlot, otherPlotsFirst, runtime.navigation.currentPlotHoldSweeps);
        runtime.navigation.plotQueue.clear();
        runtime.navigation.plotQueue.addAll(
                PestPlotPriority.order(infested, currentPlot, otherPlotsFirst));
        String firstPlot = runtime.navigation.plotQueue.getFirst();

        if (!holdCurrent && !PestPlotId.equals(firstPlot, currentPlot)) {
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
