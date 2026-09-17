package dev.aether.modules.routes;

import dev.aether.macro.MacroInput;
import dev.aether.macro.MacroState;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

// client thread only: runs the warps and legs of a route one tick at a time
public final class RouteRunner {
    private enum Phase { WARP, SETTLE, LEG, LEG_WAIT, DONE, FAILED }

    private static final String HUB_WARP = "/warp hub";
    // a warp to the island we are already on never changes the world and can land right where we stand,
    // so once nothing else has happened by then the warp is taken as done
    // hypixel can sit on "Warping..." for several seconds before the transfer, so this has to outlast that
    private static final long SAME_ISLAND_WARP_MS = 15_000L;
    // no walk covers this much ground in one tick, so a jump like it can only be the warp landing
    private static final double WARP_TICK_JUMP = 2.0;
    private static final long SETTLE_TIMEOUT_MS = 20_000L;
    // the walker can stop without reporting back, so a leg with nothing navigating is given this long
    private static final long IDLE_LEG_GRACE_MS = 1_500L;
    private static final long LEG_TIMEOUT_MS = 90_000L;
    private static final long SETTLE_MIN_MS = 1_800L;
    private static final long SETTLE_MAX_MS = 3_200L;
    private static final long LEG_RETRY_MIN_MS = 500L;
    private static final long LEG_RETRY_MAX_MS = 1_000L;
    private static final int MAX_LEG_ATTEMPTS = 3;
    private static final double ARRIVED_HORIZONTAL = 0.7;
    // a walk that only passes through a waypoint is done once the walker calls it reached, not centred on it
    private static final double PASSED_HORIZONTAL = 1.3;
    private static final double ARRIVED_VERTICAL = 1.1;

    private final Route route;
    private final Deque<String> warps = new ArrayDeque<>();
    private Phase phase;
    private String failure = "";

    private String currentWarp;
    private long warpSentAt;
    private Level warpLevel;
    private Level routeLevel;
    private Vec3 lastTickPosition;
    private long settleStartedAt;
    private long settleUntil;
    private long legStartedAt;
    private long legIdleSince;

