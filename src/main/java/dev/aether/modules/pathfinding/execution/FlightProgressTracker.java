package dev.aether.modules.pathfinding.execution;

final class FlightProgressTracker {
    private static final double MIN_PROGRESS = 0.15;

    private int waypointIndex = -1;
    private double bestDistance = Double.POSITIVE_INFINITY;
    private long lastProgressTime;

    void reset() {
        waypointIndex = -1;
        bestDistance = Double.POSITIVE_INFINITY;
        lastProgressTime = 0;
    }

    long stalledFor(int waypointIndex, double distance, long now) {
        if (this.waypointIndex != waypointIndex || distance <= bestDistance - MIN_PROGRESS) {
            this.waypointIndex = waypointIndex;
            bestDistance = distance;
            lastProgressTime = now;
        }
        return now - lastProgressTime;
    }
}
