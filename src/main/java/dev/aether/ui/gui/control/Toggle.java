package dev.aether.ui.gui.control;

import dev.aether.ui.gui.Cursor;
import dev.aether.ui.gui.FocusHandler;
import dev.aether.ui.gui.HitHandler;
import dev.aether.ui.gui.Rect;

// an on/off switch; flip reads and writes the live value, so it can be shared with a row-wide click
public final class Toggle {
    private Toggle() {
    }

    // the switch drawn at the skin's toggle size, vertically centred at the right edge of area
    public static Rect place(UiContext ui, Rect area) {
        float w = ui.metrics().toggleWidth();
        float h = ui.metrics().toggleHeight();
        return new Rect(area.right() - w, area.centerY() - h / 2f, w, h);
    }

    public static void render(UiContext ui, Object id, Rect track, boolean on, Runnable flip, boolean enabled) {
        float hoverT = ControlSupport.hover(ui, id);
        float onT = ui.anim().spring(Part.of(id, "on"), on ? 1f : 0f);
        ui.skin().toggle(ui.sc(), track, onT, hoverT, ControlSupport.pressed(ui, id), enabled);
        if (!enabled) {
            return;
        }
        // a few units of slack around the small track, so it is easy to hit
        Rect hit = track.inset(-6f);
        ControlSupport.region(ui, id, hit, HitHandler.click(flip), Cursor.HAND);
        ControlSupport.focusable(ui, id, hit, flip::run);
        ControlSupport.ring(ui, id, track, track.h() / 2f);
    }

    public static FocusHandler focusHandler(Runnable flip) {
        return flip::run;
    }
}
