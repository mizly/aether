package dev.aether.ui.gui.control;

import dev.aether.ui.gui.Cursor;
import dev.aether.ui.gui.HitHandler;
import dev.aether.ui.gui.Icon;
import dev.aether.ui.gui.Rect;
import dev.aether.ui.gui.skin.ButtonKind;
import dev.aether.ui.gui.skin.Glyph;

// a push button; the action runs on the left press, the way vanilla buttons react
public final class Button {
    private Button() {
    }

    public static float width(UiContext ui, String label, Icon icon) {
        return ui.skin().buttonWidth(ui.sc(), label, icon);
    }

    public static void render(UiContext ui, Object id, Rect r, String label, ButtonKind kind, Icon icon,
                              boolean enabled, Runnable action) {
        float hoverT = ControlSupport.hover(ui, id);
        ui.skin().button(ui.sc(), r, label, kind, icon, hoverT, ControlSupport.pressed(ui, id), enabled);
        if (!enabled) {
            return;
        }
        ControlSupport.region(ui, id, r, HitHandler.click(action), Cursor.HAND);
        ControlSupport.focusable(ui, id, r, action::run);
        ControlSupport.ring(ui, id, r, ui.metrics().buttonRadius());
    }

    // a small square button with a glyph, for list rows and header tools
    public static void glyph(UiContext ui, Object id, Rect r, Glyph glyph, boolean enabled, Runnable action) {
        float hoverT = ControlSupport.hover(ui, id);
        ui.skin().iconButton(ui.sc(), r, glyph, hoverT, ControlSupport.pressed(ui, id), enabled);
        if (!enabled) {
            return;
        }
        ControlSupport.region(ui, id, r, HitHandler.click(action), Cursor.HAND);
        ControlSupport.focusable(ui, id, r, action::run);
        ControlSupport.ring(ui, id, r, ui.metrics().fieldRadius());
    }

    public static void icon(UiContext ui, Object id, Rect r, Icon icon, boolean enabled, Runnable action) {
        float hoverT = ControlSupport.hover(ui, id);
        ui.skin().iconButton(ui.sc(), r, icon, hoverT, ControlSupport.pressed(ui, id), enabled);
        if (!enabled) {
            return;
        }
        ControlSupport.region(ui, id, r, HitHandler.click(action), Cursor.HAND);
        ControlSupport.focusable(ui, id, r, action::run);
        ControlSupport.ring(ui, id, r, ui.metrics().fieldRadius());
    }
}
