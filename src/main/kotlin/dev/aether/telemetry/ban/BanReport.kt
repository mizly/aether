package dev.aether.telemetry.ban

import com.google.gson.JsonObject

class BanReport(server: String?, temporary: Boolean, reason: String?, duration: String?, macroActiveAtDisconnect: Boolean, failsafeTriggeredWithinLast5Minutes: Boolean) {
    private val server: String = server ?: ""
    private val temporary: Boolean = temporary
    private val reason: String = reason ?: ""
    private val duration: String = duration ?: ""
    private val macroActiveAtDisconnect: Boolean = macroActiveAtDisconnect
    private val failsafeTriggeredWithinLast5Minutes: Boolean = failsafeTriggeredWithinLast5Minutes

    fun server(): String = server
    fun temporary(): Boolean = temporary
    fun reason(): String = reason
    fun duration(): String = duration
    fun macroActiveAtDisconnect(): Boolean = macroActiveAtDisconnect
    fun failsafeTriggeredWithinLast5Minutes(): Boolean = failsafeTriggeredWithinLast5Minutes

    fun toJson(): JsonObject {
        val payload = JsonObject()
        payload.addProperty("server", server)
        payload.addProperty("reason", reason)
        payload.addProperty("temporary", temporary)
        payload.addProperty("duration", duration)
        payload.addProperty("macro_active", macroActiveAtDisconnect)
        payload.addProperty("failsafe_recent", failsafeTriggeredWithinLast5Minutes)
        return payload
    }

    fun dedupKey(): String = server + '|' + temporary + '|' + reason + '|' + duration

    override fun equals(other: Any?): Boolean = other is BanReport && server == other.server && temporary == other.temporary && reason == other.reason && duration == other.duration && macroActiveAtDisconnect == other.macroActiveAtDisconnect && failsafeTriggeredWithinLast5Minutes == other.failsafeTriggeredWithinLast5Minutes
    override fun hashCode(): Int = java.util.Objects.hash(server, temporary, reason, duration, macroActiveAtDisconnect, failsafeTriggeredWithinLast5Minutes)
    override fun toString(): String = "BanReport[server=$server, temporary=$temporary, reason=$reason, duration=$duration, macroActiveAtDisconnect=$macroActiveAtDisconnect, failsafeTriggeredWithinLast5Minutes=$failsafeTriggeredWithinLast5Minutes]"

    companion object {
        @JvmStatic
        fun of(server: String?, ban: DetectedBan, context: BanDisconnectContext): BanReport = BanReport(server, ban.temporary(), ban.reason(), ban.duration(), context.macroActiveAtDisconnect(), context.failsafeTriggeredWithinLast5Minutes())
    }
}
