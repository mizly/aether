package dev.aether.ui.orbit.panel;

import dev.aether.ui.gui.Argb;
import dev.aether.ui.gui.GuiCanvas;
import dev.aether.ui.gui.Icon;
import dev.aether.ui.gui.Palette;
import dev.aether.ui.gui.Rect;
import dev.aether.ui.util.Fonts;

// aurora's drawing vocabulary: glass surfaces, ios switches, path glyphs, pills and icon tiles, all from the palette
final class PanelPaint {
    static final String REGULAR = Fonts.UI_REGULAR;
    static final String MEDIUM = Fonts.UI_MEDIUM;
    static final String SEMIBOLD = Fonts.UI_SEMIBOLD;
    static final String BOLD = Fonts.UI_BOLD;
    static final String MONO = Fonts.UI_MONO;

    // inter's caps sit a little below the middle of its line box when drawn from the top
    private static final float CENTER_FACTOR = 0.60f;

    enum ButtonKind { PRIMARY, GHOST, SUBTLE, DANGER }

    private PanelPaint() {
    }

    // -- palette-derived tones --------------------------------------------------

    static int windowFill(Palette p) {
        return Argb.withAlpha(p.panel(), 0.90f);
    }

    static int sidebarFill(Palette p) {
        return Argb.withAlpha(Argb.mix(p.sidebar(), p.panel(), 0.25f), 0.62f);
    }

    static int cardFill(Palette p) {
        return Argb.withAlpha(Argb.mix(p.panel(), p.card(), 0.75f), 0.78f);
    }

    static int cardBorder(Palette p) {
        return Argb.withAlpha(p.border(), p.light() ? 0.30f : 0.22f);
    }

    static int hairline(Palette p) {
        return Argb.withAlpha(p.separator(), p.light() ? 0.45f : 0.38f);
    }

    static int highlight(Palette p) {
        return p.light() ? 0x99FFFFFF : Argb.withAlpha(p.text(), 0.09f);
    }

    static int accentWash(Palette p, float strength) {
        return Argb.withAlpha(p.accent(), 0.16f * strength);
    }

    static int fieldFill(Palette p) {
        return Argb.withAlpha(Argb.mix(p.field(), p.panel(), 0.15f), 0.92f);
    }

    static int shadow(Palette p, float strength) {
        return Argb.multiplyAlpha(p.shadow(), strength);
    }

    // -- text ---------------------------------------------------------------

    static float top(float centerY, float size) {
        return centerY - size * CENTER_FACTOR;
    }

    static void text(GuiCanvas c, String font, float size, String text, float x, float centerY, int argb) {
        c.text(font, size, text, x, top(centerY, size), argb);
    }

    static float textRight(GuiCanvas c, String font, float size, String text, float right, float centerY, int argb) {
        float w = c.textWidth(font, size, text);
        c.text(font, size, text, right - w, top(centerY, size), argb);
        return w;
    }

    static void textCentered(GuiCanvas c, String font, float size, String text, float centerX, float centerY,
                             int argb) {
        float w = c.textWidth(font, size, text);
        c.text(font, size, text, centerX - w / 2f, top(centerY, size), argb);
    }

    static void fitText(GuiCanvas c, String font, float size, String text, float x, float centerY, float maxW,
                        int argb) {
        c.text(font, size, c.ellipsize(font, size, text, maxW), x, top(centerY, size), argb);
    }

    // -- surfaces -------------------------------------------------------------

    static void card(GuiCanvas c, Palette p, Rect r, float radius, float lift) {
        if (lift > 0.01f) {
            c.shadow(r, radius, 18f, shadow(p, 0.55f * lift));
        }
        c.roundedRect(r, radius, cardFill(p));
        if (lift > 0.01f) {
            c.roundedRect(r, radius, Argb.multiplyAlpha(p.hover(), lift));
        }
        c.strokeRect(r, radius, 1f, Argb.mix(cardBorder(p), p.borderHover(), lift * 0.6f));
        c.line(r.x() + radius, r.y() + 0.5f, r.right() - radius, r.y() + 0.5f, 1f, highlight(p));
    }

    static void iconTile(GuiCanvas c, Palette p, Rect r, Icon icon, float iconSize, float radius) {
        c.roundedRect(r, radius, Argb.withAlpha(Argb.mix(p.field(), p.accent(), 0.10f), 0.85f));
        c.strokeRect(r, radius, 1f, Argb.withAlpha(p.border(), 0.18f));
        if (icon != null) {
            icon(c, icon, r.centerX(), r.centerY(), iconSize, p.text());
        }
    }

    // svg icons take the tint, minecraft items always draw in their own colours
    static void icon(GuiCanvas c, Icon icon, float centerX, float centerY, float size, int tint) {
        int color = icon instanceof Icon.Item ? 0xFFFFFFFF : tint;
        c.icon(icon, centerX - size / 2f, centerY - size / 2f, size, color);
    }

    // -- controls ---------------------------------------------------------------

