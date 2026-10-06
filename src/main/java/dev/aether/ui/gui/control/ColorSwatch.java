package dev.aether.ui.gui.control;

import dev.aether.ui.gui.ClipboardTarget;
import dev.aether.ui.gui.Cursor;
import dev.aether.ui.gui.HitHandler;
import dev.aether.ui.gui.PointerEvent;
import dev.aether.ui.gui.Rect;
import dev.aether.ui.gui.overlay.ColorRequest;
import dev.aether.ui.gui.overlay.Overlay;
import dev.aether.ui.gui.skin.FontRole;
import org.lwjgl.glfw.GLFW;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

// a colour swatch that opens the colour picker anchored to it; hovering it, ctrl+c copies and ctrl+v pastes
public final class ColorSwatch {
    private ColorSwatch() {
    }

    public static boolean isOpen(UiContext ui, Object id) {
        Overlay open = ui.memory().peek(Part.of(id, "picker"));
        return open != null && ui.overlays().isOpen(open);
    }

    public static void open(UiContext ui, Object id, String title, IntSupplier get, IntConsumer set) {
        if (isOpen(ui, id)) {
            return;
        }
        Rect anchor = ui.overlays().anchor(id);
        if (anchor == null) {
            return;
        }
        Overlay overlay = ui.overlayFactory().colorPicker(ColorRequest.of(id, anchor, title, get, set));
        if (overlay != null) {
            ui.memory().put(Part.of(id, "picker"), overlay);
            ui.open(overlay);
        }
    }

    // the "#RRGGBB" text drawn left of the swatch, ending at right
    public static float hexWidth(UiContext ui) {
        return ui.skin().textWidth(ui.sc(), FontRole.VALUE, "#000000");
    }

    public static void render(UiContext ui, Object id, Rect swatch, IntSupplier get, IntConsumer set, String title,
                              boolean showHex, boolean enabled) {
        ui.anchor(id, swatch);
        int argb = get.getAsInt();
        boolean open = isOpen(ui, id);
        float hoverT = enabled ? ControlSupport.hover(ui, id) : 0f;
        if (showHex) {
            float w = hexWidth(ui);
            ui.skin().textRight(ui.sc(), FontRole.VALUE, ColorText.rgb(argb),
                    new Rect(swatch.x() - 10f - w, swatch.y(), w, swatch.h()), ui.palette().textMuted());
        }
        ui.skin().swatch(ui.sc(), swatch, argb, open, hoverT);
        if (!enabled) {
            return;
        }
        ControlSupport.region(ui, id, swatch.inset(-4f), new SwatchHandler(ui, id, title, get, set), Cursor.HAND);
        ControlSupport.focusable(ui, id, swatch, () -> open(ui, id, title, get, set));
        ControlSupport.ring(ui, id, swatch, 6f);
    }

    private record SwatchHandler(UiContext ui, Object id, String title, IntSupplier get, IntConsumer set)
            implements HitHandler, ClipboardTarget {
        @Override
        public boolean press(PointerEvent e) {
            if (e.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                return false;
            }
            open(ui, id, title, get, set);
            return true;
        }

        @Override
        public String copyText() {
            return ColorText.copy(get.getAsInt());
        }

        @Override
        public boolean paste(String text) {
            var parsed = ColorText.parse(text);
            parsed.ifPresent(set::accept);
            return parsed.isPresent();
        }
    }
}
