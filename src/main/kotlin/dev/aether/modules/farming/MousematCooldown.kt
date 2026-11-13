package dev.aether.modules.farming

import java.util.concurrent.TimeUnit
import java.util.function.BooleanSupplier
import java.util.function.LongPredicate
import java.util.function.LongSupplier

internal class MousematCooldown(private val clock: LongSupplier) {
    private var lastUseNanos: Long = 0
    private var used: Boolean = false

    @Synchronized
    fun recordUse() {
        lastUseNanos = clock.asLong
        used = true
    }

    @Synchronized
    fun remainingMillis(): Long {
        if (!used) return 0
        val remaining = COOLDOWN_NANOS - (clock.asLong - lastUseNanos)
        return if (remaining <= 0) 0 else (remaining + 999_999L) / 1_000_000L
    }

    fun await(shouldAbort: BooleanSupplier, sleep: LongPredicate): Boolean {
        while (!shouldAbort.asBoolean) {
            val remaining = remainingMillis()
            if (remaining == 0L) return true
            if (!sleep.test(Math.min(remaining, POLL_MILLIS))) return false
        }
        return false
    }

    companion object {
        private val COOLDOWN_NANOS: Long = TimeUnit.SECONDS.toNanos(3)
        private const val POLL_MILLIS: Long = 50L
    }
}