    private int legIndex;
    private int legAttempts;
    private long legRetryAt;
    private volatile boolean legFinished;
    private volatile boolean legFailed;
    private RouteEtherwarpLeg etherwarpLeg;
    private BlockCentering finalCentering;

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
            if (etherwarpLeg != null) {
                RouteEtherwarpLeg.release(Minecraft.getInstance());
                etherwarpLeg = null;
            }
        }
        phase = Phase.FAILED;
        failure = "cancelled";
    }

    public void tick(Minecraft mc) {
        if (phase == Phase.DONE || phase == Phase.FAILED) {
            return;
        }
        long now = System.currentTimeMillis();
        if (phase != Phase.WARP && mc.level != null && routeLevel != null && mc.level != routeLevel) {
            restartAfterLateTransfer(mc, now);
        }
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
            warpSentAt = 0L;
        }
        if (currentWarp == null) {
            phase = Phase.LEG;
            return;
        }
        if (mc.player == null || mc.level == null) {
            return;
        }

        if (warpSentAt == 0L) {
            if (mc.screen != null) {
                return;
            }
            warpLevel = mc.level;
            lastTickPosition = mc.player.position();
            warpSentAt = now;
            ClientUtils.sendDebugMessage("[Route] " + currentWarp);
            ClientUtils.sendCommand(currentWarp);
            return;
        }

        Vec3 position = mc.player.position();
        boolean worldChanged = mc.level != warpLevel;
        boolean jumped = lastTickPosition != null && position.distanceTo(lastTickPosition) >= WARP_TICK_JUMP;
        lastTickPosition = position;
        if (worldChanged || jumped || now - warpSentAt > SAME_ISLAND_WARP_MS) {
            ClientUtils.sendDebugMessage("[Route] warp done ("
                    + (worldChanged ? "new world" : jumped ? "teleported" : "no change seen") + ")");
            currentWarp = null;
            beginSettle(mc, now);
        }
    }

    private void beginSettle(Minecraft mc, long now) {
        routeLevel = mc.level;
        settleStartedAt = now;
        settleUntil = now + ThreadLocalRandom.current().nextLong(SETTLE_MIN_MS, SETTLE_MAX_MS + 1);
        phase = Phase.SETTLE;
    }

    // the server moved us after we had already started, so whatever leg was running is meaningless now
    private void restartAfterLateTransfer(Minecraft mc, long now) {
        ClientUtils.sendDebugMessage("[Route] world changed mid route, settling again");
        if (phase == Phase.LEG_WAIT) {
            PathfindingManager.stop(false);
            if (etherwarpLeg != null) {
                RouteEtherwarpLeg.release(mc);
                etherwarpLeg = null;
            }
        }
        legAttempts = 0;
        legRetryAt = 0L;
        beginSettle(mc, now);
    }

    private void tickSettle(Minecraft mc, long now) {
        if (now - settleStartedAt > SETTLE_TIMEOUT_MS) {
            fail("never got back into skyblock after the warp");
            return;
        }
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
        ClientUtils.sendDebugMessage("[Route] settled in " + location);
        phase = warps.isEmpty() ? Phase.LEG : Phase.WARP;
    }

    private void tickLeg(Minecraft mc, long now) {
        if (legIndex >= route.waypoints().size()) {
            // the macro takes its home from the block the player stands on, so the route ends dead centre on it
            if (mc.player == null || mc.screen != null) {
                return;
            }
            if (finalCentering == null) {
                finalCentering = new BlockCentering(now, floorOf(route.end()));
            }
            if (finalCentering.tick(mc, now)) {
                MacroInput.set(mc.options.keyShift, false);
                phase = Phase.DONE;
            }
            return;
        }
        if (mc.player == null || mc.level == null || mc.screen != null || now < legRetryAt) {
            return;
        }

        Route.Waypoint waypoint = route.waypoints().get(legIndex);
        if (hasReached(mc, waypoint)) {
            nextLeg();
            return;
        }
        if (++legAttempts > MAX_LEG_ATTEMPTS) {
            fail("could not reach waypoint " + (legIndex + 1));
            return;
        }

        phase = Phase.LEG_WAIT;
        legStartedAt = now;
        legIdleSince = 0L;
        ClientUtils.sendDebugMessage("[Route] " + waypoint.type().name().toLowerCase(Locale.ROOT)
                + " to waypoint " + (legIndex + 1) + "/" + route.waypoints().size()
                + " (attempt " + legAttempts + ")");
        if (waypoint.type() == Route.LegType.ETHERWARP) {
            BlockPos throwFrom = legIndex == 0 ? null : floorOf(route.waypoints().get(legIndex - 1));
            etherwarpLeg = new RouteEtherwarpLeg(waypoint, throwFrom);
            return;
        }
        legFinished = false;
        legFailed = false;
        PathfindingManager.startUprightWalk(mc,
                Vec3.atBottomCenterOf(new BlockPos(waypoint.x(), waypoint.y(), waypoint.z())),
                () -> legFinished = true,
                () -> legFailed = true,
                mustStopOnWaypoint());
    }

    private void tickLegWait(Minecraft mc, long now) {
        if (etherwarpLeg != null) {
            RouteEtherwarpLeg.Result result = etherwarpLeg.tick(mc);
            if (result == RouteEtherwarpLeg.Result.RUNNING) {
                return;
            }
            String reason = etherwarpLeg.failure();
            etherwarpLeg = null;
            if (result == RouteEtherwarpLeg.Result.LANDED) {
                phase = Phase.LEG;
                nextLeg();
                // back to back warps keep the crouch, lifting it between them is what lets a click fire unsneaked
                if (!nextIsEtherwarp()) {
                    MacroInput.set(mc.options.keyShift, false);
                }
                return;
            }
            // a missed warp never falls back to another way of getting there, the route is exact or it stops
            fail("waypoint " + (legIndex + 1) + ": " + reason);
            return;
        }

        if (!legFinished && !legFailed) {
            if (now - legStartedAt > LEG_TIMEOUT_MS) {
                PathfindingManager.stop(false);
                legFailed = true;
            } else if (PathfindingManager.isNavigating()) {
                legIdleSince = 0L;
            } else if (legIdleSince == 0L) {
                legIdleSince = now;
            } else if (now - legIdleSince > IDLE_LEG_GRACE_MS) {
                legFailed = true;
            }
        }

        if (legFinished || legFailed) {
            boolean arrived = mc.player != null && hasReached(mc, route.waypoints().get(legIndex));
            phase = Phase.LEG;
            if (arrived) {
                // the walker can finish with keys still down, and a warp aimed while drifting lands beside its block
                PathfindingManager.stop(false);
                MacroInput.releaseMovement(mc);
                nextLeg();
                return;
            }
            legRetryAt = now + ThreadLocalRandom.current().nextLong(LEG_RETRY_MIN_MS, LEG_RETRY_MAX_MS + 1);
        }
    }

    private boolean nextIsEtherwarp() {
        return legIndex < route.waypoints().size()
                && route.waypoints().get(legIndex).type() == Route.LegType.ETHERWARP;
    }

    private static BlockPos floorOf(Route.Waypoint waypoint) {
        return new BlockPos(waypoint.x(), waypoint.y() - 1, waypoint.z());
    }

    private boolean isLastLeg() {
        return legIndex == route.waypoints().size() - 1;
    }

    // a walk can brush past a waypoint, unless it ends the route or an etherwarp is thrown from it,
    // since the warp was recorded from standing on that exact block
    private boolean mustStopOnWaypoint() {
        return isLastLeg() || route.waypoints().get(legIndex + 1).type() == Route.LegType.ETHERWARP;
    }

    private boolean hasReached(Minecraft mc, Route.Waypoint waypoint) {
        if (waypoint.type() == Route.LegType.ETHERWARP || mustStopOnWaypoint()) {
            return isStandingAt(mc, waypoint);
        }
        return isWithin(mc, waypoint, PASSED_HORIZONTAL);
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
        return isWithin(mc, waypoint, ARRIVED_HORIZONTAL);
    }

    private static boolean isWithin(Minecraft mc, Route.Waypoint waypoint, double horizontal) {
        return isWithinWaypoint(
                mc.player.getX() - (waypoint.x() + 0.5),
                mc.player.getY() - waypoint.y(),
                mc.player.getZ() - (waypoint.z() + 0.5),
                horizontal);
    }

    static boolean isWithinWaypoint(double dx, double dy, double dz, double horizontal) {
        return Math.abs(dx) <= horizontal
                && Math.abs(dz) <= horizontal
                && Math.abs(dy) <= ARRIVED_VERTICAL;
    }
}
