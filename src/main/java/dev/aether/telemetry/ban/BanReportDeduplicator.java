package dev.aether.telemetry.ban;

import java.util.concurrent.TimeUnit;

public final class BanReportDeduplicator {
    private static final long DEFAULT_WINDOW_NANOS = TimeUnit.MINUTES.toNanos(2);

    private final long windowNanos;
    private String lastKey;
    private long lastAcceptedNanos;

    public BanReportDeduplicator() {
        this(DEFAULT_WINDOW_NANOS);
    }

    public BanReportDeduplicator(long windowNanos) {
        this.windowNanos = windowNanos;
    }

    public synchronized boolean accept(BanReport report, long nowNanos) {
        String key = report.dedupKey();
        if (key.equals(lastKey)) {
            long elapsed = nowNanos - lastAcceptedNanos;
            if (elapsed >= 0L && elapsed <= windowNanos) {
                return false;
            }
        }
        lastKey = key;
        lastAcceptedNanos = nowNanos;
        return true;
    }

    public synchronized void reset() {
        lastKey = null;
        lastAcceptedNanos = 0L;
    }
}
