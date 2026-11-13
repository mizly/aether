package dev.aether.modules.pest.helpers

import dev.aether.config.AetherConfig
import dev.aether.modules.pathfinding.PathfindingManager
import dev.aether.modules.pest.PestManager
import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft
import java.util.LinkedHashSet
import java.util.regex.Pattern

object PestLeaveOneController {
    interface Context {
        fun getEffectivePlot(client: Minecraft): String
        fun setState(state: PestDestroyer.State)
    }

    private val PLOT_PEST_COUNT = Pattern.compile("(?i)\\bplot\\s*[-:#]?\\s*(\\d+)\\D+?x\\s*(\\d+)\\b")

    @JvmStatic
    fun shouldFinishForAliveCount(client: Minecraft, runtime: PestDestroyerRuntime, aliveCount: Int): Boolean {
        if (aliveCount < 0) return false
        if (aliveCount == 0) {
            runtime.navigation.leaveOneSkippedPlots.clear()
            return true
        }
        if (!AetherConfig.LEAVE_ONE_PEST_ALIVE.get()) {
            resetTracking(runtime)
            return false
        }
        pruneRememberedPlots(runtime)
        return shouldFinishForCounts(aliveCount, runtime.navigation.leaveOneSkippedPlots.size)
    }

    @JvmStatic
    fun tryLeaveCurrentPlot(client: Minecraft, runtime: PestDestroyerRuntime, context: Context): Boolean {
        if (!AetherConfig.LEAVE_ONE_PEST_ALIVE.get()) return false
        val currentPlot = context.getEffectivePlot(client)
        if (!isWhitelistedPlot(currentPlot)) {
            resetTracking(runtime)
            return false
        }
        val normalized = PestPlotId.normalize(currentPlot)
        if (runtime.navigation.leaveOneSkippedPlots.contains(normalized)) return false
        val plotPests = parsePlotPestCount(ClientUtils.getSidebarLines().toList(), currentPlot)
        if (normalized != runtime.navigation.leaveOneTrackedPlot) {
            runtime.navigation.leaveOneTrackedPlot = normalized
            runtime.navigation.leaveOneRemainingKills = -1
            runtime.navigation.leaveOneUnbudgetedKills = 0
            runtime.navigation.leaveOneReservedEntityId = -1
        }
        if (runtime.navigation.leaveOneRemainingKills < 0 && plotPests > 0) {
            runtime.navigation.leaveOneRemainingKills = remainingKillBudget(plotPests, runtime.navigation.leaveOneUnbudgetedKills)
            ClientUtils.sendDebugMessage("PestDestroyer: Plot $currentPlot leave-one kill budget is ${runtime.navigation.leaveOneRemainingKills} after ${runtime.navigation.leaveOneUnbudgetedKills} early kill(s).")
        }
        return (plotPests == 1 || runtime.navigation.leaveOneRemainingKills == 0) && finishCurrentPlot(client, runtime, context, currentPlot)
    }

    @JvmStatic
    fun recordTrackedKill(client: Minecraft, runtime: PestDestroyerRuntime, context: Context): Boolean {
        val currentPlot = context.getEffectivePlot(client)
        if (!isWhitelistedPlot(currentPlot)) return false
        val normalized = PestPlotId.normalize(currentPlot)
        if (normalized != runtime.navigation.leaveOneTrackedPlot) {
            runtime.navigation.leaveOneTrackedPlot = normalized
            runtime.navigation.leaveOneRemainingKills = -1
            runtime.navigation.leaveOneUnbudgetedKills = 0
            runtime.navigation.leaveOneReservedEntityId = -1
        }
        if (runtime.navigation.leaveOneRemainingKills < 0) {
            runtime.navigation.leaveOneUnbudgetedKills++
            return false
        }
        runtime.navigation.leaveOneRemainingKills = consumeKillBudget(runtime.navigation.leaveOneRemainingKills)
        return runtime.navigation.leaveOneRemainingKills == 0 && finishCurrentPlot(client, runtime, context, currentPlot)
    }

    @JvmStatic
    fun isTrackingPlot(runtime: PestDestroyerRuntime, plot: String): Boolean =
        runtime.navigation.leaveOneRemainingKills >= 0 && PestPlotId.equals(runtime.navigation.leaveOneTrackedPlot, plot)

    @JvmStatic
    fun filterSkippedPlots(runtime: PestDestroyerRuntime, infested: Collection<String>): Set<String> {
        pruneRememberedPlots(runtime)
        val filtered = LinkedHashSet<String>()
        for (plot in infested) if (!runtime.navigation.leaveOneSkippedPlots.contains(PestPlotId.normalize(plot))) filtered.add(plot)
        return filtered.toSet()
    }

    @JvmStatic
    fun forgetPlot(runtime: PestDestroyerRuntime, plot: String) {
        val normalized = PestPlotId.normalize(plot)
        runtime.navigation.leaveOneSkippedPlots.remove(normalized)
        if (normalized == runtime.navigation.leaveOneTrackedPlot) resetTracking(runtime)
    }

