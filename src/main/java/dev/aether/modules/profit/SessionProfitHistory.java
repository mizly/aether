package dev.aether.modules.profit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class SessionProfitHistory {
    public static final long MAX_WINDOW_MILLIS = 15 * 60_000L;
    private static final long BUCKET_NANOS = 1_000_000_000L;
    private final ArrayDeque<Bucket> buckets = new ArrayDeque<>();
    private long elapsedNanos;
    private long lastNanos;
    private long generation;
    private boolean initialized;
    private boolean running;
    private List<Point> cachedPoints;

    public record Point(double timeMillis, long coins) {}
    public record Snapshot(double elapsedMillis, List<Point> points, long generation) {}

    public synchronized void reset(long nowNanos, boolean active) {
        buckets.clear();
        elapsedNanos = 0;
        lastNanos = nowNanos;
        initialized = true;
        running = active;
        generation++;
        buckets.add(new Bucket(0, new Point(0, 0)));
        cachedPoints = null;
    }

    public synchronized void setRunning(long nowNanos, boolean active) {
        advance(nowNanos);
        running = active;
    }

    public synchronized void record(long nowNanos, long coins) {
        advance(nowNanos);
        Bucket last = buckets.peekLast();
        if (last == null || last.last.coins != coins) {
            Point point = new Point(elapsedNanos / 1_000_000.0, coins);
            long second = elapsedNanos / BUCKET_NANOS;
            if (last == null || last.second != second) buckets.addLast(new Bucket(second, point));
            else last.add(point);
            cachedPoints = null;
        }
        double cutoff = elapsedNanos / 1_000_000.0 - MAX_WINDOW_MILLIS;
        while (buckets.size() > 1) {
            Bucket first = buckets.removeFirst();
            if (buckets.getFirst().last.timeMillis >= cutoff) {
                buckets.addFirst(first);
                break;
            }
            cachedPoints = null;
        }
    }

    public synchronized Snapshot snapshot(long nowNanos) {
        if (cachedPoints == null) {
            var points = new ArrayList<Point>(buckets.size() * 4);
            for (Bucket bucket : buckets) bucket.appendTo(points);
            cachedPoints = List.copyOf(points);
        }
        long extra = initialized && running ? Math.max(0, nowNanos - lastNanos) : 0;
        return new Snapshot((elapsedNanos + extra) / 1_000_000.0, cachedPoints, generation);
    }

    private void advance(long nowNanos) {
        if (!initialized) {
            reset(nowNanos, false);
            return;
        }
        long delta = nowNanos - lastNanos;
        if (delta < 0) return;
        if (running) elapsedNanos += delta;
        lastNanos = nowNanos;
    }

    private static final class Bucket {
        private final long second;
        private final Point first;
        private Point min;
        private Point max;
        private Point last;

        private Bucket(long second, Point point) {
            this.second = second;
            first = min = max = last = point;
        }

        private void add(Point point) {
            if (point.coins < min.coins) min = point;
            if (point.coins > max.coins) max = point;
            last = point;
        }

        private void appendTo(List<Point> points) {
            var ordered = new ArrayList<>(List.of(first, min, max, last));
            ordered.sort(Comparator.comparingDouble(Point::timeMillis));
            Point previous = null;
            for (Point point : ordered) {
                if (point != previous) points.add(point);
                previous = point;
            }
        }
    }
}
