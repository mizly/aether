package dev.aether.modules.pathfinding.rotation;

import java.util.function.Function;

/**
 * 28 standard easing functions
 */
public enum EasingType {

    LINEAR           (t -> t),
    EASE_IN_SINE     (t -> (float)(1 - Math.cos(t * Math.PI / 2))),
    EASE_OUT_SINE    (t -> (float) Math.sin(t * Math.PI / 2)),
    EASE_IN_OUT_SINE (t -> (float)(-0.5f * (Math.cos(Math.PI * t) - 1))),
    EASE_IN_QUAD     (t -> t * t),
    EASE_OUT_QUAD    (t -> t * (2 - t)),
    EASE_IN_OUT_QUAD (t -> t < 0.5f ? 2 * t * t : -1 + (4 - 2 * t) * t),
    EASE_IN_CUBIC    (t -> t * t * t),
    EASE_OUT_CUBIC   (t -> { float x = t - 1; return x * x * x + 1; }),
    EASE_IN_OUT_CUBIC(t -> t < 0.5f ? 4 * t * t * t : (t - 1) * (2*t-2) * (2*t-2) + 1),
    EASE_IN_QUART    (t -> t * t * t * t),
    EASE_OUT_QUART   (t -> { float x = t - 1; return 1 - x * x * x * x; }),
    EASE_IN_OUT_QUART(t -> t < 0.5f ? 8 * t * t * t * t : 1 - 8 * (t-1)*(t-1)*(t-1)*(t-1)),
    EASE_IN_QUINT    (t -> t * t * t * t * t),
    EASE_OUT_QUINT   (t -> { float x = t - 1; return 1 + x * x * x * x * x; }),
    EASE_IN_OUT_QUINT(t -> t < 0.5f ? 16*t*t*t*t*t : 1 + 16*(t-1)*(t-1)*(t-1)*(t-1)*(t-1)),
    EASE_IN_EXPO     (t -> t == 0f ? 0f : (float)Math.pow(2, 10 * (t - 1))),
    EASE_OUT_EXPO    (t -> t == 1f ? 1f : 1 - (float)Math.pow(2, -10 * t)),
    EASE_IN_OUT_EXPO (t -> {
        if (t == 0f) return 0f;
        if (t == 1f) return 1f;
        return t < 0.5f
            ? (float)(Math.pow(2,  20*t - 10) / 2)
            : (float)(2 - Math.pow(2, -20*t + 10) / 2);
    }),
    EASE_IN_CIRC     (t -> (float)(1 - Math.sqrt(1 - t * t))),
    EASE_OUT_CIRC    (t -> (float) Math.sqrt(1 - (t-1)*(t-1))),
    EASE_IN_OUT_CIRC (t -> t < 0.5f
        ? (float)((1 - Math.sqrt(1 - (2*t)*(2*t))) / 2)
        : (float)((Math.sqrt(1 - (-2*t+2)*(-2*t+2)) + 1) / 2)),
    EASE_IN_BACK     (t -> { float c1 = 1.70158f, c3 = c1 + 1; return c3*t*t*t - c1*t*t; }),
    EASE_OUT_BACK    (t -> { float c1 = 1.70158f, c3 = c1 + 1, x = t - 1; return 1 + c3*x*x*x + c1*x*x; }),
    EASE_IN_OUT_BACK (t -> {
        float c1 = 1.70158f, c2 = c1 * 1.525f;
        if (t < 0.5f) { float k = 2*t; return (k*k*((c2+1)*k - c2)) / 2; }
        else           { float k = 2*t-2; return (k*k*((c2+1)*k + c2) + 2) / 2; }
    });

    private final Function<Float, Float> ease;

    EasingType(Function<Float, Float> ease) { this.ease = ease; }

    public float ease(float t) { return ease.apply(Math.max(0f, Math.min(1f, t))); }

    public float apply(float from, float to, float progress) {
        return from + (to - from) * ease(progress);
    }
}
