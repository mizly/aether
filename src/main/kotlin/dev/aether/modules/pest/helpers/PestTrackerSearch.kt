package dev.aether.modules.pest.helpers

import dev.aether.config.AetherConfig
import dev.aether.mixin.AccessorInventory
import dev.aether.modules.failsafe.FailsafeManager
import dev.aether.modules.pathfinding.PathfindingManager
import dev.aether.modules.rotation.RotationManager
import dev.aether.util.ClientUtils
import dev.aether.util.GardenPlots
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3

class PestTrackerSearch {
    private var requestedAt = 0L
    private var readyTick = 0
    private var guidedWaypoints = 0
    private var previousWaypoint: Vec3? = null
    private var waypoint: Vec3? = null
    private var rotating = false

    fun beginSearch() {
        requestedAt = 0L
        readyTick = 0
        waypoint = null
    }

    fun reset() {
        beginSearch()
        guidedWaypoints = 0
        previousWaypoint = null
        rotating = false
    }

    fun stopLooking() {
        if (rotating) RotationManager.cancelRotation()
        rotating = false
    }

    fun tick(client: Minecraft, runtime: PestDestroyerRuntime, now: Long): Boolean {
        val player = client.player ?: return false
        if (!AetherConfig.USE_PEST_TRACKER_ABILITY.get() || guidedWaypoints >= 2
            || runtime.vacuumSlot < 0 || runtime.vacuumSlot > 8
            || !PestLoadoutHelper.isVacuum(player.inventory.getItem(runtime.vacuumSlot))) {
            stopLooking()
            return false
        }
        if (requestedAt == 0L) {
            if (now - runtime.stateEnteredAt > 2_000L) return false
            PathfindingManager.stop()
            ClientUtils.endUseHold()
            if (player.isUsingItem) client.gameMode?.releaseUsingItem(player)
            ClientUtils.setKeyMappingState(client.options.keyShift, false)
            val selected = (player.inventory as AccessorInventory).getSelected()
            if (selected != runtime.vacuumSlot) {
                FailsafeManager.selectHotbarSlot(client, runtime.vacuumSlot)
                readyTick = player.tickCount + 2
                return true
            }
            if (player.tickCount < readyTick || player.isShiftKeyDown || !PestTrackerAbility.canUse(now)) return true
            requestedAt = now
            ClientUtils.performAttackClickDirect()
            return true
        }
        val prediction = PestTrackerAbility.predictionSince(requestedAt, now)
        if (prediction != null) {
            RotationManager.trackRotation(client, prediction.target(), 450f, Math.min(120f, AetherConfig.PEST_MAX_TURN_SPEED.get()))
            rotating = true
        }
        if (!PestTrackerAbility.isComplete(requestedAt, now)) return true
        if (prediction != null) {
            waypoint = constrainWaypoint(player.position(), prediction.target(), PestPlotNavigator.currentPlotBounds(client, runtime.navigation), previousWaypoint)
            if (waypoint != null) {
                guidedWaypoints++
                previousWaypoint = waypoint
            }
        }
        return false
    }

    fun waypoint(): Vec3? = waypoint

    fun onSweepWaypoint() {
        guidedWaypoints = 0
    }

    companion object {
        @JvmStatic fun constrainWaypoint(player: Vec3, estimate: Vec3, bounds: GardenPlots.Bounds?, previous: Vec3?): Vec3? {
            val x = bounds?.clampX(estimate.x, 8.0) ?: estimate.x
            val z = bounds?.clampZ(estimate.z, 8.0) ?: estimate.z
            val target = Vec3(x, player.y, z)
            if (player.distanceTo(target) < 16.0 || previous != null && previous.distanceTo(target) < 12.0) return null
            return target
        }
    }
}
