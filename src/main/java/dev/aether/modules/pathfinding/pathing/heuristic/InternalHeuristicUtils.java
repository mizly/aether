package dev.aether.modules.pathfinding.pathing.heuristic;

import dev.aether.modules.pathfinding.pathing.PathfindingProgress;

final class InternalHeuristicUtils {
    private static final double EPSILON = 1e-9;

    private InternalHeuristicUtils() {}

    static double calculatePerpendicularDistanceSq(PathfindingProgress progress) {
        double sx = progress.start.centeredX(),   sy = progress.start.centeredY(),   sz = progress.start.centeredZ();
        double cx = progress.current.centeredX(), cy = progress.current.centeredY(), cz = progress.current.centeredZ();
        double tx = progress.target.centeredX(),  ty = progress.target.centeredY(),  tz = progress.target.centeredZ();

        double lineX = tx - sx, lineY = ty - sy, lineZ = tz - sz;
        double lineSq = lineX*lineX + lineY*lineY + lineZ*lineZ;

        if (lineSq < EPSILON) {
            double dx = cx - sx, dy = cy - sy, dz = cz - sz;
            return dx*dx + dy*dy + dz*dz;
        }

        double toX = cx - sx, toY = cy - sy, toZ = cz - sz;
        double crossX = toY*lineZ - toZ*lineY;
        double crossY = toZ*lineX - toX*lineZ;
        double crossZ = toX*lineY - toY*lineX;
        double crossSq = crossX*crossX + crossY*crossY + crossZ*crossZ;

        return crossSq / lineSq;
    }

    static double calculatePerpendicularDistance(PathfindingProgress progress) {
        return Math.sqrt(calculatePerpendicularDistanceSq(progress));
    }
}
