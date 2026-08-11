package dev.aether.macro.farming;

import dev.aether.config.AetherConfig;
import dev.aether.config.FarmWaypoint;
import dev.aether.config.ConfigHelpers;
import dev.aether.config.FarmWaypoints;
import net.minecraft.client.Minecraft;

import java.util.List;

public class CustomFarmMacro extends AbstractFarmingMacro {
    private static final double REACHED_VERTICAL_DISTANCE = 1.5;

    private int activeWaypointIndex = 0;

    /**
     * Custom waypoint switches are confirmed first, then committed after the
     * configured lane-switch delay. While confirmation is pending,
     * activeWaypointIndex intentionally remains unchanged so invokeState()
     * continues holding the current farm key and attack.
     */
    private boolean keySwitchConfirm = false;
    private int pendingWaypointIndex = -1;
    private int keySwitchDelayTicks = 0;

    @Override
    public void onEnable(Minecraft mc) {
        super.onEnable(mc);
        if (AetherConfig.MACRO_FAST_LANE_SWITCH.get()) {
            AetherConfig.MACRO_FAST_LANE_SWITCH.set(false);
            AetherConfig.save();
        }
        List<FarmWaypoint> waypoints = FarmWaypoints.get();
        activeWaypointIndex = initialActiveIndex(mc, waypoints);
        resetKeySwitchConfirmation();
    }

    @Override
    public void onDisable(Minecraft mc) {
        resetKeySwitchConfirmation();
        super.onDisable(mc);
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
            resetKeySwitchConfirmation();
            return;
        }

        if (activeWaypointIndex < 0 || activeWaypointIndex >= waypoints.size()) {
            activeWaypointIndex = 0;
            resetKeySwitchConfirmation();
        }

        // A waypoint switch has already been confirmed. Keep the current
        // active waypoint/key while the randomized lane-switch delay counts down.
        if (keySwitchConfirm) {
            if (pendingWaypointIndex < 0 || pendingWaypointIndex >= waypoints.size()) {
                resetKeySwitchConfirmation();
            } else {
                if (keySwitchDelayTicks > 0) {
                    keySwitchDelayTicks--;
                }

                if (keySwitchDelayTicks > 0) {
                    changeState(displayStateFor(waypoints.get(activeWaypointIndex)));
                    return;
                }

                // Delay finished: now commit the new waypoint/key, then reset
                // keySwitchConfirm so the next waypoint can be detected.
                commitPendingWaypoint();
                changeState(displayStateFor(waypoints.get(activeWaypointIndex)));
                return;
            }
        }

        for (int i = 0; i < waypoints.size(); i++) {
            if (!isAtWaypoint(mc, waypoints.get(i))) {
                continue;
            }

            // Preserve the original first-match behaviour. If the player is
            // still inside the active waypoint's radius, do not scan through
            // it and accidentally confirm an overlapping waypoint.
            if (i == activeWaypointIndex) {
                break;
            }

            beginKeySwitchConfirmation(i);

            // A zero randomized delay should behave as an immediate switch.
            if (keySwitchDelayTicks <= 0) {
                commitPendingWaypoint();
            }
            break;
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

    private void beginKeySwitchConfirmation(int waypointIndex) {
        keySwitchConfirm = true;
        pendingWaypointIndex = waypointIndex;

        int delayMs = ConfigHelpers.getRandomizedDelay(
                AetherConfig.MACRO_LANE_SWITCH_DELAY_MIN.get(),
                AetherConfig.MACRO_LANE_SWITCH_DELAY_MAX.get());

        // Pick a fresh random delay inside the configured min/max range for
        // every confirmed waypoint switch, matching AbstractFarmingMacro.
        keySwitchDelayTicks = (delayMs + 25) / 50;
    }

    private void commitPendingWaypoint() {
        activeWaypointIndex = pendingWaypointIndex;
        FarmWaypoints.saveLastWaypoint(activeWaypointIndex);
        resetKeySwitchConfirmation();
    }

    private void resetKeySwitchConfirmation() {
        keySwitchConfirm = false;
        pendingWaypointIndex = -1;
        keySwitchDelayTicks = 0;
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
        double radius = AetherConfig.MACRO_CUSTOM_WAYPOINT_SWITCH_RADIUS.get();
        double radiusSq = radius * radius;
        return dx * dx + dz * dz <= radiusSq
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
