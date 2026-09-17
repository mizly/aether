package dev.aether.hud;

import java.util.Locale;

final class ProfitGraphScale {
    record Bounds(double min, double max) {
        double fraction(double value) {
            return (value - min) / (max - min);
        }
    }

    private Bounds bounds;
    private long lastNanos;

    Bounds update(double min, double max, long nowNanos, boolean reset) {
        Bounds target = target(min, max);
        if (bounds == null || reset) {
            bounds = target;
        } else {
            double seconds = Math.max(0, nowNanos - lastNanos) / 1_000_000_000.0;
            double blend = -Math.expm1(-seconds / 0.6);
            double low = bounds.min + (target.min - bounds.min) * blend;
            double high = bounds.max + (target.max - bounds.max) * blend;
            bounds = new Bounds(Math.min(low, min), Math.max(high, max));
        }
        lastNanos = nowNanos;
        return bounds;
    }

    static Bounds target(double min, double max) {
        double precision = Math.ulp(Math.max(Math.abs(min), Math.abs(max))) * 8;
        double padding = Math.max(Math.max(5, precision), (max - min) * 0.12);
        // zero stays on the axis so the trace is always read against a fixed baseline
        double low = min >= 0 ? 0 : min - padding;
        double high = max <= 0 ? 0 : max + padding;
        if (high <= low) high = low + 10;
        double step = tickStep(high - low);
        return new Bounds(Math.floor(low / step) * step, Math.ceil(high / step) * step);
    }

    static double tickStep(double span) {
        double rough = Math.max(1, span / 4);
        double power = Math.pow(10, Math.floor(Math.log10(rough)));
        double fraction = rough / power;
        return power * (fraction <= 1 ? 1 : fraction <= 2 ? 2 : fraction <= 5 ? 5 : 10);
    }

    static String label(double value, double step) {
        if (Math.abs(value) < step * 0.001) return "0";
        double magnitude = Math.abs(value);
        double divisor = magnitude >= 1e12 ? 1e12 : magnitude >= 1e9 ? 1e9 : magnitude >= 1e6 ? 1e6 : magnitude >= 1e3 ? 1e3 : 1;
        String suffix = divisor == 1e12 ? "T" : divisor == 1e9 ? "B" : divisor == 1e6 ? "M" : divisor == 1e3 ? "k" : "";
        int decimals = (int) Math.max(0, Math.ceil(-Math.log10(step / divisor)));
        if (decimals > 3) return String.format(Locale.ROOT, "%.0f", value);
        return String.format(Locale.ROOT, "%." + decimals + "f%s", value / divisor, suffix);
    }
}
