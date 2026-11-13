package dev.aether.modules.pathfinding.execution

internal class FlightProgressTracker {
    private var waypointIndex = -1
    private var bestDistance = Double.POSITIVE_INFINITY
    private var lastProgressTime: Long = 0

    fun reset() {
        waypointIndex = -1
        bestDistance = Double.POSITIVE_INFINITY
        lastProgressTime = 0
    }

    fun stalledFor(waypointIndex: Int, distance: Double, now: Long): Long {
        if (this.waypointIndex != waypointIndex || distance <= bestDistance - MIN_PROGRESS) {
            this.waypointIndex = waypointIndex
            bestDistance = distance
            lastProgressTime = now
        }
        return now - lastProgressTime
    }

    companion object {
        private const val MIN_PROGRESS = 0.15
    }
}
