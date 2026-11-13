package dev.aether.modules.pest.helpers

import dev.aether.config.AetherConfig
import dev.aether.modules.pathfinding.PathfindingManager
import dev.aether.modules.pest.PestManager
import dev.aether.util.ClientUtils
import dev.aether.util.CommandUtils
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3

internal object PestNavigationCoordinator {
    interface Context {
        fun runtime(): PestDestroyerRuntime
        fun getStateEnteredAt(): Long = runtime().stateEnteredAt
        fun setStateEnteredAt(value: Long) { runtime().stateEnteredAt = value }
        fun getVacuumSlot(): Int = runtime().vacuumSlot
        fun setVacuumSlot(value: Int) { runtime().vacuumSlot = value }
        fun getVacuumRange(): Double = runtime().vacuumRange.toDouble()
        fun getStuckTicks(): Int = runtime().stuckTicks
        fun setStuckTicks(value: Int) { runtime().stuckTicks = value }
        fun getFlyTapTicks(): Int = runtime().flyTapTicks
        fun setFlyTapTicks(value: Int) { runtime().flyTapTicks = value }
        fun findVacuumHotbarSlot(client: Minecraft): Int
        fun setState(state: PestDestroyer.State)
        fun findClosestPest(client: Minecraft): Entity?
        fun engagePestTarget(client: Minecraft, pest: Entity)
        fun tryNextPlot(client: Minecraft): Boolean
        fun tryLeaveOneOnCurrentWhitelistedPlot(client: Minecraft): Boolean
        fun startRoofAotv(client: Minecraft, plot: String)
        fun startBallsackShredder(client: Minecraft, plot: String)
    }

    @JvmStatic fun handleTeleportToPlot(client: Minecraft, navigationState: PestNavigationState, context: Context, plotTpWaitMs: Long) {
        if (!navigationState.plotTpSent) {
            val targetPlot = PestPlotNavigator.getNextPlotTarget(navigationState)
            if (targetPlot == null) { context.setState(PestDestroyer.State.EQUIP_VACUUM); return }
            val currentPlot = ClientUtils.getCurrentPlot()
            if (targetPlot == currentPlot && !AetherConfig.PEST_PLOT_TP_FOR_CURRENT_PLOT.get()) {
                ClientUtils.sendDebugMessage("[PestDestroyer] Already on plot $targetPlot, skipping TP.")
                finalizePlotArrival(client, navigationState, context, targetPlot); return
            }
            ClientUtils.sendDebugMessage("[PestDestroyer] Teleporting to plot $targetPlot")
            navigationState.plotTpWindow = CommandUtils.beginChatWindow()
            CommandUtils.initiatePlotTp(targetPlot)
            navigationState.lastTargetPlot = targetPlot
            navigationState.plotTpSent = true
            context.setStateEnteredAt(System.currentTimeMillis()); return
        }
        val elapsed = System.currentTimeMillis() - context.getStateEnteredAt()
        val confirmed = navigationState.lastTargetPlot?.let { CommandUtils.hasPlotTp(navigationState.plotTpWindow, it) }
            ?: CommandUtils.hasPlotTp(navigationState.plotTpWindow)
        if (confirmed && navigationState.lastTargetPlot != null) {
            ClientUtils.sendDebugMessage("[PestDestroyer] Teleport to plot ${navigationState.lastTargetPlot} confirmed via chat. Trusting this location.")
            finalizePlotArrival(client, navigationState, context, navigationState.lastTargetPlot!!); return
        }
        if (elapsed > plotTpWaitMs) {
            val targetPlot = navigationState.lastTargetPlot
            val currentPlot = PestPlotNavigator.getEffectivePlot(client, navigationState)
            if (targetPlot != null && PestPlotNavigator.plotsEqual(targetPlot, currentPlot)) {
                ClientUtils.sendDebugMessage("[PestDestroyer] Teleport to plot $targetPlot confirmed by current plot.")
                finalizePlotArrival(client, navigationState, context, targetPlot); return
            }
            ClientUtils.sendDebugMessage("[PestDestroyer] Waiting for plot $targetPlot arrival; current plot is $currentPlot.")
            navigationState.plotTpSent = false; navigationState.plotTpWindow = null
            context.setStateEnteredAt(System.currentTimeMillis())
        }
    }

