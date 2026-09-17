package dev.aether.modules.farming;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;

final class MousematCooldown {
    private static final long COOLDOWN_NANOS = TimeUnit.SECONDS.toNanos(3);
    private static final long POLL_MILLIS = 50L;
    private final LongSupplier clock;
    private long lastUseNanos;
    private boolean used;

    MousematCooldown(LongSupplier clock) {
        this.clock = clock;
    }

    synchronized void recordUse() {
        lastUseNanos = clock.getAsLong();
        used = true;
    }

    synchronized long remainingMillis() {
        if (!used) return 0;
        long remaining = COOLDOWN_NANOS - (clock.getAsLong() - lastUseNanos);
        return remaining <= 0 ? 0 : (remaining + 999_999L) / 1_000_000L;
    }

    boolean await(BooleanSupplier shouldAbort, LongPredicate sleep) {
        while (!shouldAbort.getAsBoolean()) {
            long remaining = remainingMillis();
            if (remaining == 0) return true;
            if (!sleep.test(Math.min(remaining, POLL_MILLIS))) return false;
        }
        return false;
    }
}
