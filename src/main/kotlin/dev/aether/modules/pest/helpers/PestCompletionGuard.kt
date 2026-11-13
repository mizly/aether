package dev.aether.modules.pest.helpers

class PestCompletionGuard private constructor() {
    companion object {
        const val STARTUP_FINISH_GRACE_MS = 5_000L
        const val TAB_FINISH_CONFIRM_TICKS = 10

        @JvmStatic fun isInStartupGrace(activatedAtMs: Long): Boolean =
            isInStartupGrace(activatedAtMs, System.currentTimeMillis())

        internal fun isInStartupGrace(activatedAtMs: Long, nowMs: Long): Boolean =
            nowMs - activatedAtMs < STARTUP_FINISH_GRACE_MS

        @JvmStatic fun isStartupGraceElapsed(activatedAtMs: Long): Boolean =
            !isInStartupGrace(activatedAtMs)

        @JvmStatic fun shouldAcceptFinishReading(activatedAtMs: Long, allowDuringStartup: Boolean): Boolean =
            allowDuringStartup || isStartupGraceElapsed(activatedAtMs)

        @JvmStatic fun isConfirmed(finishReadingTicks: Int): Boolean =
            finishReadingTicks >= TAB_FINISH_CONFIRM_TICKS
    }
}
