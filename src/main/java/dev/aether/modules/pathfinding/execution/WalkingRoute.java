package dev.aether.modules.pathfinding.execution;

import net.minecraft.world.phys.Vec3;

import java.util.List;

final class WalkingRoute {
    private static final double HEIGHT_TOLERANCE = 0.65;
    private static final double WAYPOINT_RADIUS = 0.25;
    private static final double ROUTE_HALF_WIDTH = 0.45;
    private static final double FALL_CORRIDOR_RADIUS = 0.75;

    private final List<Vec3> points;
    private final double[] distances;

    WalkingRoute(List<Vec3> points) {
        this.points = List.copyOf(points);
        distances = new double[points.size()];
        for (int i = 1; i < points.size(); i++) {
            distances[i] = distances[i - 1] + points.get(i).distanceTo(points.get(i - 1));
        }
    }

    int advance(Vec3 feet, int segment) {
        while (segment + 1 < points.size()) {
            Vec3 from = points.get(segment);
            Vec3 to = points.get(segment + 1);
            boolean heightReached = to.y < from.y
                    ? feet.y <= to.y + HEIGHT_TOLERANCE : feet.y >= to.y - HEIGHT_TOLERANCE;
            if (!heightReached) {
                if (to.y > from.y || !fallingPast(feet, segment + 1)) break;
            } else if (!passedHorizontally(feet, from, to)) {
                break;
            }
            segment++;
        }
        return segment;
    }

    double progress(Vec3 feet, int segment) {
        if (points.isEmpty()) return 0.0;
        if (segment + 1 >= points.size()) return distances[distances.length - 1];
        Vec3 from = points.get(segment);
        Vec3 to = points.get(segment + 1);
        Vec3 delta = to.subtract(from);
        double t = useHeightForProjection(delta)
                ? (delta.lengthSqr() < 1.0e-12 ? 0.0 : feet.subtract(from).dot(delta) / delta.lengthSqr())
                : horizontalProjection(feet, from, to);
        return distances[segment] + Math.clamp(t, 0.0, 1.0) * (distances[segment + 1] - distances[segment]);
    }

    Vec3 steeringTarget(Vec3 feet, int segment, double lookahead) {
        if (points.isEmpty()) return feet;
        if (segment + 1 >= points.size()) return points.getLast();
        Vec3 from = points.get(segment);
        Vec3 to = points.get(segment + 1);
        Vec3 direction = to.subtract(from).multiply(1.0, 0.0, 1.0);
        double length = direction.length();
        if (length < 1.0e-6) return to;
        double remaining = Math.clamp(horizontalProjection(feet, from, to), 0.0, 1.0) * length
                + Math.max(0.0, lookahead);
        while (true) {
            if (to.y < from.y && feet.y > to.y + HEIGHT_TOLERANCE) return to;
            if (remaining < length) return from.lerp(to, remaining / length);
            if (segment + 2 >= points.size()) return to;
            Vec3 next = points.get(segment + 2);
            Vec3 nextDirection = next.subtract(to).multiply(1.0, 0.0, 1.0);
            double nextLength = nextDirection.length();
            if (nextLength < 1.0e-6 || direction.dot(nextDirection) < length * nextLength * 0.99) return to;
            remaining -= length;
            segment++;
            from = to;
            to = next;
            direction = nextDirection;
            length = nextLength;
        }
    }

    static boolean useHeightForProjection(Vec3 direction) {
        return direction.y < -1.0e-6 || direction.horizontalDistanceSqr() < 1.0e-12;
    }

    private boolean fallingPast(Vec3 feet, int waypoint) {
        if (feet.y >= points.get(waypoint).y - HEIGHT_TOLERANCE) return false;
        for (int i = waypoint; i + 1 < points.size(); i++) {
            Vec3 from = points.get(i);
            Vec3 to = points.get(i + 1);
            if (to.y > from.y) return false;
            if (to.y < from.y && feet.y >= to.y - HEIGHT_TOLERANCE) {
                double t = Math.clamp(horizontalProjection(feet, from, to), 0.0, 1.0);
                return feet.subtract(from.lerp(to, t)).horizontalDistance() <= FALL_CORRIDOR_RADIUS;
            }
            if (!passedHorizontally(feet, from, to)) return false;
        }
        return false;
    }

    private static boolean passedHorizontally(Vec3 feet, Vec3 from, Vec3 to) {
        double horizontalLength = to.subtract(from).horizontalDistanceSqr();
        double radius = to.y < from.y && horizontalLength < 1.0e-12 ? FALL_CORRIDOR_RADIUS : WAYPOINT_RADIUS;
        return feet.subtract(to).horizontalDistance() <= radius
                || (horizontalLength > 1.0e-12
                && horizontalProjection(feet, from, to) >= 1.0
                && lateralDistance(feet, from, to) <= ROUTE_HALF_WIDTH);
    }

    double distance(Vec3 feet, int segment) {
        if (points.isEmpty()) return 0.0;
        double best = Double.POSITIVE_INFINITY;
        int start = Math.max(0, segment - 1);
        int end = Math.min(points.size() - 1, segment + 2);
        for (int i = start; i < end; i++) {
            Vec3 from = points.get(i);
            Vec3 to = points.get(i + 1);
            double t = Math.clamp(horizontalProjection(feet, from, to), 0.0, 1.0);
            best = Math.min(best, feet.subtract(from.lerp(to, t)).horizontalDistance());
        }
        return Double.isFinite(best) ? best : feet.subtract(points.getLast()).horizontalDistance();
    }

    static boolean reachedGoal(Vec3 feet, Vec3 goal, double tolerance) {
        return feet.subtract(goal).horizontalDistance() <= tolerance && Math.abs(feet.y - goal.y) <= 0.75;
    }

    boolean endsAt(Vec3 goal) {
        if (points.isEmpty()) return false;
        Vec3 end = points.getLast();
        return Math.floor(end.x) == Math.floor(goal.x)
                && Math.floor(end.y) == Math.floor(goal.y)
                && Math.floor(end.z) == Math.floor(goal.z);
    }

    private static double horizontalProjection(Vec3 feet, Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double lengthSquared = dx * dx + dz * dz;
        return lengthSquared < 1.0e-6 ? 0.0
                : ((feet.x - from.x) * dx + (feet.z - from.z) * dz) / lengthSquared;
    }

    private static double lateralDistance(Vec3 feet, Vec3 from, Vec3 to) {
        return Math.abs((feet.x - from.x) * (to.z - from.z) - (feet.z - from.z) * (to.x - from.x))
                / to.subtract(from).horizontalDistance();
    }
}
