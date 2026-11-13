package dev.aether.modules.failsafe

// Not cleared by FailsafeManager.reset(): a STOP-action failsafe stops the macro, which
// resets the failsafes, and that would erase the very trigger we need to report.
class FailsafeTriggerHistory private constructor() {
    companion object {
        @JvmStatic private var triggered = false
        @JvmStatic private var lastTriggerNanos = 0L
        @JvmStatic private var lastTriggerDetails = ""

        @JvmStatic
        fun recordTrigger(details: String?) {
            recordTriggerAt(System.nanoTime(), details)
        }

        @JvmStatic
        fun recordTriggerAt(nanos: Long, details: String?) {
            lastTriggerNanos = nanos
            lastTriggerDetails = details?.trim() ?: ""
            triggered = true
        }

        @JvmStatic
        fun triggeredWithin(nowNanos: Long, windowNanos: Long): Boolean {
            if (!triggered) return false
            val elapsed = nowNanos - lastTriggerNanos
            return elapsed >= 0L && elapsed <= windowNanos
        }

        @JvmStatic
        fun getLastTriggerDetails(): String = if (triggered) lastTriggerDetails else ""

        @JvmStatic
        fun clearForTesting() {
            triggered = false
            lastTriggerNanos = 0L
            lastTriggerDetails = ""
        }
    }
}
