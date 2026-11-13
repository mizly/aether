package dev.aether.modules.pathfinding.execution

import net.minecraft.world.phys.Vec3

internal class WalkingRoute(points: List<Vec3>) {
    private val points: List<Vec3> = java.util.List.copyOf(points)
    private val distances = DoubleArray(points.size)

    init {
        for (i in 1 until points.size) distances[i] = distances[i - 1] + points[i].distanceTo(points[i - 1])
    }

    fun advance(feet: Vec3, segmentStart: Int): Int {
        var segment = segmentStart
        while (segment + 1 < points.size) {
            val from = points[segment]
            val to = points[segment + 1]
            val heightReached = if (to.y < from.y) feet.y <= to.y + HEIGHT_TOLERANCE else feet.y >= to.y - HEIGHT_TOLERANCE
            if (!heightReached) {
                if (to.y > from.y || !fallingPast(feet, segment + 1)) break
            } else if (!passedHorizontally(feet, from, to)) break
            segment++
        }
        return segment
    }

    fun progress(feet: Vec3, segment: Int): Double {
        if (points.isEmpty()) return 0.0
        if (segment + 1 >= points.size) return distances[distances.size - 1]
        val from = points[segment]
        val to = points[segment + 1]
        val delta = to.subtract(from)
        val t = if (useHeightForProjection(delta)) {
            if (delta.lengthSqr() < 1.0e-12) 0.0 else feet.subtract(from).dot(delta) / delta.lengthSqr()
        } else horizontalProjection(feet, from, to)
        return distances[segment] + Math.clamp(t, 0.0, 1.0) * (distances[segment + 1] - distances[segment])
    }

    fun steeringTarget(feet: Vec3, segmentStart: Int, lookahead: Double): Vec3 {
        var segment = segmentStart
        if (points.isEmpty()) return feet
        if (segment + 1 >= points.size) return points.last()
        var from = points[segment]
        var to = points[segment + 1]
        var direction = to.subtract(from).multiply(1.0, 0.0, 1.0)
        var length = direction.length()
        if (length < 1.0e-6) return to
        var remaining = Math.clamp(horizontalProjection(feet, from, to), 0.0, 1.0) * length + Math.max(0.0, lookahead)
        while (true) {
            if (to.y < from.y && feet.y > to.y + HEIGHT_TOLERANCE) return to
            if (remaining < length) return from.lerp(to, remaining / length)
            if (segment + 2 >= points.size) return to
            val next = points[segment + 2]
            val nextDirection = next.subtract(to).multiply(1.0, 0.0, 1.0)
            val nextLength = nextDirection.length()
            if (nextLength < 1.0e-6 || direction.dot(nextDirection) < length * nextLength * 0.99) return to
            remaining -= length
            segment++
            from = to
            to = next
            direction = nextDirection
            length = nextLength
        }
    }

    private fun fallingPast(feet: Vec3, waypoint: Int): Boolean {
        if (feet.y >= points[waypoint].y - HEIGHT_TOLERANCE) return false
        for (i in waypoint until points.size - 1) {
            val from = points[i]
            val to = points[i + 1]
            if (to.y > from.y) return false
            if (to.y < from.y && feet.y >= to.y - HEIGHT_TOLERANCE) {
                val t = Math.clamp(horizontalProjection(feet, from, to), 0.0, 1.0)
                return feet.subtract(from.lerp(to, t)).horizontalDistance() <= FALL_CORRIDOR_RADIUS
            }
            if (!passedHorizontally(feet, from, to)) return false
        }
        return false
    }

    companion object {
        private const val HEIGHT_TOLERANCE = 0.65
        private const val WAYPOINT_RADIUS = 0.25
        private const val ROUTE_HALF_WIDTH = 0.45
        private const val FALL_CORRIDOR_RADIUS = 0.75

        @JvmStatic
        fun useHeightForProjection(direction: Vec3): Boolean = direction.y < -1.0e-6 || direction.horizontalDistanceSqr() < 1.0e-12

        private fun passedHorizontally(feet: Vec3, from: Vec3, to: Vec3): Boolean {
            val horizontalLength = to.subtract(from).horizontalDistanceSqr()
            val radius = if (to.y < from.y && horizontalLength < 1.0e-12) FALL_CORRIDOR_RADIUS else WAYPOINT_RADIUS
            return feet.subtract(to).horizontalDistance() <= radius || (horizontalLength > 1.0e-12 && horizontalProjection(feet, from, to) >= 1.0 && lateralDistance(feet, from, to) <= ROUTE_HALF_WIDTH)
        }

        @JvmStatic
        fun reachedGoal(feet: Vec3, goal: Vec3, tolerance: Double): Boolean = feet.subtract(goal).horizontalDistance() <= tolerance && Math.abs(feet.y - goal.y) <= 0.75

        private fun horizontalProjection(feet: Vec3, from: Vec3, to: Vec3): Double {
            val dx = to.x - from.x
            val dz = to.z - from.z
            val lengthSquared = dx * dx + dz * dz
            return if (lengthSquared < 1.0e-6) 0.0 else ((feet.x - from.x) * dx + (feet.z - from.z) * dz) / lengthSquared
        }

        private fun lateralDistance(feet: Vec3, from: Vec3, to: Vec3): Double =
            Math.abs((feet.x - from.x) * (to.z - from.z) - (feet.z - from.z) * (to.x - from.x)) / to.subtract(from).horizontalDistance()
    }

    fun distance(feet: Vec3, segment: Int): Double {
        if (points.isEmpty()) return 0.0
        var best = Double.POSITIVE_INFINITY
        val start = Math.max(0, segment - 1)
        val end = Math.min(points.size - 1, segment + 2)
        for (i in start until end) {
            val from = points[i]
            val to = points[i + 1]
            val t = Math.clamp(horizontalProjection(feet, from, to), 0.0, 1.0)
            best = Math.min(best, feet.subtract(from.lerp(to, t)).horizontalDistance())
        }
        return if (java.lang.Double.isFinite(best)) best else feet.subtract(points.last()).horizontalDistance()
    }

    fun endsAt(goal: Vec3): Boolean {
        if (points.isEmpty()) return false
        val end = points.last()
        return Math.floor(end.x) == Math.floor(goal.x) && Math.floor(end.y) == Math.floor(goal.y) && Math.floor(end.z) == Math.floor(goal.z)
    }
}
