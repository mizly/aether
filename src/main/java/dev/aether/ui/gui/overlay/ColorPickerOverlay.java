package dev.aether.ui.gui.overlay;

import dev.aether.ui.gui.Argb;
import dev.aether.ui.gui.Cursor;
import dev.aether.ui.gui.GuiCanvas;
import dev.aether.ui.gui.HitHandler;
import dev.aether.ui.gui.KeyInput;
import dev.aether.ui.gui.Palette;
import dev.aether.ui.gui.PointerEvent;
import dev.aether.ui.gui.Rect;
import dev.aether.ui.gui.control.ColorText;
import dev.aether.ui.gui.control.Part;
import dev.aether.ui.gui.control.TextField;
import dev.aether.ui.gui.control.UiContext;
import dev.aether.ui.gui.skin.FontRole;
import dev.aether.ui.gui.skin.SkinContext;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

// the reference colour picker, anchored to its swatch: saturation/value square, hue and alpha bars, a hex
// field and a preview. drags write the colour live; enter or a press outside commits a typed hex, and the
// outside press still reaches what it landed on, like the old picker
final class ColorPickerOverlay extends Popup {
    private static final float PAD = 12f;
    private static final float SV_W = 208f;
    private static final float SV_H = 132f;
    private static final float BAR_H = 12f;
    private static final int[] HUES = {0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000};

    private final ColorRequest request;
    private Hsv hsv;
    private int lastWritten;
    private UiContext last;

    ColorPickerOverlay(ColorRequest request) {
        this.request = request;
        this.lastWritten = request.get().getAsInt();
        this.hsv = Hsv.fromArgb(lastWritten);
    }

    @Override
    public boolean alive(UiContext ui) {
        return ui.overlays().anchor(request.anchorId()) != null;
    }

    @Override
    public boolean passesOutsidePress() {
        return true;
    }

    private void write(Hsv next) {
        hsv = next;
        lastWritten = next.toArgb();
        request.set().accept(lastWritten);
    }

    @Override
    public void render(UiContext ui) {
        last = ui;
        int current = request.get().getAsInt();
        if (current != lastWritten) {
            // changed from outside (a paste over the swatch): follow it
            lastWritten = current;
            hsv = Hsv.fromArgb(current);
        }
        SkinContext sc = ui.sc();
        GuiCanvas g = ui.canvas();
        Palette p = ui.palette();
        float fieldH = ui.metrics().controlHeight();
        float width = SV_W + PAD * 2f;
        float height = PAD + SV_H + 10f + BAR_H + 9f + BAR_H + 12f + fieldH + PAD;
        Rect anchor = ui.overlays().anchor(request.anchorId());
        if (anchor == null) {
            anchor = request.anchor();
        }
        Rect panel = place(ui.canvas().bounds(), anchor, width, height, 6f, true);
        bounds = panel;
        float t = openProgress(ui);
        g.save();
        g.alpha(t);
        g.translate(0f, (1f - t) * (flippedUp(panel, anchor) ? 4f : -4f));
        ui.skin().popover(sc, panel, t);
        ui.hits().block(panel);

        Rect sv = new Rect(panel.x() + PAD, panel.y() + PAD, SV_W, SV_H);
        int pure = new Hsv(hsv.h(), 1f, 1f, 1f).toArgb();
        g.horizontalGradient(sv, 6f, 0xFFFFFFFF, pure);
        g.verticalGradient(sv, 6f, 0x00000000, 0xFF000000);
        g.strokeRect(sv, 6f, sc.pixel(), Argb.withAlpha(p.text(), 0.12f));
        float kx = sv.x() + hsv.s() * sv.w();
        float ky = sv.y() + (1f - hsv.v()) * sv.h();
        knob(g, kx, ky, new Hsv(hsv.h(), hsv.s(), hsv.v(), 1f).toArgb());
        ui.hits().add(Part.of(this, "sv"), sv, drag((x, y, r) -> write(hsv.withSv((x - r.x()) / r.w(), 1f - (y - r.y()) / r.h()))),
                Cursor.CROSSHAIR);

        Rect hue = new Rect(sv.x(), sv.bottom() + 10f, SV_W, BAR_H);
        float segment = hue.w() / (HUES.length - 1);
        g.save();
        g.clip(hue);
        for (int i = 0; i < HUES.length - 1; i++) {
            g.horizontalGradient(new Rect(hue.x() + segment * i - 0.5f, hue.y(), segment + 1f, hue.h()), 0f, HUES[i], HUES[i + 1]);
        }
        g.restore();
        g.strokeRect(hue, BAR_H / 2f, sc.pixel(), Argb.withAlpha(p.text(), 0.12f));
        barKnob(g, hue.x() + hsv.h() * hue.w(), hue, pure);
        ui.hits().add(Part.of(this, "hue"), hue.inset(0f, -3f, 0f, -3f),
                drag((x, y, r) -> write(hsv.withHue((x - r.x()) / r.w()))), Cursor.HAND);

        Rect alpha = new Rect(sv.x(), hue.bottom() + 9f, SV_W, BAR_H);
        checker(g, alpha);
        int opaque = new Hsv(hsv.h(), hsv.s(), hsv.v(), 1f).toArgb();
        g.horizontalGradient(alpha, BAR_H / 2f, opaque & 0x00FFFFFF, opaque);
        g.strokeRect(alpha, BAR_H / 2f, sc.pixel(), Argb.withAlpha(p.text(), 0.12f));
        barKnob(g, alpha.x() + hsv.a() * alpha.w(), alpha, hsv.toArgb() | 0xFF000000);
        ui.hits().add(Part.of(this, "alpha"), alpha.inset(0f, -3f, 0f, -3f),
                drag((x, y, r) -> write(hsv.withAlpha((x - r.x()) / r.w()))), Cursor.HAND);

        float rowY = alpha.bottom() + 12f;
        float previewW = 44f;
        Rect field = new Rect(sv.x(), rowY, SV_W - previewW - 8f, fieldH);
        TextField.render(ui, Part.of(this, "hex"), field, hexModel(), true);
        Rect preview = new Rect(field.right() + 8f, rowY, previewW, fieldH);
        checker(g, preview);
        g.roundedRect(preview, ui.metrics().fieldRadius(), hsv.toArgb());
        g.strokeRect(preview, ui.metrics().fieldRadius(), sc.pixel(), Argb.withAlpha(p.text(), 0.14f));
        g.restore();
    }

