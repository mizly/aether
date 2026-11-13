package dev.aether.ui

internal class SelectionAnimation {
    private var value = 0.0
    private var velocity = 0.0
    private var lastNanos = 0L
    private var initialized = false

    fun update(target: Float, durationMs: Float, nowNanos: Long): Float {
        if (!initialized) {
            reset(target, nowNanos)
            return target
        }
        val seconds = Math.max(0L, nowNanos - lastNanos) / 1_000_000_000.0
        lastNanos = nowNanos
        val omega = 6_000.0 / Math.max(1.0, durationMs.toDouble())
        val offset = value - target
        val decay = Math.exp(-omega * seconds)
        val step = (velocity + omega * offset) * seconds
        value = target + (offset + step) * decay
        velocity = (velocity - omega * step) * decay
        if (Math.abs(value - target) < 0.01 && Math.abs(velocity) < 0.01) {
            value = target.toDouble()
            velocity = 0.0
        }
        return value.toFloat()
    }

    fun reset(target: Float, nowNanos: Long) {
        value = target.toDouble()
        velocity = 0.0
        lastNanos = nowNanos
        initialized = true
    }
}