    @JvmStatic
    fun clearRememberedPlots(runtime: PestDestroyerRuntime) {
        runtime.navigation.leaveOneSkippedPlots.clear()
        resetTracking(runtime)
    }

    @JvmStatic
    fun rememberedPlotCount(runtime: PestDestroyerRuntime): Int {
        pruneRememberedPlots(runtime)
        return runtime.navigation.leaveOneSkippedPlots.size
    }

    @JvmStatic
    fun shouldFinishForCounts(aliveCount: Int, rememberedPlotCount: Int): Boolean =
        aliveCount == 0 || rememberedPlotCount > 0 && aliveCount <= rememberedPlotCount

    @JvmStatic
    fun parsePlotPestCount(lines: List<String>, plot: String): Int {
        val normalizedPlot = PestPlotId.normalize(plot)
        for (line in lines) {
            val matcher = PLOT_PEST_COUNT.matcher(line)
            if (matcher.find() && PestPlotId.normalize(matcher.group(1)) == normalizedPlot) return matcher.group(2).toInt()
        }
        return -1
    }

    @JvmStatic fun killBudgetForCount(pestCount: Int): Int = if (pestCount > 0) pestCount - 1 else -1
    @JvmStatic fun consumeKillBudget(remainingKills: Int): Int = if (remainingKills > 0) remainingKills - 1 else remainingKills
    @JvmStatic fun remainingKillBudget(pestCount: Int, earlyKills: Int): Int = maxOf(0, killBudgetForCount(pestCount) - maxOf(0, earlyKills))

    private fun finishCurrentPlot(client: Minecraft, runtime: PestDestroyerRuntime, context: Context, currentPlot: String): Boolean {
        runtime.navigation.leaveOneSkippedPlots.add(PestPlotId.normalize(currentPlot))
        resetTracking(runtime)
        ClientUtils.sendDebugMessage("PestDestroyer: leaving one pest alive on whitelisted plot $currentPlot.")
        moveToNextActivePlotOrFinish(client, runtime, context)
        return true
    }

    private fun moveToNextActivePlotOrFinish(client: Minecraft, runtime: PestDestroyerRuntime, context: Context) {
        ClientUtils.setKeyMappingState(client.options.keyUse, false)
        ClientUtils.setKeyMappingState(client.options.keyDown, false)
        ClientUtils.setKeyMappingState(client.options.keyAttack, false)
        ClientUtils.setKeyMappingState(client.options.keyUp, false)
        PathfindingManager.stop()
        runtime.currentTarget = null
        runtime.pestTargetQueue.clear()
        runtime.zeroPestTabTicks = 0
        runtime.navigation.plotTpSent = false
        runtime.navigation.getLocationAttempts = 0
        runtime.navigation.waypointCycleCount = 0
        val activeInfested: Set<String> = filterSkippedPlots(runtime, PestManager.getInfestedPlotsFromTab(client))
        runtime.navigation.plotQueue.clear()
        runtime.navigation.plotQueue.addAll(activeInfested)
        runtime.navigation.currentPlotIdx = 0
        if (runtime.navigation.plotQueue.isEmpty()) {
            context.setState(PestDestroyer.State.FINISH)
            return
        }
        val nextPlot = runtime.navigation.plotQueue.first()
        val currentPlot = context.getEffectivePlot(client)
        if (!PestPlotId.equals(nextPlot, currentPlot)) {
            ClientUtils.sendDebugMessage("PestDestroyer: moving from skipped plot $currentPlot to plot $nextPlot.")
            context.setState(PestDestroyer.State.TELEPORT_TO_PLOT)
            return
        }
        context.setState(PestDestroyer.State.CHECK_NEXT)
    }

    private fun pruneRememberedPlots(runtime: PestDestroyerRuntime) {
        if (!AetherConfig.LEAVE_ONE_PEST_ALIVE.get()) {
            runtime.navigation.leaveOneSkippedPlots.clear()
            resetTracking(runtime)
            return
        }
        val configured = LinkedHashSet<String>()
        for (plot in AetherConfig.LEAVE_ONE_PEST_PLOTS.get()) configured.add(PestPlotId.normalize(plot))
        runtime.navigation.leaveOneSkippedPlots.retainAll(configured)
    }

    private fun resetTracking(runtime: PestDestroyerRuntime) {
        runtime.navigation.leaveOneTrackedPlot = null
        runtime.navigation.leaveOneRemainingKills = -1
        runtime.navigation.leaveOneUnbudgetedKills = 0
        runtime.navigation.leaveOneReservedEntityId = -1
    }

    private fun isWhitelistedPlot(plot: String): Boolean {
        val normalized = PestPlotId.normalize(plot)
        if (normalized.isEmpty() || normalized == "unknown") return false
        return AetherConfig.LEAVE_ONE_PEST_PLOTS.get().any { PestPlotId.normalize(it) == normalized }
    }
}
