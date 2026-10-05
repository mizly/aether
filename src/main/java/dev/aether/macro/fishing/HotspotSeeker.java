package dev.aether.macro.fishing;

import dev.aether.macro.MacroInput;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.modules.routes.BlockCentering;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

// which hotspot the macro fishes, the spot it casts from, and the walk over there
final class HotspotSeeker {

    enum Seek { WORKING, NONE, FOUND }

    enum Trip { RUNNING, ARRIVED, FAILED }

    // a float this far from the middle is outside the ring on every island, whatever its real size
    static final double FLOAT_RING = 2.0;
    static final int MAX_MISSES = 2;
    private static final long RESCAN_MS = 5_000L;
    private static final long TRIP_LIMIT_MS = 40_000L;
    private static final long CENTRE_LIMIT_MS = 3_000L;
    // a walk that never got going leaves the pathfinder idle with no callback, so it is called off after this
    private static final long IDLE_WALK_MS = 1_500L;
    // a hotspot with no usable spot right now may get one once the crowd around it moves on
    private static final long SKIP_MS = 60_000L;

    private final HotspotDetector detector = new HotspotDetector();
    private final Set<BlockPos> badSpots = new HashSet<>();
    private final Map<Integer, Long> skippedUntil = new HashMap<>();
    private HotspotDetector.Hotspot hotspot;
    private HotspotSpotFinder finder;
    private BlockPos spot;
    private boolean settled;
    private int misses;
    private long nextSeekAt;

    private boolean walking;
    private volatile boolean walkDone;
    private volatile boolean walkFailed;
    private long tripStartedAt;
    private long idleSince;
    private BlockCentering centering;
    private long centeringSince;

    void reset() {
        detector.clear();
        badSpots.clear();
        skippedUntil.clear();
        forget();
        nextSeekAt = 0L;
        walking = false;
        centering = null;
    }

    void scan(Minecraft mc) {
        detector.tick(mc);
    }

    // the hotspot the macro is set up on, or null while it fishes plain liquid
    HotspotDetector.Hotspot fishing() {
        return settled ? hotspot : null;
    }

    BlockPos spot() {
        return spot;
    }

    void miss() {
        misses++;
    }

    void hit() {
        misses = 0;
    }

    // the spot can no longer line up a throw at all, which is as good as missing twice
    void spotUnusable() {
        misses = MAX_MISSES;
    }

    boolean wantsReplan(Minecraft mc, long now) {
        if (settled) {
            return misses >= MAX_MISSES || detector.isGone(mc, hotspot);
        }
        return now >= nextSeekAt && selectable(mc, now) != null;
    }

    // a spot missed from twice is out, but the hotspot keeps its other spots
    void beginSeek() {
        if (settled && misses >= MAX_MISSES && spot != null) {
            badSpots.add(spot);
        }
        settled = false;
        misses = 0;
        finder = null;
        spot = null;
    }

    Seek tickSeek(Minecraft mc, long now, RandomGenerator random) {
        // a missing hotspot starts over with whichever is nearest now
        if (hotspot != null && detector.isGone(mc, hotspot)) {
            forget();
        }
        if (hotspot == null) {
            hotspot = selectable(mc, now);
            badSpots.clear();
            finder = null;
            if (hotspot == null) {
                nextSeekAt = now + RESCAN_MS;
                return Seek.NONE;
            }
        }
        if (finder == null) {
            finder = HotspotSpotFinder.live(mc, hotspot, badSpots, random);
        }
        HotspotSpotFinder.Step step = finder.step();
        if (step.status() == HotspotSpotFinder.Status.WORKING) {
            return Seek.WORKING;
        }
        if (step.status() == HotspotSpotFinder.Status.EXHAUSTED) {
            ClientUtils.sendDebugMessage("[FishingMacro] no spot can cast into the hotspot, skipping it for now");
            skippedUntil.put(hotspot.id(), now + SKIP_MS);
            forget();
            nextSeekAt = now + RESCAN_MS;
            return Seek.NONE;
        }
        spot = step.spot();
        return Seek.FOUND;
    }

    private HotspotDetector.Hotspot selectable(Minecraft mc, long now) {
        skippedUntil.values().removeIf(until -> now >= until);
        return HotspotDetector.nearest(detector.seen(), mc.player.position(), skippedUntil.keySet());
    }

    private void forget() {
        hotspot = null;
        finder = null;
        spot = null;
        settled = false;
        misses = 0;
    }

    boolean onTrip() {
        return walking || centering != null;
    }

    void beginTrip(Minecraft mc, long now) {
        tripStartedAt = now;
        idleSince = 0L;
        walkDone = false;
        walkFailed = false;
        centering = null;
        if (onSpot(mc)) {
            startCentering(now);
            return;
        }
        walking = true;
        ClientUtils.sendDebugMessage("[FishingMacro] walking to " + spot.toShortString() + " to fish the hotspot");
        // upright the whole way, since a crouched walk can neither jump nor sprint
        PathfindingManager.startUprightWalk(mc, Vec3.atBottomCenterOf(spot),
                () -> walkDone = true, () -> walkFailed = true, true, true);
    }

    Trip tickTrip(Minecraft mc, long now) {
        if (centering != null) {
            if (!centering.tick(mc, now) && now - centeringSince <= CENTRE_LIMIT_MS) {
                return Trip.RUNNING;
            }
            centering = null;
            MacroInput.set(mc.options.keyShift, false);
            if (!onSpot(mc)) {
                return failTrip();
            }
            settled = true;
            misses = 0;
            return Trip.ARRIVED;
        }
        if (walkDone) {
            walking = false;
            if (!onSpot(mc)) {
                return failTrip();
            }
            startCentering(now);
            return Trip.RUNNING;
        }
        idleSince = PathfindingManager.isNavigating() ? 0L : (idleSince == 0L ? now : idleSince);
        if (walkFailed || now - tripStartedAt > TRIP_LIMIT_MS
                || (idleSince != 0L && now - idleSince > IDLE_WALK_MS)) {
            return failTrip();
        }
        return Trip.RUNNING;
    }

    void cancel() {
        if (walking) {
            PathfindingManager.stop(false);
        }
        walking = false;
        centering = null;
    }

    private Trip failTrip() {
        ClientUtils.sendDebugMessage("[FishingMacro] could not reach the hotspot spot, trying another");
        cancel();
        badSpots.add(spot);
        spot = null;
        return Trip.FAILED;
    }

    private void startCentering(long now) {
        PathfindingManager.stop(false);
        centering = new BlockCentering(now, spot.below());
        centeringSince = now;
    }

    private boolean onSpot(Minecraft mc) {
        Vec3 feet = Vec3.atBottomCenterOf(spot);
        return HomeKeeper.withinOriginBlock(mc.player.getX() - feet.x, mc.player.getY() - feet.y,
                mc.player.getZ() - feet.z);
    }
}
