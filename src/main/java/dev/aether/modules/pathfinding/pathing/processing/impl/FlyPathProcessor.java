package dev.aether.modules.pathfinding.pathing.processing.impl;

import dev.aether.modules.pathfinding.movement.WalkabilityChecker;
import dev.aether.modules.pathfinding.pathing.processing.Cost;
import dev.aether.modules.pathfinding.pathing.processing.NodeProcessor;
import dev.aether.modules.pathfinding.pathing.processing.context.EvaluationContext;
import dev.aether.modules.pathfinding.wrapper.PathPosition;

/**
 * Flight-specific path validation. Unlike the walking processor, this only
 * requires enough body clearance for the player and does not require floor
 * support.
 */
public final class FlyPathProcessor implements NodeProcessor {
    private static final double WALL_PROXIMITY_COST = 0.12;
    private static final double VERTICAL_COST = 0.08;

    private final WalkabilityChecker checker;

    public FlyPathProcessor(WalkabilityChecker checker) {
        this.checker = checker;
    }

    @Override
    public boolean isValid(EvaluationContext context) {
        if (checker == null) {
            return false;
        }

        PathPosition pos = context.getCurrentPathPosition();
        if (!hasFlightClearance(pos)) {
            return false;
        }

        PathPosition prev = context.getPreviousPathPosition();
        if (prev == null) {
            return true;
        }

        int dx = pos.flooredX() - prev.flooredX();
        int dz = pos.flooredZ() - prev.flooredZ();
        if (Math.abs(dx) == 1 && Math.abs(dz) == 1) {
            return hasFlightClearance(prev.add(dx, 0.0, 0.0))
                    && hasFlightClearance(prev.add(0.0, 0.0, dz));
        }

        return true;
    }

    @Override
    public Cost calculateCostContribution(EvaluationContext context) {
        PathPosition pos = context.getCurrentPathPosition();
        PathPosition prev = context.getPreviousPathPosition();
        if (checker == null || prev == null) {
            return Cost.ZERO;
        }

        double cost = Math.abs(pos.flooredY() - prev.flooredY()) * VERTICAL_COST;

        int x = pos.flooredX();
        int y = pos.flooredY();
        int z = pos.flooredZ();
        cost += countNearbyFullWalls(x, y, z) * WALL_PROXIMITY_COST;
        cost += countNearbyFullWalls(x, y + 1, z) * WALL_PROXIMITY_COST;

        return Cost.of(cost);
    }

    public boolean hasFlightClearance(PathPosition pos) {
        return hasFlightClearance(pos.flooredX(), pos.flooredY(), pos.flooredZ());
    }

    public boolean hasFlightClearance(int x, int y, int z) {
        return isSafePassable(x, y, z) && isSafePassable(x, y + 1, z);
    }

    private boolean isSafePassable(int x, int y, int z) {
        return checker.isPassable(x, y, z) && !checker.isDangerous(x, y, z);
    }

    private int countNearbyFullWalls(int x, int y, int z) {
        int walls = 0;
        if (checker.isFullWallBlock(x + 1, y, z)) walls++;
        if (checker.isFullWallBlock(x - 1, y, z)) walls++;
        if (checker.isFullWallBlock(x, y, z + 1)) walls++;
        if (checker.isFullWallBlock(x, y, z - 1)) walls++;
        return walls;
    }
}
