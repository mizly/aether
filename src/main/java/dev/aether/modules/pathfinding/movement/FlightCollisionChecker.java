package dev.aether.modules.pathfinding.movement;

import dev.aether.modules.pathfinding.execution.FlightPathClearance;
import dev.aether.modules.pathfinding.wrapper.PathPosition;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class FlightCollisionChecker {
    private static final AABB BODY = new AABB(-0.35, 0, -0.35, 0.35, 1.8, 0.35);
    private static final int START_SEARCH_RADIUS = 3;

    // a cell is only as good as the room around it: creative flight moves in 0.15 steps, so a route
    // that leans on the last few centimetres of head clearance cannot actually be flown
    private static final double HEADROOM_PROBE = 0.35;
    private static final double NEAR_WALL_PROBE = 0.25;
    private static final double WIDE_WALL_PROBE = 0.6;
    private static final double TIGHT_HEADROOM_COST = 0.9;
    private static final double NEAR_WALL_COST = 0.55;
    private static final double WIDE_WALL_COST = 0.3;

    private final Function<AABB, Iterable<AABB>> collisions;
    private final Long2ByteOpenHashMap clearanceCache = new Long2ByteOpenHashMap();
    private final Long2DoubleOpenHashMap clearanceCostCache = new Long2DoubleOpenHashMap();

    public FlightCollisionChecker(WalkabilityChecker checker) {
        this.clearanceCostCache.defaultReturnValue(Double.NaN);
        var cache = new Long2ObjectOpenHashMap<List<AABB>>();
        collisions = search -> {
            List<AABB> result = new ArrayList<>();
            for (int x = Mth.floor(search.minX) - 1; x <= Mth.floor(search.maxX) + 1; x++) {
                for (int z = Mth.floor(search.minZ) - 1; z <= Mth.floor(search.maxZ) + 1; z++) {
                    for (int y = Mth.floor(search.minY) - 1; y <= Mth.floor(search.maxY) + 1; y++) {
                        var pos = new BlockPos(x, y, z);
                        List<AABB> boxes = cache.get(pos.asLong());
                        if (boxes == null) {
                            if (checker.getLevel().isOutsideBuildHeight(y) || !checker.getLevel().hasChunkAt(pos)
                                    || checker.isDangerous(x, y, z)) {
                                boxes = List.of(new AABB(pos));
                            } else {
                                boxes = checker.getState(x, y, z).getCollisionShape(checker.getLevel(), pos)
                                        .toAabbs().stream().map(box -> box.move(pos)).toList();
                            }
                            cache.put(pos.asLong(), boxes);
                        }
                        for (AABB box : boxes) {
                            if (box.intersects(search)) result.add(box);
                        }
                    }
                }
            }
            return result;
        };
    }

    FlightCollisionChecker(Function<AABB, Iterable<AABB>> collisions) {
        this.collisions = collisions;
        this.clearanceCostCache.defaultReturnValue(Double.NaN);
    }

    public static FlightCollisionChecker over(Function<AABB, Iterable<AABB>> collisions) {
        return new FlightCollisionChecker(collisions);
    }

    public boolean hasClearance(PathPosition position) {
        long key = BlockPos.asLong(position.flooredX(), position.flooredY(), position.flooredZ());
        byte cached = clearanceCache.get(key);
        if (cached != 0) return cached == 2;
        boolean clear = isClear(position, position);
        clearanceCache.put(key, clear ? (byte) 2 : (byte) 1);
        return clear;
    }

    public boolean isClear(PathPosition from, PathPosition to) {
        Vec3 start = waypoint(from);
        return FlightPathClearance.isClear(BODY.move(start), waypoint(to).subtract(start), collisions);
    }

    public PathPosition findStart(Vec3 feet, AABB bounds) {
        PathPosition origin = new PathPosition(feet.x, feet.y, feet.z).floor();
        PathPosition best = canReachStart(feet, bounds, origin) ? origin : null;
        double bestDistance = best == null ? Double.POSITIVE_INFINITY : feet.distanceToSqr(waypoint(best));
        for (int x = -START_SEARCH_RADIUS; x <= START_SEARCH_RADIUS; x++) {
            for (int y = -START_SEARCH_RADIUS; y <= START_SEARCH_RADIUS; y++) {
                for (int z = -START_SEARCH_RADIUS; z <= START_SEARCH_RADIUS; z++) {
                    PathPosition candidate = origin.add(x, y, z);
                    Vec3 target = waypoint(candidate);
                    double distance = feet.distanceToSqr(target);
                    if (distance >= bestDistance || !canReachStart(feet, bounds, candidate)) continue;
                    best = candidate;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private boolean canReachStart(Vec3 feet, AABB bounds, PathPosition candidate) {
        if (!hasClearance(candidate)) return false;
        Vec3 target = waypoint(candidate);
        return FlightPathClearance.isClear(bounds, target.subtract(feet), collisions)
                || FlightPathClearance.clearCorner(feet, target, (from, to) ->
                FlightPathClearance.isClear(bounds.move(from.subtract(feet)),
                        to.subtract(from), collisions)) != null;
    }

    // graded room around a cell, zero when nothing is close on any side
    public double clearanceCost(PathPosition position) {
        long key = BlockPos.asLong(position.flooredX(), position.flooredY(), position.flooredZ());
        double cached = clearanceCostCache.get(key);
        if (!Double.isNaN(cached)) return cached;

        AABB body = BODY.move(waypoint(position));
        double cost = 0.0;
        if (!isFree(body.expandTowards(0.0, HEADROOM_PROBE, 0.0))) cost += TIGHT_HEADROOM_COST;
        if (!isFree(body.inflate(NEAR_WALL_PROBE, 0.0, NEAR_WALL_PROBE))) cost += NEAR_WALL_COST;
        else if (!isFree(body.inflate(WIDE_WALL_PROBE, 0.0, WIDE_WALL_PROBE))) cost += WIDE_WALL_COST;

        clearanceCostCache.put(key, cost);
        return cost;
    }

    private boolean isFree(AABB box) {
        return FlightPathClearance.isClear(box, Vec3.ZERO, collisions);
    }

    public static Vec3 waypoint(PathPosition position) {
        return new Vec3(position.flooredX() + 0.5, position.flooredY() + 0.15, position.flooredZ() + 0.5);
    }
}
