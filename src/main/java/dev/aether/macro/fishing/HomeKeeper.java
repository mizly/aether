package dev.aether.macro.fishing;

import dev.aether.macro.MacroInput;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.routes.BlockCentering;
import dev.aether.modules.routes.EtherwarpLeg;
import dev.aether.modules.routes.Route;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

// the block a fishing macro casts from, the trip back onto it, and the swim out of whatever it fell into
final class HomeKeeper {

    enum Result { RUNNING, ARRIVED, FAILED }

    // the block being walked to sits just under eye level, so watching it reads as ahead and slightly down
    private static final double LOOK_TARGET_HEIGHT = 1.2;
    private static final long LIQUID_JUMP_MIN_DELAY_MS = 100L;
    private static final long LIQUID_JUMP_MAX_DELAY_MS = 300L;
    private static final double ETHERWARP_MIN_DISTANCE = 4.0;
    // wading out of lava is slow and expensive, so the warp takes over as soon as there is anywhere to go
    private static final double ETHERWARP_LIQUID_MIN_DISTANCE = 1.0;
    private static final int MAX_RETURN_ATTEMPTS = 6;
    // a failed plan usually means we are still sinking in lava, so the jump needs time before retrying
    private static final long RETURN_RETRY_MIN_MS = 500L;
    private static final long RETURN_RETRY_MAX_MS = 900L;
    // the block footprint plus a sliver; an exact block match alone routes us to where we already stand
    private static final double ORIGIN_RADIUS = 0.55;
    private static final double ORIGIN_BELOW = 0.5;
    private static final double ORIGIN_ABOVE = 1.0;
    // centring only counts its own timeout on the ground, so a landing that keeps bobbing is cut off here
    private static final long CENTRE_LIMIT_MS = 3_000L;

    private final String tag;
    private final BooleanSupplier etherwarpReturn;
    private final Predicate<Entity> escapeFrom;
    private final boolean upright;
    private BlockPos origin;
    private int returnAttempts;
    private boolean returnPathStarted;
    private boolean returnByWalk;
    private boolean etherwarpUsed;
    private EtherwarpLeg returnWarp;
    private long returnRetryAt;
    private volatile boolean returnFinished;
    private BlockCentering centering;
    private long centeringSince;
    private long jumpHoldAt;

    // escapeFrom picks the liquids worth jumping out of; it also holds the walk home until we are clear of them.
    // upright walks home standing and insists on a full path, so a step up near the block cannot stall it
    HomeKeeper(String tag, BooleanSupplier etherwarpReturn, Predicate<Entity> escapeFrom, boolean upright) {
        this.tag = tag;
        this.etherwarpReturn = etherwarpReturn;
        this.escapeFrom = escapeFrom;
        this.upright = upright;
    }

    void start(BlockPos origin) {
        this.origin = origin;
        returnAttempts = 0;
        returnPathStarted = false;
        returnFinished = false;
        centering = null;
        jumpHoldAt = 0L;
    }

    BlockPos origin() {
        return origin;
    }

    boolean isOnOrigin(Minecraft mc) {
        if (origin == null) {
            return false;
        }
        if (origin.equals(mc.player.blockPosition())) {
            return true;
        }
        Vec3 home = Vec3.atBottomCenterOf(origin);
        return withinOriginBlock(mc.player.getX() - home.x,
                mc.player.getY() - home.y,
                mc.player.getZ() - home.z);
    }

    // standing on the lip of the block, or a hair above it after the jump out, still counts as home
    static boolean withinOriginBlock(double dx, double dy, double dz) {
        return Math.abs(dx) <= ORIGIN_RADIUS
                && Math.abs(dz) <= ORIGIN_RADIUS
                && dy >= -ORIGIN_BELOW
                && dy <= ORIGIN_ABOVE;
    }

    // a new trip home: one warp allowed again, nothing planned yet
    void beginTrip(Minecraft mc) {
        returnPathStarted = false;
        returnByWalk = false;
        returnFinished = false;
        etherwarpUsed = false;
        returnRetryAt = 0L;
        centering = null;
        dropReturnWarp(mc);
    }

    void cancel(Minecraft mc) {
        PathfindingManager.stop(false);
        dropReturnWarp(mc);
        returnPathStarted = false;
        returnFinished = false;
        centering = null;
    }