    // on is 0..1 so the knob and fill can spring between states
    static void toggle(GuiCanvas c, Palette p, Rect r, float on, float hover, boolean enabled) {
        float radius = r.h() / 2f;
        int off = Argb.mix(p.toggleTrack(), p.text(), 0.04f + hover * 0.06f);
        int track = Argb.mix(off, p.accent(), on);
        c.save();
        if (!enabled) {
            c.alpha(0.45f);
        }
        c.roundedRect(r, radius, track);
        float knob = r.h() - 4f;
        float kx = r.x() + 2f + (r.w() - knob - 4f) * on;
        Rect knobRect = new Rect(kx, r.y() + 2f, knob, knob);
        c.shadow(knobRect.offset(0f, 0.5f), knob / 2f, 3f, shadow(p, 0.5f));
        int knobColor = Argb.mix(p.toggleKnob(), 0xFFFFFFFF, on);
        c.circle(knobRect.centerX(), knobRect.centerY(), knob / 2f, knobColor);
        c.restore();
    }

    static void button(GuiCanvas c, Palette p, Rect r, String label, Icon icon, ButtonKind kind, float hover,
                       boolean pressed, boolean enabled) {
        float radius = Math.min(9f, r.h() / 2f);
        int fill;
        int text;
        int stroke = 0;
        switch (kind) {
            case PRIMARY -> {
                fill = Argb.mix(p.accent(), p.onAccent(), hover * 0.10f + (pressed ? 0.12f : 0f));
                text = p.onAccent();
            }
            case DANGER -> {
                fill = Argb.withAlpha(p.danger(), 0.18f + hover * 0.10f);
                text = p.danger();
            }
            case SUBTLE -> {
                fill = Argb.withAlpha(p.text(), 0.06f + hover * 0.05f + (pressed ? 0.04f : 0f));
                text = p.text();
            }
            default -> {
                fill = Argb.withAlpha(p.text(), hover * 0.06f + (pressed ? 0.05f : 0f));
                text = p.text();
                stroke = Argb.withAlpha(p.border(), 0.45f + hover * 0.25f);
            }
        }
        c.save();
        if (!enabled) {
            c.alpha(0.45f);
        }
        if (kind == ButtonKind.PRIMARY) {
            c.shadow(r.offset(0f, 1f), radius, 8f, Argb.withAlpha(p.accent(), 0.28f + hover * 0.12f));
        }
        c.roundedRect(r, radius, fill);
        if (stroke != 0) {
            c.strokeRect(r, radius, 1f, stroke);
        }
        float size = r.h() >= 32f ? 13f : 12f;
        float labelW = label.isEmpty() ? 0f : c.textWidth(SEMIBOLD, size, label);
        float iconSize = icon == null ? 0f : size + 1f;
        float gap = icon != null && !label.isEmpty() ? 7f : 0f;
        float start = r.centerX() - (labelW + iconSize + gap) / 2f;
        if (icon != null) {
            icon(c, icon, start + iconSize / 2f, r.centerY(), iconSize, text);
        }
        if (!label.isEmpty()) {
            c.text(SEMIBOLD, size, label, start + iconSize + gap, top(r.centerY(), size), text);
        }
        c.restore();
    }

    // primary button with its label on the left and a play triangle on the right
    static void resumeButton(GuiCanvas c, Palette p, Rect r, String label, float hover) {
        button(c, p, r, "", null, ButtonKind.PRIMARY, hover, false, true);
        float size = r.h() >= 34f ? 13f : 12.5f;
        float w = c.textWidth(SEMIBOLD, size, label);
        float start = r.centerX() - (w + 16f) / 2f;
        c.text(SEMIBOLD, size, label, start, top(r.centerY(), size), p.onAccent());
        play(c, start + w + 11f, r.centerY(), 9f, p.onAccent());
    }

    static float pill(GuiCanvas c, float x, float centerY, String text, String font, float size, int fg, int bg,
                      float padX, float h) {
        float w = c.textWidth(font, size, text) + padX * 2f;
        c.roundedRect(new Rect(x, centerY - h / 2f, w, h), h / 2f, bg);
        c.text(font, size, text, x + padX, top(centerY, size), fg);
        return w;
    }

    static float pillWidth(GuiCanvas c, String text, String font, float size, float padX) {
        return c.textWidth(font, size, text) + padX * 2f;
    }

    static float keycap(GuiCanvas c, Palette p, float x, float centerY, String text) {
        float size = 10.5f;
        float w = c.textWidth(MEDIUM, size, text) + 12f;
        Rect r = new Rect(x, centerY - 9f, w, 18f);
        c.roundedRect(r, 5f, Argb.withAlpha(p.text(), 0.07f));
        c.strokeRect(r, 5f, 1f, Argb.withAlpha(p.border(), 0.40f));
        c.text(MEDIUM, size, text, x + 6f, top(centerY, size), p.textMuted());
        return w;
    }

    static void focusRing(GuiCanvas c, Palette p, Rect r, float radius) {
        c.strokeRect(r.inset(-2f), radius + 2f, 2f, Argb.withAlpha(p.accent(), 0.65f));
    }

    // -- path glyphs ------------------------------------------------------------

