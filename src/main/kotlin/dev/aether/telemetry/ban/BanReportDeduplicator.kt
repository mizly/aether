package dev.aether.telemetry.ban

import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

class BanReportDeduplicator {
    private val windowNanos: Long
    private val acceptedAtNanos: MutableMap<String, Long> = LinkedHashMap()

    constructor() : this(DEFAULT_WINDOW_NANOS)
    constructor(windowNanos: Long) { this.windowNanos = windowNanos }

    @Synchronized
    fun accept(report: BanReport, nowNanos: Long): Boolean {
        prune(nowNanos)
        val key = report.dedupKey()
        val acceptedAt = acceptedAtNanos[key]
        if (acceptedAt != null) {
            val elapsed = nowNanos - acceptedAt
            if (elapsed >= 0L && elapsed <= windowNanos) return false
        }
        acceptedAtNanos[key] = nowNanos
        return true
    }

    @Synchronized fun reset() { acceptedAtNanos.clear() }

    private fun prune(nowNanos: Long) {
        val iterator = acceptedAtNanos.entries.iterator()
        while (iterator.hasNext()) {
            val elapsed = nowNanos - iterator.next().value
            if (elapsed < 0L || elapsed > windowNanos) iterator.remove()
        }
        val oldest = acceptedAtNanos.keys.iterator()
        while (acceptedAtNanos.size > MAX_TRACKED && oldest.hasNext()) {
            oldest.next()
            oldest.remove()
        }
    }

    companion object {
        private val DEFAULT_WINDOW_NANOS: Long = TimeUnit.MINUTES.toNanos(2)
        private const val MAX_TRACKED = 64
    }
}