    Result tick(Minecraft mc, long now, RandomGenerator random) {
        if (centering != null) {
            return tickCentering(mc, now);
        }

        if (isOnOrigin(mc)) {
            PathfindingManager.stop(false);
            dropReturnWarp(mc);
            centering = new BlockCentering(now, origin.below());
            centeringSince = now;
            return tickCentering(mc, now);
        }

        // one crouched, lined up click; the old pathfinder warp re-clicked on a timer and could fire unsneaked,
        // which is a plain aotv teleport straight off the block
        if (returnWarp != null) {
            EtherwarpLeg.Result result = returnWarp.tick(mc);
            if (result == EtherwarpLeg.Result.RUNNING) {
                return Result.RUNNING;
            }
            if (result == EtherwarpLeg.Result.FAILED) {
                ClientUtils.sendDebugMessage(tag + " return warp failed: " + returnWarp.failure());
            }
            dropReturnWarp(mc);
            returnFinished = true;
            return Result.RUNNING;
        }

        if (returnFinished) {
            returnFinished = false;
            returnPathStarted = false;
            // a refused warp is a change of plan, not a failed attempt; only a dead walk route counts
            if (returnByWalk && ++returnAttempts > MAX_RETURN_ATTEMPTS) {
                return Result.FAILED;
            }
            returnByWalk = false;
            returnRetryAt = now + nextReturnRetryDelayMs(random);
            return Result.RUNNING;
        }

        if (now < returnRetryAt) {
            return Result.RUNNING;
        }

        Vec3 home = Vec3.atBottomCenterOf(origin);
        if (!returnPathStarted) {
            boolean inLiquid = escapeFrom.test(mc.player);
            // one warp per trip, landed or missed; anything after it walks and looks at the block
            if (!etherwarpUsed && shouldEtherwarp(mc.player.position().distanceTo(home), inLiquid,
                    etherwarpReturn.getAsBoolean())) {
                etherwarpUsed = true;
                returnPathStarted = true;
                returnByWalk = false;
                PathfindingManager.stop(false);
                returnWarp = new EtherwarpLeg(
                        new Route.Waypoint(origin.getX(), origin.getY(), origin.getZ(), Route.LegType.ETHERWARP), null);
                return Result.RUNNING;
            }
            // a walk route cannot be planned out of lava, so the jump has to lift us clear first
            if (inLiquid) {
                return Result.RUNNING;
            }
            returnPathStarted = true;
            startWalkHome(mc, home);
            return Result.RUNNING;
        }

        // the warp has to keep its own aim, so only the walk watches the block it is heading for
        if (returnByWalk && PathfindingManager.isNavigating()) {
            PathfindingManager.setWalkLookTarget(home.add(0.0, LOOK_TARGET_HEIGHT, 0.0));
        }
        return Result.RUNNING;
    }

    // a walk or warp can stop on the lip, and a cast from there is not the throw the block was picked for
    private Result tickCentering(Minecraft mc, long now) {
        if (!centering.tick(mc, now) && now - centeringSince <= CENTRE_LIMIT_MS) {
            return Result.RUNNING;
        }
        centering = null;
        MacroInput.set(mc.options.keyShift, false);
        returnPathStarted = false;
        returnByWalk = false;
        returnFinished = false;
        etherwarpUsed = false;
        returnRetryAt = 0L;
        returnAttempts = 0;
        return Result.ARRIVED;
    }

    private void dropReturnWarp(Minecraft mc) {
        if (returnWarp != null) {
            returnWarp = null;
            EtherwarpLeg.release(mc);
        }
    }

    private void startWalkHome(Minecraft mc, Vec3 home) {
        returnByWalk = true;
        if (upright) {
            PathfindingManager.startUprightWalk(mc, home, () -> returnFinished = true, () -> returnFinished = true,
                    true, true);
            return;
        }
        PathfindingManager.startConfiguredWalk(mc, home,
                () -> returnFinished = true,
                () -> returnFinished = true,
                true, 0.35, true, false);
    }

    void tickLiquidEscape(Minecraft mc, long now, RandomGenerator random) {
        // a jump mid aim moves the eye off the line the warp was lined up on
        if (!escapeFrom.test(mc.player) || returnWarp != null) {
            jumpHoldAt = 0L;
            return;
        }
        if (jumpHoldAt == 0L) {
            // a beat of sinking first, so surfacing is not a frame-perfect reaction to touching lava
            jumpHoldAt = now + nextLiquidJumpDelayMs(random);
        }
        if (shouldHoldLiquidJump(true, now, jumpHoldAt)) {
            MacroInput.set(mc.options.keyJump, true);
        }
    }

    // walking a few blocks beats lining up a warp, but lava is worth leaving at once
    static boolean shouldEtherwarp(double distance, boolean inLiquid, boolean etherwarpEnabled) {
        if (!etherwarpEnabled) {
            return false;
        }
        return distance >= (inLiquid ? ETHERWARP_LIQUID_MIN_DISTANCE : ETHERWARP_MIN_DISTANCE);
    }

    static long nextReturnRetryDelayMs(RandomGenerator random) {
        return random.nextLong(RETURN_RETRY_MIN_MS, RETURN_RETRY_MAX_MS + 1);
    }

    static boolean returnRetryDelayInRange(long delay) {
        return delay >= RETURN_RETRY_MIN_MS && delay <= RETURN_RETRY_MAX_MS;
    }

    static long nextLiquidJumpDelayMs(RandomGenerator random) {
        return random.nextLong(LIQUID_JUMP_MIN_DELAY_MS, LIQUID_JUMP_MAX_DELAY_MS + 1);
    }

    static boolean liquidJumpDelayInRange(long delay) {
        return delay >= LIQUID_JUMP_MIN_DELAY_MS && delay <= LIQUID_JUMP_MAX_DELAY_MS;
    }

    static boolean shouldHoldLiquidJump(boolean inLiquid, long now, long holdFrom) {
        return inLiquid && holdFrom != 0L && now >= holdFrom;
    }
}
