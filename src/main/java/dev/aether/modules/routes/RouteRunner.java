package dev.aether.modules.routes;

import dev.aether.macro.MacroState;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ThreadLocalRandom;

// client thread only: runs the warps and legs of a route one tick at a time
public final class RouteRunner {
    private enum Phase { WARP, SETTLE, LEG, LEG_WAIT, DONE, FAILED }

    private static final String HUB_WARP = "/warp hub";
    private static final long WARP_TIMEOUT_MS = 12_000L;
    private static final int MAX_WARP_ATTEMPTS = 3;
    // a warp inside the island we are already on only moves us, so a jump this big counts as arriving
    private static final double SAME_WORLD_WARP_JUMP = 8.0;
    private static final long SETTLE_MIN_MS = 1_800L;
    private static final long SETTLE_MAX_MS = 3_200L;
    private static final long LEG_RETRY_MIN_MS = 500L;
    private static final long LEG_RETRY_MAX_MS = 1_000L;
    private static final int MAX_LEG_ATTEMPTS = 3;
    private static final double ARRIVED_HORIZONTAL = 0.7;
    private static final double ARRIVED_VERTICAL = 1.1;

    private final Route route;
    private final Deque<String> warps = new ArrayDeque<>();
    private Phase phase;
    private String failure = "";

    private String currentWarp;
    private int warpAttempts;
    private long warpSentAt;
    private Level warpLevel;
    private Vec3 warpPosition;
    private long settleUntil;

    private int legIndex;
    private int legAttempts;
    private long legRetryAt;
    private volatile boolean legFinished;
    private volatile boolean legFailed;

    public RouteRunner(Route route, boolean hopThroughHub) {
        this.route = route;
        if (hopThroughHub) {
            warps.add(HUB_WARP);
        }
        String islandWarp = route.warpCommand();
        if (!islandWarp.isEmpty()) {
            warps.add(islandWarp);
        }
        phase = warps.isEmpty() ? Phase.LEG : Phase.WARP;
    }

    public boolean isDone() {
        return phase == Phase.DONE;
    }

    public boolean isFailed() {
        return phase == Phase.FAILED;
    }

    public String failure() {
        return failure;
    }

    public void cancel() {
        if (phase == Phase.LEG_WAIT) {
            PathfindingManager.stop(false);
        }
        phase = Phase.FAILED;
        failure = "cancelled";
    }

    public void tick(Minecraft mc) {
        if (phase == Phase.DONE || phase == Phase.FAILED) {
            return;
        }
        long now = System.currentTimeMillis();
        switch (phase) {
            case WARP -> tickWarp(mc, now);
            case SETTLE -> tickSettle(mc, now);
            case LEG -> tickLeg(mc, now);
            case LEG_WAIT -> tickLegWait(mc, now);
            default -> {
            }
        }
    }

    private void tickWarp(Minecraft mc, long now) {
        if (currentWarp == null) {
            currentWarp = warps.poll();
            warpAttempts = 0;
            warpSentAt = 0L;
        }
        if (currentWarp == null) {
            phase = Phase.LEG;
            return;
        }

        if (warpSentAt == 0L) {
            if (mc.player == null || mc.level == null || mc.screen != null) {
                return;
            }
            if (++warpAttempts > MAX_WARP_ATTEMPTS) {
                fail("warp did not go through: " + currentWarp);
                return;
            }
            warpLevel = mc.level;
            warpPosition = mc.player.position();
            warpSentAt = now;
            ClientUtils.sendDebugMessage("[Route] " + currentWarp + " (attempt " + warpAttempts + ")");
            ClientUtils.sendCommand(currentWarp);
            return;
        }

        if (mc.player != null && mc.level != null && hasArrived(mc)) {
            currentWarp = null;
            settleUntil = now + ThreadLocalRandom.current().nextLong(SETTLE_MIN_MS, SETTLE_MAX_MS + 1);
            phase = Phase.SETTLE;
            return;
        }
        if (now - warpSentAt > WARP_TIMEOUT_MS) {
            warpSentAt = 0L;
        }
    }

    private boolean hasArrived(Minecraft mc) {
        if (mc.level != warpLevel) {
            return true;
        }
        return warpPosition != null && mc.player.position().distanceTo(warpPosition) >= SAME_WORLD_WARP_JUMP;
    }

    private void tickSettle(Minecraft mc, long now) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        // the scoreboard is missing for a moment on every transfer, so wait until we are really in skyblock
        MacroState.Location location = ClientUtils.getCurrentLocation();
        if (location == MacroState.Location.LIMBO || location == MacroState.Location.LOBBY
                || location == MacroState.Location.UNKNOWN) {
            settleUntil = Math.max(settleUntil, now + SETTLE_MIN_MS / 2);
            return;
        }
        if (now < settleUntil) {
            return;
        }
        phase = warps.isEmpty() ? Phase.LEG : Phase.WARP;
    }

    private void tickLeg(Minecraft mc, long now) {
        if (legIndex >= route.waypoints().size()) {
            phase = Phase.DONE;
            return;
        }
        if (mc.player == null || mc.level == null || mc.screen != null || now < legRetryAt) {
            return;
        }

        Route.Waypoint waypoint = route.waypoints().get(legIndex);
        if (isStandingAt(mc, waypoint)) {
            nextLeg();
            return;
        }
        if (++legAttempts > MAX_LEG_ATTEMPTS) {
            fail("could not reach waypoint " + (legIndex + 1));
            return;
        }

        legFinished = false;
        legFailed = false;
        phase = Phase.LEG_WAIT;
        // a warp that has already missed once walks instead, so one bad line of sight does not end the route
        if (waypoint.type() == Route.LegType.ETHERWARP && legAttempts == 1) {
            PathfindingManager.startConfiguredPureEtherwarp(mc, waypoint.x(), waypoint.y(), waypoint.z(),
                    () -> legFinished = true,
                    () -> legFailed = true);
            return;
        }
        PathfindingManager.startConfiguredWalk(mc,
                Vec3.atBottomCenterOf(new BlockPos(waypoint.x(), waypoint.y(), waypoint.z())),
                () -> legFinished = true,
                () -> legFailed = true,
                true, 0.35, true, false);
    }

    private void tickLegWait(Minecraft mc, long now) {
        if (legFinished || legFailed) {
            boolean arrived = mc.player != null && isStandingAt(mc, route.waypoints().get(legIndex));
            phase = Phase.LEG;
            if (arrived) {
                nextLeg();
                return;
            }
            legRetryAt = now + ThreadLocalRandom.current().nextLong(LEG_RETRY_MIN_MS, LEG_RETRY_MAX_MS + 1);
        }
    }

    private void nextLeg() {
        legIndex++;
        legAttempts = 0;
        legRetryAt = 0L;
    }

    private void fail(String reason) {
        failure = reason;
        phase = Phase.FAILED;
        ClientUtils.sendDebugMessage("[Route] failed: " + reason);
    }

    public static boolean isStandingAt(Minecraft mc, Route.Waypoint waypoint) {
        return isWithinWaypoint(
                mc.player.getX() - (waypoint.x() + 0.5),
                mc.player.getY() - waypoint.y(),
                mc.player.getZ() - (waypoint.z() + 0.5));
    }

    static boolean isWithinWaypoint(double dx, double dy, double dz) {
        return Math.abs(dx) <= ARRIVED_HORIZONTAL
                && Math.abs(dz) <= ARRIVED_HORIZONTAL
                && Math.abs(dy) <= ARRIVED_VERTICAL;
    }
}
