package dev.aether.ui.gui.control;

import dev.aether.ui.gui.Cursor;
import dev.aether.ui.gui.FocusHandler;
import dev.aether.ui.gui.HitHandler;
import dev.aether.ui.gui.Rect;

// the bookkeeping every control repeats: hover progress, registering its region and focus entry, the ring
public final class ControlSupport {
    // the root rect of an element that just took keyboard focus, for its scroll container to reveal
    public static final Object REVEAL = new Object();

    private ControlSupport() {
    }

    public static float hover(UiContext ui, Object id) {
        return ui.anim().hover(Part.of(id, "hover"), ui.hits().hovered(id));
    }

    public static boolean pressed(UiContext ui, Object id) {
        return ui.hits().active(id);
    }

    public static void region(UiContext ui, Object id, Rect r, HitHandler handler, Cursor cursor) {
        ui.hits().add(id, r, handler, cursor);
    }

    public static void focusable(UiContext ui, Object id, Rect r, FocusHandler handler) {
        if (ui.focus().add(id, r, handler)) {
            ui.memory().put(REVEAL, ui.rootRect(r));
        }
    }

    public static void ring(UiContext ui, Object id, Rect r, float radius) {
        if (ui.focus().ringVisible(id)) {
            ui.skin().focusRing(ui.sc(), r, radius);
        }
    }

    // takes the pending reveal rect, if a control asked for one this frame
    public static Rect takeReveal(UiContext ui) {
        Rect reveal = ui.memory().peek(REVEAL);
        ui.memory().remove(REVEAL);
        return reveal;
    }
}
