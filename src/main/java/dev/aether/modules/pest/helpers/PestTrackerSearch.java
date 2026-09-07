package dev.aether.modules.pest.helpers;

import dev.aether.config.AetherConfig;
import dev.aether.mixin.AccessorInventory;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.util.ClientUtils;
import dev.aether.util.GardenPlots;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

final class PestTrackerSearch {
    private long requestedAt;
    private int readyTick;
    private int guidedWaypoints;
    private Vec3 previousWaypoint;
    private Vec3 waypoint;
    private boolean rotating;

    void beginSearch() {
        requestedAt = 0L;
        readyTick = 0;
        waypoint = null;
    }

    void reset() {
        beginSearch();
        guidedWaypoints = 0;
        previousWaypoint = null;
        rotating = false;
    }

    void stopLooking() {
        if (rotating) RotationManager.cancelRotation();
        rotating = false;
    }

    boolean tick(Minecraft client, PestDestroyerRuntime runtime, long now) {
        if (!AetherConfig.USE_PEST_TRACKER_ABILITY.get() || guidedWaypoints >= 2
                || runtime.vacuumSlot < 0 || runtime.vacuumSlot > 8
                || !PestLoadoutHelper.isVacuum(client.player.getInventory().getItem(runtime.vacuumSlot))) {
            stopLooking();
            return false;
        }
        if (requestedAt == 0L) {
            if (now - runtime.stateEnteredAt > 2_000L) return false;
            PathfindingManager.stop();
            ClientUtils.endUseHold();
            if (client.player.isUsingItem() && client.gameMode != null) client.gameMode.releaseUsingItem(client.player);
            ClientUtils.setKeyMappingState(client.options.keyShift, false);
            int selected = ((AccessorInventory) client.player.getInventory()).getSelected();
            if (selected != runtime.vacuumSlot) {
                FailsafeManager.selectHotbarSlot(client, runtime.vacuumSlot);
                readyTick = client.player.tickCount + 2;
                return true;
            }
            if (client.player.tickCount < readyTick || client.player.isShiftKeyDown()
                    || !PestTrackerAbility.canUse(now)) return true;
            requestedAt = now;
            ClientUtils.performAttackClickDirect();
            return true;
        }

        PestTrackerTrail.Prediction prediction = PestTrackerAbility.predictionSince(requestedAt, now);
        if (prediction != null) {
            RotationManager.trackRotation(client, prediction.target(), 450f,
                    Math.min(120f, AetherConfig.PEST_MAX_TURN_SPEED.get()));
            rotating = true;
        }
        if (!PestTrackerAbility.isComplete(requestedAt, now)) return true;
        if (prediction != null) {
            waypoint = constrainWaypoint(client.player.position(), prediction.target(),
                    PestPlotNavigator.currentPlotBounds(client, runtime.navigation), previousWaypoint);
            if (waypoint != null) {
                guidedWaypoints++;
                previousWaypoint = waypoint;
            }
        }
        return false;
    }

    Vec3 waypoint() {
        return waypoint;
    }

    void onSweepWaypoint() {
        guidedWaypoints = 0;
    }

    static Vec3 constrainWaypoint(Vec3 player, Vec3 estimate, GardenPlots.Bounds bounds, Vec3 previous) {
        double x = bounds == null ? estimate.x : bounds.clampX(estimate.x, 8.0);
        double z = bounds == null ? estimate.z : bounds.clampZ(estimate.z, 8.0);
        Vec3 target = new Vec3(x, player.y, z);
        if (player.distanceTo(target) < 16.0 || previous != null && previous.distanceTo(target) < 12.0) return null;
        return target;
    }
}
