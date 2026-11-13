package dev.aether.modules.pest.helpers

import dev.aether.modules.pathfinding.PathfindingManager
import dev.aether.modules.pest.PestManager
import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity

class PestHuntController private constructor() {
    interface Context {
        fun tryLeaveOneOnCurrentPlot(client: Minecraft): Boolean
        fun setState(state: PestDestroyer.State)
        fun finish(client: Minecraft)
    }

    companion object {
        @JvmStatic
        fun checkNext(client: Minecraft, runtime: PestDestroyerRuntime,
                  targetContext: PestTargetController.Context, context: Context) {
        ClientUtils.setKeyMappingState(client.options.keyUse, false)
        ClientUtils.setKeyMappingState(client.options.keyDown, false)
        ClientUtils.setKeyMappingState(client.options.keyUp, false)
        PathfindingManager.stop()
        if (context.tryLeaveOneOnCurrentPlot(client)) return
        var pest: Entity? = PestTargetController.nextQueuedPest(client, runtime)
        if (pest == null) {
            PestTargetController.rebuildQueue(client, runtime, targetContext)
            pest = PestTargetController.nextQueuedPest(client, runtime)
        }
        if (pest == null && runtime.deferredTargets.releaseRetryable()) {
            PestTargetController.rebuildQueue(client, runtime, targetContext)
            pest = PestTargetController.nextQueuedPest(client, runtime)
        }
        if (pest != null) {
            PestTargetController.engage(client, runtime, targetContext, pest)
            return
        }
        val rawInfested = PestManager.getInfestedPlotsFromTab(client)
        if (rawInfested.isEmpty()) {
            if (PestCompletionGuard.isInStartupGrace(runtime.activatedAt)) {
                ClientUtils.sendDebugMessage("[PestDestroyer] Empty infested-plot tab data during startup grace. Retrying location scan.")
                context.setState(PestDestroyer.State.GET_LOCATION)
                return
            }
            ClientUtils.sendDebugMessage("[PestDestroyer] No infested plots in tab. Finishing.")
            context.finish(client)
            return
        }
        val infested: Set<String> = PestLeaveOneController.filterSkippedPlots(runtime, rawInfested)
        if (infested.isEmpty()) {
            ClientUtils.sendDebugMessage("PestDestroyer: all remaining infested plots are skipped leave-one plots. Finishing.")
            context.finish(client)
            return
        }
        runtime.navigation.plotQueue.clear()
        val currentPlot = PestPlotNavigator.getEffectivePlot(client, runtime.navigation)
        infested.stream().filter { PestPlotId.equals(it, currentPlot) }.findFirst().ifPresent { runtime.navigation.plotQueue.add(it) }
        infested.stream().filter { !PestPlotId.equals(it, currentPlot) }.forEach { runtime.navigation.plotQueue.add(it) }
        val firstPlot = runtime.navigation.plotQueue.first()
        if (!PestPlotId.equals(firstPlot, currentPlot)) {
            runtime.navigation.currentPlotIdx = 0
            runtime.navigation.plotTpSent = false
            runtime.navigation.getLocationAttempts = 0
            runtime.navigation.waypointCycleCount = 0
            ClientUtils.sendDebugMessage("[PestDestroyer] Not on first infested plot (current=$currentPlot, target=$firstPlot). Teleporting to Plot $firstPlot")
            context.setState(PestDestroyer.State.TELEPORT_TO_PLOT)
            return
        }
        ClientUtils.sendDebugMessage("[PestDestroyer] No visible pests on Plot $firstPlot. Sweeping the plot...")
        context.setState(PestDestroyer.State.GET_LOCATION)
        }
    }
}
