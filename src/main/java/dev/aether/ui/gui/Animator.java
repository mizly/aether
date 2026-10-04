package dev.aether.ui.gui;

import java.util.HashMap;
import java.util.Map;

// keyed, time-based animation for immediate-mode drawing. durations scale with the theme's animation time
// (250 ms is 1x) and everything snaps at the minimum animation time, which doubles as reduced motion.
// keys untouched for two seconds are forgotten
public final class Animator {
    public static final float BASE_MS = 250f;
    private static final long EVICT_AFTER_NANOS = 2_000_000_000L;
    private static final float HOVER_MS = 120f;
    private static final float STAGGER_STEP_MS = 20f;
    private static final int STAGGER_MAX_STEPS = 8;
    private static final float STAGGER_MS = 180f;

    private final Map<Object, Track> tracks = new HashMap<>();
    private final boolean inert;
    private long now;
    private float speed = 1f;
    private boolean snap;
    private Animator inertView;

    public Animator() {
        this(false);
    }

    private Animator(boolean inert) {
        this.inert = inert;
    }

    // every call returns its target and nothing is stored, for thumbnails and previews
    public Animator inert() {
        if (inert) {
            return this;
        }
        if (inertView == null) {
            inertView = new Animator(true);
        }
        return inertView;
    }

    public void begin(long nowNanos, float animTimeMs, float minAnimTimeMs) {
        now = nowNanos;
        speed = Math.max(0f, animTimeMs) / BASE_MS;
        snap = animTimeMs <= minAnimTimeMs;
        tracks.values().removeIf(track -> nowNanos - track.used > EVICT_AFTER_NANOS);
    }

    // critically damped spring, the same maths as the old menu's sliding highlights; a new key starts at its target
    public float spring(Object key, float target) {
        if (inert) {
            return target;
        }
        Track track = track(key, target);
        double seconds = Math.max(0L, now - track.stepped) / 1_000_000_000.0;
        track.stepped = now;
        if (snap) {
            track.value = target;
            track.velocity = 0.0;
            return target;
        }
        double omega = 6_000.0 / Math.max(1.0, BASE_MS * speed);
        double offset = track.value - target;
        double decay = Math.exp(-omega * seconds);
        double step = (track.velocity + omega * offset) * seconds;
        track.value = target + (offset + step) * decay;
        track.velocity = (track.velocity - omega * step) * decay;
        if (Math.abs(track.value - target) < 0.01 && Math.abs(track.velocity) < 0.01) {
            track.value = target;
            track.velocity = 0.0;
        }
        return (float) track.value;
    }

    // ease-out from wherever the value was when the target last changed; a new key starts at its target
    public float ease(Object key, float target, float durationMs) {
        if (inert) {
            return target;
        }
        Track track = track(key, target);
        if (track.to != target) {
            track.from = eased(track, durationMs);
            track.to = target;
            track.started = now;
        }
        return snap ? target : eased(track, durationMs);
    }

    public float hover(Object key, boolean hovered) {
        return ease(key, hovered ? 1f : 0f, HOVER_MS);
    }

    // page-enter progress 0..1 for row index, timed from the first frame the key was seen: each row starts
    // 20 ms after the one before (capped at 8 rows) and settles in 180 ms
    public float stagger(Object key, int index) {
        if (inert || snap) {
            return 1f;
        }
        Track track = track(key, 0f);
        float delayMs = Math.min(Math.max(0, index), STAGGER_MAX_STEPS) * STAGGER_STEP_MS * speed;
        float elapsedMs = (now - track.created) / 1_000_000f - delayMs;
        return easeOut(elapsedMs / Math.max(1f, STAGGER_MS * speed));
    }

    private Track track(Object key, float initial) {
        Track track = tracks.computeIfAbsent(key, k -> new Track(initial, now));
        track.used = now;
        return track;
    }

    private float eased(Track track, float durationMs) {
        float t = (now - track.started) / 1_000_000f / Math.max(1f, durationMs * speed);
        return track.from + (track.to - track.from) * easeOut(t);
    }

    private static float easeOut(float t) {
        float clamped = Math.max(0f, Math.min(1f, t));
        float inverse = 1f - clamped;
        return 1f - inverse * inverse * inverse;
    }

    private static final class Track {
        private final long created;
        private long used;
        private long stepped;
        private long started;
        private double value;
        private double velocity;
        private float from;
        private float to;

        private Track(float initial, long now) {
            created = now;
            used = now;
            stepped = now;
            started = now;
            value = initial;
            from = initial;
            to = initial;
        }
    }
}
