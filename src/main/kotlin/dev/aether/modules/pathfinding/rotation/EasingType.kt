package dev.aether.modules.pathfinding.rotation

import java.util.function.Function

enum class EasingType(private val ease: Function<Float, Float>) {

    LINEAR(Function { t -> t }),
    EASE_IN_SINE(Function { t -> (1 - Math.cos(t.toDouble() * Math.PI / 2)).toFloat() }),
    EASE_OUT_SINE(Function { t -> Math.sin(t.toDouble() * Math.PI / 2).toFloat() }),
    EASE_IN_OUT_SINE(Function { t -> (-0.5f * (Math.cos(Math.PI * t.toDouble()) - 1)).toFloat() }),
    EASE_IN_QUAD(Function { t -> t * t }),
    EASE_OUT_QUAD(Function { t -> t * (2 - t) }),
    EASE_IN_OUT_QUAD(Function { t -> if (t < 0.5f) 2 * t * t else -1 + (4 - 2 * t) * t }),
    EASE_IN_CUBIC(Function { t -> t * t * t }),
    EASE_OUT_CUBIC(Function { t ->
        val x = t - 1
        x * x * x + 1
    }),
    EASE_IN_OUT_CUBIC(Function { t -> if (t < 0.5f) 4 * t * t * t else (t - 1) * (2 * t - 2) * (2 * t - 2) + 1 }),
    EASE_IN_QUART(Function { t -> t * t * t * t }),
    EASE_OUT_QUART(Function { t ->
        val x = t - 1
        1 - x * x * x * x
    }),
    EASE_IN_OUT_QUART(Function { t ->
        if (t < 0.5f) 8 * t * t * t * t else 1 - 8 * (t - 1) * (t - 1) * (t - 1) * (t - 1)
    }),
    EASE_IN_QUINT(Function { t -> t * t * t * t * t }),
    EASE_OUT_QUINT(Function { t ->
        val x = t - 1
        1 + x * x * x * x * x
    }),
    EASE_IN_OUT_QUINT(Function { t ->
        if (t < 0.5f) 16 * t * t * t * t * t else 1 + 16 * (t - 1) * (t - 1) * (t - 1) * (t - 1) * (t - 1)
    }),
    EASE_IN_EXPO(Function { t -> if (t == 0f) 0f else Math.pow(2.0, (10 * (t - 1)).toDouble()).toFloat() }),
    EASE_OUT_EXPO(Function { t -> if (t == 1f) 1f else 1 - Math.pow(2.0, (-10 * t).toDouble()).toFloat() }),
    EASE_IN_OUT_EXPO(Function { t ->
        if (t == 0f) return@Function 0f
        if (t == 1f) return@Function 1f
        if (t < 0.5f) (Math.pow(2.0, (20 * t - 10).toDouble()) / 2).toFloat()
        else (2 - Math.pow(2.0, (-20 * t + 10).toDouble()) / 2).toFloat()
    }),
    EASE_IN_CIRC(Function { t -> (1 - Math.sqrt(1 - t * t.toDouble())).toFloat() }),
    EASE_OUT_CIRC(Function { t -> Math.sqrt(1 - (t - 1) * (t - 1).toDouble()).toFloat() }),
    EASE_IN_OUT_CIRC(Function { t ->
        if (t < 0.5f) ((1 - Math.sqrt(1 - (2 * t) * (2 * t).toDouble())) / 2).toFloat()
        else ((Math.sqrt(1 - (-2 * t + 2) * (-2 * t + 2).toDouble()) + 1) / 2).toFloat()
    }),
    EASE_IN_BACK(Function { t ->
        val c1 = 1.70158f
        val c3 = c1 + 1
        c3 * t * t * t - c1 * t * t
    }),
    EASE_OUT_BACK(Function { t ->
        val c1 = 1.70158f
        val c3 = c1 + 1
        val x = t - 1
        1 + c3 * x * x * x + c1 * x * x
    }),
    EASE_IN_OUT_BACK(Function { t ->
        val c1 = 1.70158f
        val c2 = c1 * 1.525f
        if (t < 0.5f) {
            val k = 2 * t
            (k * k * ((c2 + 1) * k - c2)) / 2
        } else {
            val k = 2 * t - 2
            (k * k * ((c2 + 1) * k + c2) + 2) / 2
        }
    });

    fun ease(t: Float): Float = ease.apply(maxOf(0f, minOf(1f, t)))

    fun apply(from: Float, to: Float, progress: Float): Float = from + (to - from) * ease(progress)
}
