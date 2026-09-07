package dev.aether.modules.pest.helpers;

import dev.aether.modules.pest.PestManager;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;

import java.util.Set;

/**
 * Owns run-level completion, timeout, and plot-priority checks for the pest
 * destroyer. Keeping these concerns outside the state handlers makes the main
 * coordinator responsible only for orchestration.
 */
final class PestDestroyerProgressController {
    interface Context {
        boolean shouldFinishForAliveCount(Minecraft client, int aliveCount);

        String getAliveFinishReason(int aliveCount);

        Set<String> filterSkippedInfestedPlots(Set<String> plots);

        String getEffectivePlot(Minecraft client);

        boolean plotsEqual(String first, String second);

        void setState(PestDestroyer.State state);

        boolean prepareCompletionRescan(Minecraft client);

        void finish(Minecraft client);
    }

    private PestDestroyerProgressController() {
    }

    static boolean tick(
            Minecraft client,
            PestDestroyerRuntime runtime,
            long stuckTimeoutMs,
            Context context) {
        int aliveNow = PestManager.getPestDestroyerCompletionAliveCountNow(client);
        boolean finishReading = aliveNow >= 0
                && context.shouldFinishForAliveCount(client, aliveNow);

        if (finishReading
                && PestCompletionGuard.shouldAcceptFinishReading(
                        runtime.activatedAt,
                        false)) {
            runtime.zeroPestTabTicks++;
            if (PestCompletionGuard.isConfirmed(runtime.zeroPestTabTicks)) {
                ClientUtils.setKeyMappingState(client.options.keyUse, false);
                ClientUtils.setKeyMappingState(client.options.keyDown, false);
                if (context.prepareCompletionRescan(client)) {
                    runtime.zeroPestTabTicks = 0;
                    return true;
                }
                ClientUtils.sendDebugMessage(
                        "PestDestroyer: tablist reports "
                                + context.getAliveFinishReason(aliveNow)
                                + " and final rescan is clear. Finishing.");
                context.finish(client);
                return true;
            }
        } else {
            if (finishReading
                    && PestCompletionGuard.isInStartupGrace(runtime.activatedAt)
                    && runtime.zeroPestTabTicks == 0) {
                ClientUtils.sendDebugMessage(
                        "PestDestroyer: ignoring finish-level tab reading during startup grace.");
            }
            runtime.zeroPestTabTicks = 0;
        }

        if (client.player.tickCount % 10 == 0
                && isPlotRefreshState(runtime.state)) {
            Set<String> rawInfested = PestManager.getInfestedPlotsFromTab(client);
            Set<String> infested = context.filterSkippedInfestedPlots(rawInfested);
            if (!infested.isEmpty()) {
                String firstPlot = infested.iterator().next();
                String currentPlot = context.getEffectivePlot(client);
                boolean currentStillActionable = infested.stream()
                        .anyMatch(plot -> context.plotsEqual(plot, currentPlot));
                if (!currentStillActionable
                        && isImmediatePlotExitState(runtime.state)) {
                    ClientUtils.sendDebugMessage(
                            "[PestDestroyer] Plot "
                                    + currentPlot
                                    + " no longer first in tab. Leaving immediately for "
                                    + firstPlot);
                    runtime.navigation.plotQueue.clear();
                    runtime.navigation.plotQueue.addAll(infested);
                    runtime.navigation.currentPlotIdx = 0;
                    runtime.navigation.plotTpSent = false;
                    context.setState(PestDestroyer.State.TELEPORT_TO_PLOT);
                    return true;
                }
            } else if (!rawInfested.isEmpty() && finishReading) {
                ClientUtils.sendDebugMessage(
                        "PestDestroyer: only skipped leave-one plots remain. Finishing.");
                context.finish(client);
                return true;
            }
        }

        if (System.currentTimeMillis() - runtime.activatedAt > stuckTimeoutMs) {
            ClientUtils.sendMessage(
                    "\u00A7cPest destroyer timed out after 5 minutes. Returning to farm.",
                    false);
            context.finish(client);
            return true;
        }

        if (shouldEnsureFlying(runtime.state)
                && !client.player.getAbilities().flying
                && client.player.getAbilities().mayfly) {
            context.setState(PestDestroyer.State.FLY_UP);
            return true;
        }
        return false;
    }

    private static boolean isPlotRefreshState(PestDestroyer.State state) {
        return state != PestDestroyer.State.TELEPORT_TO_PLOT
                && state != PestDestroyer.State.IDLE
                && state != PestDestroyer.State.FINISH;
    }

    private static boolean isImmediatePlotExitState(PestDestroyer.State state) {
        return state == PestDestroyer.State.CHECK_NEXT
                || state == PestDestroyer.State.GET_LOCATION
                || state == PestDestroyer.State.FLY_TO_WAYPOINT;
    }

    private static boolean shouldEnsureFlying(PestDestroyer.State state) {
        return state != PestDestroyer.State.IDLE
                && state != PestDestroyer.State.FINISH
                && state != PestDestroyer.State.TELEPORT_TO_PLOT
                && state != PestDestroyer.State.EQUIP_VACUUM
                && state != PestDestroyer.State.FLY_UP;
    }
}
