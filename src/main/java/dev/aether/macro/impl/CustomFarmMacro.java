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

    private int activeWaypointIndex = 0;

    @Override
    public void onEnable(Minecraft mc) {
        super.onEnable(mc);
        if (AetherConfig.MACRO_FAST_LANE_SWITCH.get()) {
            AetherConfig.MACRO_FAST_LANE_SWITCH.set(false);
            AetherConfig.save();
        }
        List<FarmWaypoint> waypoints = FarmWaypoints.get();
        activeWaypointIndex = initialActiveIndex(mc, waypoints);
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
            activeWaypointIndex = 0;
            return;
        }

        if (activeWaypointIndex < 0 || activeWaypointIndex >= waypoints.size()) {
            activeWaypointIndex = 0;
        }

        for (int i = 0; i < waypoints.size(); i++) {
            if (isAtWaypoint(mc, waypoints.get(i))) {
                activeWaypointIndex = i;
                FarmWaypoints.saveLastWaypoint(i);
                break;
            }
        }

        changeState(displayStateFor(waypoints.get(activeWaypointIndex)));
    }

    @Override
    public void invokeState(Minecraft mc) {
        List<FarmWaypoint> waypoints = FarmWaypoints.get();
        if (waypoints.isEmpty() || activeWaypointIndex < 0 || activeWaypointIndex >= waypoints.size()) {
            holdKeys(mc, false, false, false, false, true, false, false);
            return;
        }

        FarmWaypoint waypoint = waypoints.get(activeWaypointIndex);
        holdKeys(mc,
                waypoint.left(),
                waypoint.right(),
                waypoint.forward(),
                waypoint.back(),
                true,
                false,
                false);
    }

    private int initialActiveIndex(Minecraft mc, List<FarmWaypoint> waypoints) {
        if (waypoints.isEmpty()) {
            return 0;
        }

        if (mc.player != null) {
            for (int i = 0; i < waypoints.size(); i++) {
                if (isAtWaypoint(mc, waypoints.get(i))) {
                    FarmWaypoints.saveLastWaypoint(i);
                    return i;
                }
            }
        }

        int lastWaypoint = FarmWaypoints.getLastWaypoint();
        if (lastWaypoint >= 0 && lastWaypoint < waypoints.size()) {
            return lastWaypoint;
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
