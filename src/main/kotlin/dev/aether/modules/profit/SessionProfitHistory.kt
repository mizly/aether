package dev.aether.modules.profit

import java.util.ArrayDeque

class SessionProfitHistory {
    companion object {
        const val MAX_WINDOW_MILLIS = 15 * 60_000L
        private const val BUCKET_NANOS = 1_000_000_000L
    }

    class Point(private val timeMillisValue: Double, private val coinsValue: Long) {
        fun timeMillis(): Double = timeMillisValue
        fun coins(): Long = coinsValue
    }

    class Snapshot(
        private val elapsedMillisValue: Double,
        private val pointsValue: List<Point>,
        private val generationValue: Long
    ) {
        fun elapsedMillis(): Double = elapsedMillisValue
        fun points(): List<Point> = pointsValue
        fun generation(): Long = generationValue
    }

    private val buckets = ArrayDeque<Bucket>()
    private var elapsedNanos = 0L
    private var lastNanos = 0L
    private var generation = 0L
    private var initialized = false
    private var running = false
    private var cachedPoints: List<Point>? = null

    @Synchronized
    fun reset(nowNanos: Long, active: Boolean) {
        buckets.clear()
        elapsedNanos = 0
        lastNanos = nowNanos
        initialized = true
        running = active
        generation++
        buckets.add(Bucket(0, Point(0.0, 0)))
        cachedPoints = null
    }

    @Synchronized
    fun setRunning(nowNanos: Long, active: Boolean) {
        advance(nowNanos)
        running = active
    }

    @Synchronized
    fun record(nowNanos: Long, coins: Long) {
        advance(nowNanos)
        val last = buckets.peekLast()
        if (last == null || last.last.coins() != coins) {
            val point = Point(elapsedNanos / 1_000_000.0, coins)
            val second = elapsedNanos / BUCKET_NANOS
            if (last == null || last.second != second) buckets.addLast(Bucket(second, point)) else last.add(point)
            cachedPoints = null
        }
        val cutoff = elapsedNanos / 1_000_000.0 - MAX_WINDOW_MILLIS
        while (buckets.size > 1) {
            val first = buckets.removeFirst()
            if (buckets.first.last.timeMillis() >= cutoff) {
                buckets.addFirst(first)
                break
            }
            cachedPoints = null
        }
    }

    @Synchronized
    fun snapshot(nowNanos: Long): Snapshot {
        var points = cachedPoints
        if (points == null) {
            points = buildList(buckets.size * 4) { buckets.forEach { it.appendTo(this) } }
            cachedPoints = points
        }
        val extra = if (initialized && running) maxOf(0, nowNanos - lastNanos) else 0
        return Snapshot((elapsedNanos + extra) / 1_000_000.0, points, generation)
    }

    private fun advance(nowNanos: Long) {
        if (!initialized) {
            reset(nowNanos, false)
            return
        }
        val delta = nowNanos - lastNanos
        if (delta < 0) return
        if (running) elapsedNanos += delta
        lastNanos = nowNanos
    }

    private class Bucket(val second: Long, point: Point) {
        private val first = point
        private var min = point
        private var max = point
        var last = point
            private set

        fun add(point: Point) {
            if (point.coins() < min.coins()) min = point
            if (point.coins() > max.coins()) max = point
            last = point
        }

        fun appendTo(points: MutableList<Point>) {
            val ordered = listOf(first, min, max, last).sortedBy { it.timeMillis() }
            var previous: Point? = null
            for (point in ordered) {
                if (point !== previous) points.add(point)
                previous = point
            }
        }
    }
}