    static void chevronRight(GuiCanvas c, float cx, float cy, float s, float width, int argb) {
        c.beginPath();
        c.moveTo(cx - s * 0.25f, cy - s * 0.5f);
        c.lineTo(cx + s * 0.25f, cy);
        c.lineTo(cx - s * 0.25f, cy + s * 0.5f);
        c.strokePath(width, argb);
    }

    static void chevronLeft(GuiCanvas c, float cx, float cy, float s, float width, int argb) {
        c.beginPath();
        c.moveTo(cx + s * 0.25f, cy - s * 0.5f);
        c.lineTo(cx - s * 0.25f, cy);
        c.lineTo(cx + s * 0.25f, cy + s * 0.5f);
        c.strokePath(width, argb);
    }

    static void chevronDown(GuiCanvas c, float cx, float cy, float s, float width, int argb) {
        c.beginPath();
        c.moveTo(cx - s * 0.5f, cy - s * 0.25f);
        c.lineTo(cx, cy + s * 0.25f);
        c.lineTo(cx + s * 0.5f, cy - s * 0.25f);
        c.strokePath(width, argb);
    }

    static void chevronUp(GuiCanvas c, float cx, float cy, float s, float width, int argb) {
        c.beginPath();
        c.moveTo(cx - s * 0.5f, cy + s * 0.25f);
        c.lineTo(cx, cy - s * 0.25f);
        c.lineTo(cx + s * 0.5f, cy + s * 0.25f);
        c.strokePath(width, argb);
    }

    static void cross(GuiCanvas c, float cx, float cy, float s, float width, int argb) {
        float h = s / 2f;
        c.beginPath();
        c.moveTo(cx - h, cy - h);
        c.lineTo(cx + h, cy + h);
        c.moveTo(cx + h, cy - h);
        c.lineTo(cx - h, cy + h);
        c.strokePath(width, argb);
    }

    static void check(GuiCanvas c, float cx, float cy, float s, float width, int argb) {
        c.beginPath();
        c.moveTo(cx - s * 0.5f, cy);
        c.lineTo(cx - s * 0.15f, cy + s * 0.35f);
        c.lineTo(cx + s * 0.5f, cy - s * 0.35f);
        c.strokePath(width, argb);
    }

    static void plus(GuiCanvas c, float cx, float cy, float s, float width, int argb) {
        float h = s / 2f;
        c.beginPath();
        c.moveTo(cx - h, cy);
        c.lineTo(cx + h, cy);
        c.moveTo(cx, cy - h);
        c.lineTo(cx, cy + h);
        c.strokePath(width, argb);
    }

    static void play(GuiCanvas c, float cx, float cy, float s, int argb) {
        float h = s / 2f;
        c.beginPath();
        c.moveTo(cx - h * 0.75f, cy - h);
        c.lineTo(cx + h, cy);
        c.lineTo(cx - h * 0.75f, cy + h);
        c.closePath();
        c.fillPath(argb);
    }

    static void home(GuiCanvas c, float cx, float cy, float s, float width, int argb) {
        float h = s / 2f;
        c.beginPath();
        c.moveTo(cx - h, cy - h * 0.05f);
        c.lineTo(cx, cy - h);
        c.lineTo(cx + h, cy - h * 0.05f);
        c.moveTo(cx - h * 0.72f, cy - h * 0.28f);
        c.lineTo(cx - h * 0.72f, cy + h * 0.9f);
        c.lineTo(cx + h * 0.72f, cy + h * 0.9f);
        c.lineTo(cx + h * 0.72f, cy - h * 0.28f);
        c.strokePath(width, argb);
    }

    static void dots(GuiCanvas c, float cx, float cy, float gap, float r, int argb) {
        c.circle(cx - gap, cy, r, argb);
        c.circle(cx, cy, r, argb);
        c.circle(cx + gap, cy, r, argb);
    }

    static void statusDot(GuiCanvas c, Palette p, float cx, float cy, boolean on) {
        if (on) {
            c.circle(cx, cy, 5.5f, Argb.withAlpha(p.success(), 0.22f));
            c.circle(cx, cy, 3.2f, p.success());
        } else {
            c.strokeCircle(cx, cy, 3f, 1.4f, Argb.withAlpha(p.textMuted(), 0.85f));
        }
    }

    static void sidebarGlyph(GuiCanvas c, float cx, float cy, float s, float width, int argb) {
        Rect r = new Rect(cx - s / 2f, cy - s * 0.42f, s, s * 0.84f);
        c.strokeRect(r, 2.5f, width, argb);
        c.line(r.x() + s * 0.36f, r.y() + 0.5f, r.x() + s * 0.36f, r.bottom() - 0.5f, width, argb);
    }

    // a small magnifier when no svg icon is wanted, e.g. inside thumbnails
    static void magnifier(GuiCanvas c, float cx, float cy, float s, float width, int argb) {
        float r = s * 0.34f;
        c.strokeCircle(cx - s * 0.08f, cy - s * 0.08f, r, width, argb);
        c.line(cx + r * 0.62f, cy + r * 0.62f, cx + s * 0.45f, cy + s * 0.45f, width, argb);
    }
}
