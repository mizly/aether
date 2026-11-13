package dev.aether.util

import java.util.function.BooleanSupplier
import java.util.function.Consumer
import java.util.function.LongConsumer
import java.util.function.LongSupplier

class RetryingClickSequence private constructor() {
    class Stage(val name: String, val completed: BooleanSupplier, val click: BooleanSupplier, val timeoutMs: Long) {
        override fun equals(other: Any?): Boolean = other is Stage && name == other.name && completed == other.completed && click == other.click && timeoutMs == other.timeoutMs
        override fun hashCode(): Int = java.util.Objects.hash(name, completed, click, timeoutMs)
        override fun toString(): String = "Stage[name=$name, completed=$completed, click=$click, timeoutMs=$timeoutMs]"
    }

    companion object {
        private const val POLL_INTERVAL_MS = 50L

        @JvmStatic
        fun run(stages: List<Stage>, clickDelayMs: Long, retryDelayMs: Long, cancelled: BooleanSupplier, sleeper: LongConsumer, logger: Consumer<String>): Boolean =
            run(stages, clickDelayMs, retryDelayMs, cancelled, LongSupplier { System.currentTimeMillis() }, sleeper, logger)

        @JvmStatic
        fun run(stages: List<Stage>, clickDelayMs: Long, retryDelayMs: Long, cancelled: BooleanSupplier, clock: LongSupplier, sleeper: LongConsumer, logger: Consumer<String>): Boolean {
            val normalizedRetryDelay = Math.max(POLL_INTERVAL_MS, retryDelayMs)
            for (stage in stages) {
                sleeper.accept(Math.max(0L, clickDelayMs))
                val deadline = clock.asLong + stage.timeoutMs
                var nextClickAt = 0L
                var attempts = 0
                while (!cancelled.asBoolean && !Thread.currentThread().isInterrupted && clock.asLong < deadline) {
                    if (stage.completed.asBoolean) break
                    val now = clock.asLong
                    if (now >= nextClickAt && stage.click.asBoolean) {
                        attempts++
                        logger.accept("Clicked ${stage.name} (attempt $attempts)")
                        nextClickAt = now + normalizedRetryDelay
                        if (stage.completed.asBoolean) break
                    }
                    sleeper.accept(POLL_INTERVAL_MS)
                }
                if (!stage.completed.asBoolean) {
                    logger.accept("Timed out waiting for ${stage.name} after $attempts click attempts")
                    return false
                }
            }
            return true
        }
    }
}