    private TextField.Model hexModel() {
        return new TextField.Model() {
            @Override
            public String text() {
                int argb = hsv.toArgb();
                return (argb >>> 24) == 0xFF ? String.format(Locale.ROOT, "%06X", argb & 0xFFFFFF) : ColorText.copy(argb);
            }

            @Override
            public String display() {
                return "#" + text();
            }

            @Override
            public void commit(String text) {
                ColorText.parse(text).ifPresent(argb -> write(Hsv.fromArgb(argb)));
            }

            @Override
            public void edited(String text) {
                ColorText.parse(text).ifPresent(argb -> write(Hsv.fromArgb(argb)));
            }

            @Override
            public int maxLength() {
                return 9;
            }

            @Override
            public FontRole font() {
                return FontRole.VALUE;
            }
        };
    }

    private static void knob(GuiCanvas g, float x, float y, int color) {
        g.circle(x, y + 1f, 8f, 0x55000000);
        g.circle(x, y, 7f, 0xFFFFFFFF);
        g.circle(x, y, 5f, color);
    }

    private static void barKnob(GuiCanvas g, float x, Rect bar, int color) {
        float r = bar.h() / 2f + 2f;
        g.circle(x, bar.centerY() + 1f, r + 1f, 0x55000000);
        g.circle(x, bar.centerY(), r, 0xFFFFFFFF);
        g.circle(x, bar.centerY(), r - 2.5f, color);
    }

    private static void checker(GuiCanvas g, Rect r) {
        g.save();
        g.clip(r);
        float cell = 4f;
        for (float y = r.y(); y < r.bottom(); y += cell) {
            for (float x = r.x(); x < r.right(); x += cell) {
                boolean dark = (Math.round((x - r.x()) / cell) + Math.round((y - r.y()) / cell)) % 2 == 0;
                g.rect(new Rect(x, y, cell, cell), dark ? 0xFF9A9A9A : 0xFFDADADA);
            }
        }
        g.restore();
    }

    private interface Drag {
        void at(float x, float y, Rect region);
    }

    private static HitHandler drag(Drag drag) {
        return new HitHandler() {
            @Override
            public boolean press(PointerEvent e) {
                if (e.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                    return false;
                }
                drag.at(e.localX(), e.localY(), e.pressRect());
                return true;
            }

            @Override
            public void drag(PointerEvent e) {
                drag.at(e.localX(), e.localY(), e.pressRect());
            }
        };
    }

    @Override
    public boolean key(KeyInput k) {
        if (last == null) {
            return false;
        }
        if (k.is(GLFW.GLFW_KEY_ESCAPE) || ((k.is(GLFW.GLFW_KEY_ENTER) || k.is(GLFW.GLFW_KEY_KP_ENTER))
                && !last.editors().active())) {
            last.overlays().close(this);
            return true;
        }
        return last.editors().owner() == this && last.editors().key(k, last.host().clipboard());
    }

    @Override
    public boolean chars(String chars) {
        return last != null && last.editors().owner() == this && last.editors().chars(chars);
    }
}
