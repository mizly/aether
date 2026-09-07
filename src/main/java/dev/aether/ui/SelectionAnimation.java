package dev.aether.ui;

final class SelectionAnimation {
    private double value;
    private double velocity;
    private long lastNanos;
    private boolean initialized;

    float update(float target, float durationMs, long nowNanos) {
        if (!initialized) {
            reset(target, nowNanos);
            return target;
        }
        double seconds = Math.max(0L, nowNanos - lastNanos) / 1_000_000_000.0;
        lastNanos = nowNanos;
        double omega = 6_000.0 / Math.max(1.0, durationMs);
        double offset = value - target;
        double decay = Math.exp(-omega * seconds);
        double step = (velocity + omega * offset) * seconds;
        value = target + (offset + step) * decay;
        velocity = (velocity - omega * step) * decay;
        if (Math.abs(value - target) < 0.01 && Math.abs(velocity) < 0.01) {
            value = target;
            velocity = 0.0;
        }
        return (float) value;
    }

    void reset(float target, long nowNanos) {
        value = target;
        velocity = 0.0;
        lastNanos = nowNanos;
        initialized = true;
    }
}
