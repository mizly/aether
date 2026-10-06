package dev.aether.ui.gui.overlay;

import dev.aether.ui.gui.Rect;
import dev.aether.ui.gui.control.UiContext;
import dev.aether.ui.gui.skin.SkinContext;

import java.util.List;

// delayed help tooltips. anything under the pointer may offer help while it draws; the last offer that
// contains the pointer wins, and it shows after 260 ms of hovering the same key, never while a text field
// is being edited, an overlay is open or a key is being captured
public final class HoverHelp {
    public static final long DELAY_MS = 260L;
    private static final float OFFSET = 16f;
    private static final float EDGE = 8f;

    private Offer candidate;
    private Object hoveredKey;
    private long hoveredSince;
    private Rect lastRect = Rect.EMPTY;

    public void offer(SkinContext c, Object key, Rect local, String title, String body) {
        if ((body == null || body.isBlank()) && (title == null || title.isBlank())) {
            return;
        }
        if (!c.hasPointer()) {
            return;
        }
        Rect root = c.canvas().toRoot(local).intersect(c.canvas().rootClip());
        if (root.contains(c.mouseX(), c.mouseY())) {
            candidate = new Offer(key, title, body);
        }
    }

    // the rect the tooltip was drawn in last frame (empty when none showed), for tests and styles
    public Rect lastRect() {
        return lastRect;
    }

    public void render(UiContext ui) {
        Offer offer = candidate;
        candidate = null;
        lastRect = Rect.EMPTY;
        long now = ui.nowMs();
        if (offer == null) {
            hoveredKey = null;
            return;
        }
        if (!offer.key.equals(hoveredKey)) {
            hoveredKey = offer.key;
            hoveredSince = now;
        }
        if (now - hoveredSince < DELAY_MS || ui.editors().active() || !ui.overlays().isEmpty()
                || ui.capture().active() || ui.hits().captured()) {
            return;
        }
        SkinContext c = ui.sc();
        Rect screen = ui.canvas().bounds();
        float maxW = Math.min(ui.metrics().tooltipMaxWidth(), Math.max(160f, screen.w() * 0.36f));
        List<String> body = offer.body == null || offer.body.isBlank() ? List.of() : List.of(offer.body);
        Rect size = ui.skin().tooltipSize(c, offer.title, body, maxW);
        float x = c.mouseX() + OFFSET;
        float y = c.mouseY() + OFFSET;
        if (x + size.w() > screen.right() - EDGE) {
            x = c.mouseX() - OFFSET / 2f - size.w();
        }
        if (y + size.h() > screen.bottom() - EDGE) {
            y = c.mouseY() - OFFSET / 2f - size.h();
        }
        x = Math.max(screen.x() + EDGE, Math.min(x, screen.right() - EDGE - size.w()));
        y = Math.max(screen.y() + EDGE, Math.min(y, screen.bottom() - EDGE - size.h()));
        Rect rect = new Rect(x, y, size.w(), size.h());
        ui.canvas().save();
        try {
            ui.skin().tooltip(c, rect, offer.title, body);
        } finally {
            ui.canvas().restore();
        }
        lastRect = rect;
    }

    private record Offer(Object key, String title, String body) {
    }
}
