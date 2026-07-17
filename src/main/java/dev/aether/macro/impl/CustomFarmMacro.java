package dev.aether.macro.impl;

import dev.aether.config.AetherConfig;
import dev.aether.config.FarmWaypoint;
import dev.aether.config.FarmWaypoints;
import dev.aether.macro.AbstractMacro;
import net.minecraft.client.Minecraft;

import java.util.List;

public class CustomFarmMacro extends AbstractMacro {
    private static final double REACHED_HORIZONTAL_DISTANCE_SQ = 0.85 * 0.85;
    private static final double REACHED_VERTICAL_DISTANCE = 1.5;

    private int targetWaypointIndex = 0;

    @Override
    public void onEnable(Minecraft mc) {
        super.onEnable(mc);
        if (AetherConfig.MACRO_FAST_LANE_SWITCH.get()) {
            AetherConfig.MACRO_FAST_LANE_SWITCH.set(false);
            AetherConfig.save();
        }
        List<FarmWaypoint> waypoints = FarmWaypoints.get();
        targetWaypointIndex = initialTargetIndex(mc, waypoints);
    }

    @Override
    public boolean isFarmingState() {
        return true;
    }

    @Override
    public void updateState(Minecraft mc) {
        if (mc.player == null) {
            return;
        }

        List<FarmWaypoint> waypoints = FarmWaypoints.get();
        if (waypoints.isEmpty()) {
            changeState(State.NONE);
            targetWaypointIndex = 0;
            return;
        }

        if (targetWaypointIndex < 0 || targetWaypointIndex >= waypoints.size()) {
            targetWaypointIndex = 0;
        }

        FarmWaypoint target = waypoints.get(targetWaypointIndex);
        if (isAtWaypoint(mc, target)) {
            FarmWaypoints.saveLastWaypoint(targetWaypointIndex);
            targetWaypointIndex = (targetWaypointIndex + 1) % waypoints.size();
            target = waypoints.get(targetWaypointIndex);
        }

        changeState(displayStateFor(target));
    }

    @Override
    public void invokeState(Minecraft mc) {
        List<FarmWaypoint> waypoints = FarmWaypoints.get();
        if (waypoints.isEmpty() || targetWaypointIndex < 0 || targetWaypointIndex >= waypoints.size()) {
            holdKeys(mc, false, false, false, false, true, false, false);
            return;
        }

        FarmWaypoint waypoint = waypoints.get(targetWaypointIndex);
        holdKeys(mc,
                waypoint.left(),
                waypoint.right(),
                waypoint.forward(),
                waypoint.back(),
                true,
                false,
                false);
    }

    private int initialTargetIndex(Minecraft mc, List<FarmWaypoint> waypoints) {
        if (waypoints.isEmpty()) {
            return 0;
        }

        if (mc.player != null) {
            for (int i = 0; i < waypoints.size(); i++) {
                if (isAtWaypoint(mc, waypoints.get(i))) {
                    FarmWaypoints.saveLastWaypoint(i);
                    return (i + 1) % waypoints.size();
                }
            }
        }

        int lastWaypoint = FarmWaypoints.getLastWaypoint();
        if (lastWaypoint >= 0 && lastWaypoint < waypoints.size()) {
            return (lastWaypoint + 1) % waypoints.size();
        }
        return 0;
    }

    private boolean isAtWaypoint(Minecraft mc, FarmWaypoint waypoint) {
        double dx = mc.player.getX() - waypoint.x();
        double dz = mc.player.getZ() - waypoint.z();
        double dy = Math.abs(mc.player.getY() - waypoint.y());
        return dx * dx + dz * dz <= REACHED_HORIZONTAL_DISTANCE_SQ
                && dy <= REACHED_VERTICAL_DISTANCE;
    }

    private State displayStateFor(FarmWaypoint waypoint) {
        if (waypoint.forward()) {
            return State.FORWARD;
        }
        if (waypoint.back()) {
            return State.BACKWARD;
        }
        if (waypoint.left()) {
            return State.LEFT;
        }
        if (waypoint.right()) {
            return State.RIGHT;
        }
        return State.NONE;
    }
}
