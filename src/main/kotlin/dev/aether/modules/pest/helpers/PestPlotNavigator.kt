package dev.aether.modules.pest.helpers

import dev.aether.modules.pest.PestManager
import dev.aether.util.ClientUtils
import dev.aether.util.CommandUtils
import dev.aether.util.GardenPlots
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import java.util.LinkedHashSet
import java.util.function.Predicate

internal class PestPlotNavigator private constructor() {
    companion object {
        private val SCAN_OFFSETS = arrayOf(doubleArrayOf(30.0, 30.0), doubleArrayOf(-30.0, 30.0), doubleArrayOf(-30.0, -30.0), doubleArrayOf(30.0, -30.0))
        private const val SCAN_EDGE_INSET = 8.0
        private const val TARGET_PLOT_MARGIN = 3.0

        @JvmStatic fun scanPointCount(): Int = SCAN_OFFSETS.size

        @JvmStatic fun nextScanWaypoint(client: Minecraft?, navigationState: PestNavigationState): Vec3? {
            if (client == null || client.player == null) return null
            val bounds = currentPlotBounds(client, navigationState)
            if (navigationState.plotAnchor == null) {
                navigationState.plotAnchor = if (bounds != null) Vec3(bounds.centerX(), client.player!!.y, bounds.centerZ()) else client.player!!.position()
            }
            if (navigationState.scanPointIdx >= SCAN_OFFSETS.size) return null
            val offset = SCAN_OFFSETS[navigationState.scanPointIdx++]
            val anchor = navigationState.plotAnchor!!
            var x = anchor.x + offset[0]
            var z = anchor.z + offset[1]
            if (bounds != null) {
                if (!bounds.contains(client.player!!.x, client.player!!.z, TARGET_PLOT_MARGIN)) {
                    ClientUtils.sendDebugMessage("[PestDestroyer] Sweeping plot " + getEffectivePlot(client, navigationState) + " from outside its bounds.")
                }
                x = bounds.clampX(x, SCAN_EDGE_INSET)
                z = bounds.clampZ(z, SCAN_EDGE_INSET)
            }
            return Vec3(x, client.player!!.y, z)
        }

        @JvmStatic fun currentPlotBounds(client: Minecraft?, navigationState: PestNavigationState): GardenPlots.Bounds? {
            val plot = getEffectivePlot(client, navigationState)
            return if (PestPlotId.isUsable(plot)) GardenPlots.boundsForPlot(plot) else null
        }

        @JvmStatic fun currentPlotFilter(client: Minecraft?, navigationState: PestNavigationState): Predicate<Entity> {
            val bounds = currentPlotBounds(client, navigationState) ?: return Predicate { true }
            return Predicate { entity -> entity != null && bounds.contains(entity.x, entity.z, TARGET_PLOT_MARGIN) }
        }

        @JvmStatic fun getNextPlotTarget(navigationState: PestNavigationState): String? {
            if (navigationState.plotQueue.isEmpty() || navigationState.currentPlotIdx >= navigationState.plotQueue.size) return null
            return navigationState.plotQueue[navigationState.currentPlotIdx]
        }

        @JvmStatic fun tryNextPlot(client: Minecraft, navigationState: PestNavigationState): Boolean {
            val infested = filterSkippedPlots(PestManager.getInfestedPlotsFromTab(client), navigationState)
            if (infested.isEmpty()) return false
            navigationState.plotQueue.clear()
            navigationState.plotQueue.addAll(infested)
            navigationState.currentPlotIdx = 0
            val firstPlot = navigationState.plotQueue[0]
            val currentPlot = getEffectivePlot(client, navigationState)
            if (!plotsEqual(firstPlot, currentPlot)) {
                navigationState.plotTpSent = false
                navigationState.getLocationAttempts = 0
                navigationState.waypointCycleCount = 0
                ClientUtils.sendDebugMessage("[PestDestroyer] Moving to new first infested plot: " + firstPlot)
                return true
            }
            return false
        }

        @JvmStatic fun getEffectivePlot(client: Minecraft?, navigationState: PestNavigationState): String? {
            if (navigationState.trustedPlot != null && System.currentTimeMillis() < navigationState.trustedPlotExpiresAt) return navigationState.trustedPlot!!
            val score: String? = ClientUtils.getCurrentPlot()
            if (score != null && score != "Unknown") return score
            val freshPlotChat = CommandUtils.getFreshKnownPlotChat()
            if (freshPlotChat != null) return freshPlotChat
            return "Unknown"
        }

        @JvmStatic fun plotsEqual(first: String?, second: String?): Boolean = PestPlotId.equals(first, second)

        private fun filterSkippedPlots(plots: Set<String>, navigationState: PestNavigationState): Set<String> {
            val filtered = LinkedHashSet<String>()
            for (plot in plots) if (!navigationState.leaveOneSkippedPlots.contains(PestPlotId.normalize(plot))) filtered.add(plot)
            return filtered
        }
    }
}
