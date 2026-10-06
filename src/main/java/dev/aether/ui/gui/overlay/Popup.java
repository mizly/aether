package dev.aether.ui.gui.overlay;

import dev.aether.ui.gui.Rect;
import dev.aether.ui.gui.control.Part;
import dev.aether.ui.gui.control.UiContext;

// shared by the reference overlays: the open fade, placing a panel against an anchor, and the bounds record
abstract class Popup implements Overlay {
    static final float EDGE = 8f;
    static final float FADE_MS = 120f;

    private boolean started;
    protected Rect bounds = Rect.EMPTY;

    @Override
    public Rect bounds() {
        return bounds;
    }

    // 0..1 open progress; overlays fade and slide a few units, never scale their text
    protected float openProgress(UiContext ui) {
        Object key = Part.of(this, "open");
        if (!started) {
            started = true;
            ui.anim().ease(key, 0f, FADE_MS);
        }
        return ui.anim().ease(key, 1f, FADE_MS);
    }

    // below the anchor (left edges aligned, or right edges when alignRight), flipped above when there is
    // more room there, then clamped inside the screen; height is cut to the room on the chosen side
    static Rect place(Rect screen, Rect anchor, float width, float height, float gap, boolean alignRight) {
        float w = Math.min(width, screen.w() - EDGE * 2f);
        float below = screen.bottom() - EDGE - (anchor.bottom() + gap);
        float above = anchor.y() - gap - (screen.y() + EDGE);
        boolean up = height > below && above > below;
        float h = Math.min(height, Math.max(40f, up ? above : below));
        float x = alignRight ? anchor.right() - w : anchor.x();
        x = Math.max(screen.x() + EDGE, Math.min(x, screen.right() - EDGE - w));
        float y = up ? anchor.y() - gap - h : anchor.bottom() + gap;
        y = Math.max(screen.y() + EDGE, Math.min(y, screen.bottom() - EDGE - h));
        return new Rect(x, y, w, h);
    }

    static boolean flippedUp(Rect panel, Rect anchor) {
        return panel.bottom() <= anchor.y() + 0.5f;
    }
}
