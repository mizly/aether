package dev.aether.modules.pest.helpers

import dev.aether.util.CommandUtils
import net.minecraft.world.phys.Vec3
import java.util.ArrayList
import java.util.concurrent.ConcurrentHashMap

class PestNavigationState {
    companion object { private const val TRUSTED_PLOT_TTL_MS = 120_000L }
    @JvmField var calculatedWaypoint: Vec3? = null
    @JvmField val trackerSearch = PestTrackerSearch()
    @JvmField var plotAnchor: Vec3? = null
    @JvmField var scanPointIdx = 0
    @JvmField var getLocationAttempts = 0
    @JvmField var waypointCycleCount = 0
    @JvmField var plotTpSent = false
    @JvmField var plotTpWindow: CommandUtils.ChatWindow? = null
    @JvmField val plotQueue: MutableList<String> = ArrayList()
    @JvmField val leaveOneSkippedPlots: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @JvmField var leaveOneTrackedPlot: String? = null
    @JvmField var leaveOneRemainingKills = -1
    @JvmField var leaveOneUnbudgetedKills = 0
    @JvmField var leaveOneReservedEntityId = -1
    @JvmField var currentPlotIdx = 0
    @JvmField var lastTargetPlot: String? = null
    @JvmField var trustedPlot: String? = null
    @JvmField var trustedPlotExpiresAt = 0L

    fun trustPlot(plot: String, now: Long) { trustedPlot = plot; trustedPlotExpiresAt = now + TRUSTED_PLOT_TTL_MS }
    fun resetForRun() {
        trackerSearch.reset(); calculatedWaypoint = null; plotAnchor = null; scanPointIdx = 0
        getLocationAttempts = 0; waypointCycleCount = 0; plotTpSent = false; plotTpWindow = null
        plotQueue.clear(); leaveOneTrackedPlot = null; leaveOneRemainingKills = -1
        leaveOneUnbudgetedKills = 0; leaveOneReservedEntityId = -1; currentPlotIdx = 0
        lastTargetPlot = null; trustedPlot = null; trustedPlotExpiresAt = 0L
    }
}
