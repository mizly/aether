package dev.aether.ui.gui.control;

import dev.aether.ui.gui.Cursor;
import dev.aether.ui.gui.HitHandler;
import dev.aether.ui.gui.Rect;

public final class Checkbox {
    private Checkbox() {
    }

    public static Rect place(UiContext ui, Rect area) {
        float s = ui.metrics().checkboxSize();
        return new Rect(area.right() - s, area.centerY() - s / 2f, s, s);
    }

    public static void render(UiContext ui, Object id, Rect box, boolean on, Runnable flip, boolean enabled) {
        float hoverT = ControlSupport.hover(ui, id);
        float onT = ui.anim().ease(Part.of(id, "on"), on ? 1f : 0f, 120f);
        ui.skin().checkbox(ui.sc(), box, onT, hoverT, enabled);
        if (!enabled) {
            return;
        }
        Rect hit = box.inset(-5f);
        ControlSupport.region(ui, id, hit, HitHandler.click(flip), Cursor.HAND);
        ControlSupport.focusable(ui, id, hit, flip::run);
        ControlSupport.ring(ui, id, box, 5f);
    }
}
