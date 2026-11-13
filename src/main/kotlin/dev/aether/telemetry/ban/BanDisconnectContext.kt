package dev.aether.telemetry.ban

import dev.aether.modules.failsafe.FailsafeTriggerHistory

class BanDisconnectContext(macroActiveAtDisconnect: Boolean, failsafeTriggeredWithinLast5Minutes: Boolean, failsafeTriggeredWithinLast10Minutes: Boolean, lastTriggeredFailsafe: String?) {
    private val macroActiveAtDisconnect: Boolean = macroActiveAtDisconnect
    private val failsafeTriggeredWithinLast5Minutes: Boolean = failsafeTriggeredWithinLast5Minutes
    private val failsafeTriggeredWithinLast10Minutes: Boolean = failsafeTriggeredWithinLast10Minutes
    private val lastTriggeredFailsafe: String = lastTriggeredFailsafe ?: ""

    fun macroActiveAtDisconnect(): Boolean = macroActiveAtDisconnect
    fun failsafeTriggeredWithinLast5Minutes(): Boolean = failsafeTriggeredWithinLast5Minutes
    fun failsafeTriggeredWithinLast10Minutes(): Boolean = failsafeTriggeredWithinLast10Minutes
    fun lastTriggeredFailsafe(): String = lastTriggeredFailsafe

    fun eligibleForReport(): Boolean = macroActiveAtDisconnect || failsafeTriggeredWithinLast10Minutes

    override fun equals(other: Any?): Boolean = other is BanDisconnectContext && macroActiveAtDisconnect == other.macroActiveAtDisconnect && failsafeTriggeredWithinLast5Minutes == other.failsafeTriggeredWithinLast5Minutes && failsafeTriggeredWithinLast10Minutes == other.failsafeTriggeredWithinLast10Minutes && lastTriggeredFailsafe == other.lastTriggeredFailsafe
    override fun hashCode(): Int = java.util.Objects.hash(macroActiveAtDisconnect, failsafeTriggeredWithinLast5Minutes, failsafeTriggeredWithinLast10Minutes, lastTriggeredFailsafe)
    override fun toString(): String = "BanDisconnectContext[macroActiveAtDisconnect=$macroActiveAtDisconnect, failsafeTriggeredWithinLast5Minutes=$failsafeTriggeredWithinLast5Minutes, failsafeTriggeredWithinLast10Minutes=$failsafeTriggeredWithinLast10Minutes, lastTriggeredFailsafe=$lastTriggeredFailsafe]"

    companion object {
        const val FAILSAFE_WINDOW_SECONDS: Long = 5L * 60L
        const val FAILSAFE_WINDOW_NANOS: Long = FAILSAFE_WINDOW_SECONDS * 1_000_000_000L
        const val ELIGIBILITY_WINDOW_SECONDS: Long = 10L * 60L
        const val ELIGIBILITY_WINDOW_NANOS: Long = ELIGIBILITY_WINDOW_SECONDS * 1_000_000_000L

        @JvmStatic fun capture(macroActive: Boolean): BanDisconnectContext = capture(macroActive, System.nanoTime())
        @JvmStatic
        fun capture(macroActive: Boolean, nowNanos: Long): BanDisconnectContext = BanDisconnectContext(
            macroActive,
            FailsafeTriggerHistory.triggeredWithin(nowNanos, FAILSAFE_WINDOW_NANOS),
            FailsafeTriggerHistory.triggeredWithin(nowNanos, ELIGIBILITY_WINDOW_NANOS),
            FailsafeTriggerHistory.getLastTriggerDetails()
        )
    }
}
