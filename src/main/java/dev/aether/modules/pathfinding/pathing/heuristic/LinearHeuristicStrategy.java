package dev.aether.modules.pathfinding.pathing.heuristic;

import dev.aether.modules.pathfinding.pathing.PathfindingProgress;
import dev.aether.modules.pathfinding.wrapper.PathPosition;

public final class LinearHeuristicStrategy implements IHeuristicStrategy {
    private static final double D1 = 1.0;
    private static final double D2 = Math.sqrt(2.0);
    private static final double D3 = Math.sqrt(3.0);

    @Override
    public double calculate(HeuristicContext context) {
        PathfindingProgress progress = context.pathfindingProgress;
        HeuristicWeights weights = context.heuristicWeights;

        PathPosition position = progress.current;
        PathPosition target   = progress.target;

        int ax = Math.abs(position.flooredX() - target.flooredX());
        int ay = Math.abs(position.flooredY() - target.flooredY());
        int az = Math.abs(position.flooredZ() - target.flooredZ());

        double manhattan = ax + ay + az;

        int min = Math.min(ax, Math.min(ay, az));
        int max = Math.max(ax, Math.max(ay, az));
        int mid = ax + ay + az - min - max;
        double octile = (D3 - D2) * min + (D2 - D1) * mid + D1 * max;

        double perpendicular = InternalHeuristicUtils.calculatePerpendicularDistance(progress);
        double height = (double) Math.abs(position.flooredY() - target.flooredY());

        return manhattan     * weights.manhattanWeight
             + octile        * weights.octileWeight
             + perpendicular * weights.perpendicularWeight
             + height        * weights.heightWeight;
    }

    @Override
    public double calculateTransitionCost(PathPosition from, PathPosition to) {
        double dx = to.centeredX() - from.centeredX();
        double dy = to.centeredY() - from.centeredY();
        double dz = to.centeredZ() - from.centeredZ();
        return Math.sqrt(dx*dx + dy*dy + dz*dz);
    }
}
