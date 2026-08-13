package dev.aether.telemetry.ban;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class BanReportDeduplicator {
    private static final long DEFAULT_WINDOW_NANOS = TimeUnit.MINUTES.toNanos(2);
    private static final int MAX_TRACKED = 64;

    private final long windowNanos;
    private final Map<String, Long> acceptedAtNanos = new LinkedHashMap<>();

    public BanReportDeduplicator() {
        this(DEFAULT_WINDOW_NANOS);
    }

    public BanReportDeduplicator(long windowNanos) {
        this.windowNanos = windowNanos;
    }

    // a single tracked key let two alternating reasons slip past dedup; each distinct key now carries its own window.
    public synchronized boolean accept(BanReport report, long nowNanos) {
        prune(nowNanos);
        String key = report.dedupKey();
        Long acceptedAt = acceptedAtNanos.get(key);
        if (acceptedAt != null) {
            long elapsed = nowNanos - acceptedAt;
            if (elapsed >= 0L && elapsed <= windowNanos) {
                return false;
            }
        }
        acceptedAtNanos.put(key, nowNanos);
        return true;
    }

    public synchronized void reset() {
        acceptedAtNanos.clear();
    }

    private void prune(long nowNanos) {
        Iterator<Map.Entry<String, Long>> it = acceptedAtNanos.entrySet().iterator();
        while (it.hasNext()) {
            long elapsed = nowNanos - it.next().getValue();
            if (elapsed < 0L || elapsed > windowNanos) {
                it.remove();
            }
        }
        // bound memory even under a flood of distinct reasons that all stay inside the window.
        Iterator<String> oldest = acceptedAtNanos.keySet().iterator();
        while (acceptedAtNanos.size() > MAX_TRACKED && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
    }
}
