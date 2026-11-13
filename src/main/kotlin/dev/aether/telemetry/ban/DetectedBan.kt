package dev.aether.telemetry.ban

// parsed punishment text only; never carries a ban id.
class DetectedBan(temporary: Boolean, reason: String?, duration: String?, simulated: Boolean) {
    private val temporary: Boolean = temporary
    private val reason: String = reason ?: ""
    private val duration: String = duration ?: ""
    private val simulated: Boolean = simulated

    fun temporary(): Boolean = temporary
    fun reason(): String = reason
    fun duration(): String = duration
    fun simulated(): Boolean = simulated

    override fun equals(other: Any?): Boolean = other is DetectedBan && temporary == other.temporary && reason == other.reason && duration == other.duration && simulated == other.simulated
    override fun hashCode(): Int = java.util.Objects.hash(temporary, reason, duration, simulated)
    override fun toString(): String = "DetectedBan[temporary=$temporary, reason=$reason, duration=$duration, simulated=$simulated]"
}
