package dev.aether.hud

import java.util.Locale

internal class ProfitGraphScale {
    class Bounds(private val min: Double, private val max: Double) {
        fun min(): Double = min
        fun max(): Double = max
        fun fraction(value: Double): Double = (value - min) / (max - min)
        override fun equals(other: Any?): Boolean = other is Bounds && min == other.min && max == other.max
        override fun hashCode(): Int = java.util.Objects.hash(min, max)
        override fun toString(): String = "Bounds[min=$min, max=$max]"
    }
    private var bounds: Bounds? = null
    private var lastNanos = 0L
    fun update(min: Double, max: Double, nowNanos: Long, reset: Boolean): Bounds {
        val target = target(min, max)
        val current = bounds
        bounds = if (current == null || reset) target else {
            val seconds = maxOf(0L, nowNanos - lastNanos) / 1_000_000_000.0
            val blend = -Math.expm1(-seconds / 0.6)
            val low = current.min() + (target.min() - current.min()) * blend
            val high = current.max() + (target.max() - current.max()) * blend
            Bounds(minOf(low, min), maxOf(high, max))
        }
        lastNanos = nowNanos
        return bounds ?: target
    }
    companion object {
        @JvmStatic fun target(min: Double, max: Double): Bounds {
            val precision = Math.ulp(maxOf(kotlin.math.abs(min), kotlin.math.abs(max))) * 8
            val padding = maxOf(5.0, precision, (max - min) * 0.12)
            val low = if (min >= 0) 0.0 else min - padding
            var high = if (max <= 0) 0.0 else max + padding
            if (high <= low) high = low + 10
            val step = tickStep(high - low)
            return Bounds(kotlin.math.floor(low / step) * step, kotlin.math.ceil(high / step) * step)
        }
        @JvmStatic fun tickStep(span: Double): Double {
            val rough = maxOf(1.0, span / 4)
            val power = Math.pow(10.0, kotlin.math.floor(Math.log10(rough)))
            val fraction = rough / power
            return power * when {
                fraction <= 1 -> 1.0
                fraction <= 2 -> 2.0
                fraction <= 5 -> 5.0
                else -> 10.0
            }
        }
        @JvmStatic fun label(value: Double, step: Double): String {
            if (kotlin.math.abs(value) < step * 0.001) return "0"
            val magnitude = kotlin.math.abs(value)
            val divisor = when {
                magnitude >= 1e12 -> 1e12
                magnitude >= 1e9 -> 1e9
                magnitude >= 1e6 -> 1e6
                magnitude >= 1e3 -> 1e3
                else -> 1.0
            }
            val suffix = when (divisor) {
                1e12 -> "T"
                1e9 -> "B"
                1e6 -> "M"
                1e3 -> "k"
                else -> ""
            }
            val decimals = maxOf(0.0, kotlin.math.ceil(-Math.log10(step / divisor))).toInt()
            if (decimals > 3) return String.format(Locale.ROOT, "%.0f", value)
            return String.format(Locale.ROOT, "%." + decimals + "f%s", value / divisor, suffix)
        }
    }
}
