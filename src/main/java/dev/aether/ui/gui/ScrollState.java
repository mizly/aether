package dev.aether.ui.gui;

// one scroll area in content coordinates (0 is the top of the content): the target moves at once and
// the drawn offset follows it over time
public final class ScrollState {
    private static final float SETTLED = 0.25f;
    private static final float MAX_STEP_MS = 100f;

    private float offset;
    private float target;
    private float max;
    private float viewport;
    private long lastNanos = -1L;

    public float offset() {
        return offset;
    }

    public float target() {
        return target;
    }

    public float max() {
        return max;
    }

    public float viewport() {
        return viewport;
    }

    public void setExtent(float contentHeight, float viewportHeight) {
        viewport = Math.max(0f, viewportHeight);
        max = Math.max(0f, contentHeight - viewport);
        target = clamp(target);
        offset = clamp(offset);
    }

    public void scrollBy(float delta) {
        target = clamp(target + delta);
    }

    public void scrollTo(float position) {
        target = clamp(position);
    }

    public void jumpTo(float position) {
        target = clamp(position);
        offset = target;
    }

    // dt is capped so a frame hitch does not teleport the content; snap is reduced motion
    public void tick(long nowNanos, float animTimeMs, boolean snap) {
        float dtMs = lastNanos < 0L ? 0f : Math.min(MAX_STEP_MS, Math.max(0L, nowNanos - lastNanos) / 1_000_000f);
        lastNanos = nowNanos;
        if (snap || Math.abs(target - offset) < SETTLED) {
            offset = target;
            return;
        }
        float follow = 1f - (float) Math.exp(-dtMs / Math.max(1f, animTimeMs / 3f));
        offset += (target - offset) * follow;
    }

    // scrolls the least that shows top..bottom plus margin; an element taller than the view shows its top
    public void ensureVisible(float top, float bottom, float margin) {
        if (top - margin < target) {
            target = clamp(top - margin);
        } else if (bottom + margin > target + viewport) {
            target = clamp(Math.min(bottom + margin - viewport, top - margin));
        }
    }

    // the scrollbar thumb inside track, never shorter than minThumb; empty when nothing scrolls
    public Rect thumb(Rect track, float minThumb) {
        if (max <= 0f) {
            return Rect.EMPTY;
        }
        float height = thumbHeight(track, minThumb);
        return new Rect(track.x(), track.y() + (track.h() - height) * (offset / max), track.w(), height);
    }

    // grab is the pointer's distance below the thumb top at press; use the press-time track (PointerEvent.pressRect)
    public void dragThumb(float pointerY, float grab, Rect track, float minThumb) {
        if (max <= 0f) {
            return;
        }
        float travel = track.h() - thumbHeight(track, minThumb);
        jumpTo(travel <= 0f ? 0f : (pointerY - grab - track.y()) / travel * max);
    }

    private float thumbHeight(Rect track, float minThumb) {
        float content = viewport + max;
        float height = content <= 0f ? track.h() : track.h() * viewport / content;
        return Math.min(track.h(), Math.max(minThumb, height));
    }

    private float clamp(float value) {
        return Math.max(0f, Math.min(max, value));
    }
}
