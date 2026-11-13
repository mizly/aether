package dev.aether.macro.farming

import dev.aether.config.AetherConfig
import dev.aether.config.ConfigHelpers
import dev.aether.config.FarmWaypoint
import dev.aether.config.FarmWaypoints
import net.minecraft.client.Minecraft

class CustomFarmMacro : AbstractFarmingMacro() {
    private var activeWaypointIndex = 0
    private var keySwitchConfirm = false
    private var pendingWaypointIndex = -1
    private var keySwitchDelayTicks = 0

    override fun onEnable(mc: Minecraft) {
        super.onEnable(mc)
        if (AetherConfig.MACRO_FAST_LANE_SWITCH.get()) {
            AetherConfig.MACRO_FAST_LANE_SWITCH.set(false)
            AetherConfig.save()
        }
        val waypoints = FarmWaypoints.get()
        activeWaypointIndex = initialActiveIndex(mc, waypoints)
        resetKeySwitchConfirmation()
    }
    override fun onDisable(mc: Minecraft) { resetKeySwitchConfirmation(); super.onDisable(mc) }
    override fun isFarmingState() = true

    override fun updateState(mc: Minecraft) {
        val player = mc.player ?: return
        val waypoints = FarmWaypoints.get()
        if (waypoints.isEmpty()) { changeState(State.NONE); activeWaypointIndex = 0; resetKeySwitchConfirmation(); return }
        if (activeWaypointIndex < 0 || activeWaypointIndex >= waypoints.size) { activeWaypointIndex = 0; resetKeySwitchConfirmation() }
        if (keySwitchConfirm) {
            if (pendingWaypointIndex < 0 || pendingWaypointIndex >= waypoints.size) resetKeySwitchConfirmation()
            else {
                if (keySwitchDelayTicks > 0) keySwitchDelayTicks--
                if (keySwitchDelayTicks > 0) { changeState(displayStateFor(waypoints[activeWaypointIndex])); return }
                commitPendingWaypoint(); changeState(displayStateFor(waypoints[activeWaypointIndex])); return
            }
        }
        for (i in waypoints.indices) {
            if (!isAtWaypoint(mc, waypoints[i])) continue
            if (i == activeWaypointIndex) break
            beginKeySwitchConfirmation(i)
            if (keySwitchDelayTicks <= 0) commitPendingWaypoint()
            break
        }
        changeState(displayStateFor(waypoints[activeWaypointIndex]))
    }
    override fun invokeState(mc: Minecraft) {
        val waypoints = FarmWaypoints.get()
        if (waypoints.isEmpty() || activeWaypointIndex < 0 || activeWaypointIndex >= waypoints.size) { holdKeys(mc, false, false, false, false, true, false, false); return }
        val waypoint = waypoints[activeWaypointIndex]
        holdKeys(mc, waypoint.left, waypoint.right, waypoint.forward, waypoint.back, true, false, false)
    }
    private fun beginKeySwitchConfirmation(waypointIndex: Int) {
        keySwitchConfirm = true; pendingWaypointIndex = waypointIndex
        val delayMs = ConfigHelpers.getRandomizedDelay(AetherConfig.MACRO_LANE_SWITCH_DELAY_MIN.get(), AetherConfig.MACRO_LANE_SWITCH_DELAY_MAX.get())
        keySwitchDelayTicks = (delayMs + 25) / 50
    }
    private fun commitPendingWaypoint() { activeWaypointIndex = pendingWaypointIndex; FarmWaypoints.saveLastWaypoint(activeWaypointIndex); resetKeySwitchConfirmation() }
    private fun resetKeySwitchConfirmation() { keySwitchConfirm = false; pendingWaypointIndex = -1; keySwitchDelayTicks = 0 }
    private fun initialActiveIndex(mc: Minecraft, waypoints: kotlin.collections.List<FarmWaypoint>): Int {
        if (waypoints.isEmpty()) return 0
        if (mc.player != null) for (i in waypoints.indices) if (isAtWaypoint(mc, waypoints[i])) { FarmWaypoints.saveLastWaypoint(i); return i }
        val lastWaypoint = FarmWaypoints.getLastWaypoint()
        return if (lastWaypoint >= 0 && lastWaypoint < waypoints.size) lastWaypoint else 0
    }
    private fun isAtWaypoint(mc: Minecraft, waypoint: FarmWaypoint): Boolean {
        val player = mc.player ?: return false
        val dx = player.getX() - waypoint.x
        val dz = player.getZ() - waypoint.z
        val dy = Math.abs(player.getY() - waypoint.y)
        val radius = AetherConfig.MACRO_CUSTOM_WAYPOINT_SWITCH_RADIUS.get()
        val radiusSq = radius * radius
        return dx * dx + dz * dz <= radiusSq && dy <= REACHED_VERTICAL_DISTANCE
    }
    private fun displayStateFor(waypoint: FarmWaypoint): State = when {
        waypoint.forward -> State.FORWARD
        waypoint.back -> State.BACKWARD
        waypoint.left -> State.LEFT
        waypoint.right -> State.RIGHT
        else -> State.NONE
    }
    companion object { private const val REACHED_VERTICAL_DISTANCE = 1.5 }
}