    @JvmStatic fun handleGetLocation(client: Minecraft, navigationState: PestNavigationState, context: Context, maxPlotSweeps: Int, maxScanWaypoints: Int) {
        if (!AetherConfig.PEST_DESTROYER_WALK_MODE.get() && !client.player!!.getAbilities().flying && client.player!!.getAbilities().mayfly) {
            if (context.getFlyTapTicks() < PestFlightTapper.TIMEOUT_TICKS) { PestFlightTapper.tick(client, context.getFlyTapTicks()); context.setFlyTapTicks(context.getFlyTapTicks() + 1) }
            else PestFlightTapper.release(client)
            return
        }
        if (context.getFlyTapTicks() != 0) { PestFlightTapper.release(client); context.setFlyTapTicks(0); context.setStateEnteredAt(System.currentTimeMillis()) }
        ClientUtils.setKeyMappingState(client.options!!.keyAttack, false)
        val pest = context.findClosestPest(client)
        if (pest != null) { navigationState.scanPointIdx = 0; context.engagePestTarget(client, pest); return }
        val trackerSearch = navigationState.trackerSearch
        if (trackerSearch.tick(client, context.runtime(), System.currentTimeMillis())) return
        var waypoint = trackerSearch.waypoint()
        val tracked = waypoint != null
        if (!tracked) { trackerSearch.onSweepWaypoint(); waypoint = PestPlotNavigator.nextScanWaypoint(client, navigationState) }
        if (waypoint == null) {
            navigationState.scanPointIdx = 0
            if (context.runtime().deferredTargets.releaseRetryable()) { ClientUtils.sendDebugMessage("[PestDestroyer] Plot sweep found nothing. Retrying timed-out pest(s)."); context.setState(PestDestroyer.State.CHECK_NEXT); return }
            navigationState.getLocationAttempts++
            ClientUtils.sendDebugMessage("[PestDestroyer] Plot sweep found no pests (sweep ${navigationState.getLocationAttempts}/$maxPlotSweeps)")
            if (navigationState.getLocationAttempts >= maxPlotSweeps) { navigationState.getLocationAttempts = 0; if (!context.tryNextPlot(client)) { ClientUtils.sendDebugMessage("[PestDestroyer] No more plots to check. Finishing."); context.setState(PestDestroyer.State.FINISH) } }
            return
        }
        navigationState.waypointCycleCount++
        if (navigationState.waypointCycleCount > maxScanWaypoints) {
            navigationState.waypointCycleCount = 0; navigationState.scanPointIdx = 0
            ClientUtils.sendDebugMessage("[PestDestroyer] Scan waypoint budget spent without finding a pest.")
            if (!context.tryNextPlot(client)) context.setState(PestDestroyer.State.FINISH)
            return
        }
        navigationState.calculatedWaypoint = waypoint
        ClientUtils.sendDebugMessage("[PestDestroyer] No pests loaded. ${if (tracked) "Following Pest Tracker to " else "Sweeping to "}${String.format("%.0f, %.0f, %.0f", waypoint.x, waypoint.y, waypoint.z)} (point ${navigationState.scanPointIdx}/${PestPlotNavigator.scanPointCount()}, waypoint ${navigationState.waypointCycleCount}/$maxScanWaypoints)")
        context.setState(PestDestroyer.State.FLY_TO_WAYPOINT)
    }

    @JvmStatic fun handleFlyToWaypoint(client: Minecraft, navigationState: PestNavigationState, context: Context, pathfinderStuckRetryTicks: Int, stateTimeoutMs: Long) {
        val waypoint = navigationState.calculatedWaypoint
        if (waypoint == null) { context.setState(PestDestroyer.State.CHECK_NEXT); return }
        if (context.tryLeaveOneOnCurrentWhitelistedPlot(client)) return
        val pest = context.findClosestPest(client)
        if (pest != null) {
            PathfindingManager.stop(); navigationState.waypointCycleCount = 0
            ClientUtils.sendDebugMessage("PestDestroyer: found pest while flying to waypoint at ${String.format("%.0f, %.0f, %.0f", pest.x, pest.y, pest.z)} (dist: ${String.format("%.1f", client.player!!.distanceTo(pest))})")
            context.engagePestTarget(client, pest); return
        }
        if (client.player!!.position().distanceTo(waypoint) < 15) { PathfindingManager.stop(); context.setState(PestDestroyer.State.GET_LOCATION); return }
        if (!PathfindingManager.isNavigating()) {
            context.setStuckTicks(context.getStuckTicks() + 1)
            if (context.getStuckTicks() > pathfinderStuckRetryTicks) { context.setStuckTicks(0); PathfindingManager.stop(); context.setState(PestDestroyer.State.GET_LOCATION); return }
            if (context.getStuckTicks() == 1) PathfindingManager.startPathfind(client, waypoint.x.toInt(), waypoint.y.toInt(), waypoint.z.toInt(), !AetherConfig.PEST_DESTROYER_WALK_MODE.get())
        } else context.setStuckTicks(0)
        if (System.currentTimeMillis() - context.getStateEnteredAt() > stateTimeoutMs) { ClientUtils.sendDebugMessage("[PestDestroyer] Fly-to-waypoint timed out."); PathfindingManager.stop(); context.setState(PestDestroyer.State.GET_LOCATION) }
    }

    private fun finalizePlotArrival(client: Minecraft, navigationState: PestNavigationState, context: Context, plot: String) {
        navigationState.trustPlot(plot, System.currentTimeMillis()); navigationState.plotTpSent = false; navigationState.plotTpWindow = null
        navigationState.plotAnchor = null; navigationState.trackerSearch.reset(); PestTrackerAbility.clear()
        navigationState.scanPointIdx = 0; navigationState.getLocationAttempts = 0
        if (PestManager.isBallsackShredderActiveForCurrentCycle()) { context.startBallsackShredder(client, plot); return }
        if (PestAotvManager.shouldDoAotvOnCurrentPlot(client, plot, true)) { ClientUtils.sendDebugMessage("[PestDestroyer] AOTV to roof needed for plot $plot"); context.startRoofAotv(client, plot); return }
        if (!AetherConfig.PEST_DESTROYER_WALK_MODE.get() && !client.player!!.getAbilities().flying && client.player!!.getAbilities().mayfly) { ClientUtils.sendDebugMessage("[PestDestroyer] Not flying after arrival, triggering flight."); context.setState(PestDestroyer.State.FLY_UP); return }
        if (context.getVacuumSlot() < 0) { context.setState(PestDestroyer.State.EQUIP_VACUUM); return }
        if (context.tryLeaveOneOnCurrentWhitelistedPlot(client)) return
        val immediatePest = context.findClosestPest(client)
        if (immediatePest != null) { ClientUtils.sendDebugMessage("PestDestroyer: pest detected right after arrival. Engaging."); context.engagePestTarget(client, immediatePest); return }
        context.setState(PestDestroyer.State.CHECK_NEXT)
    }
}
