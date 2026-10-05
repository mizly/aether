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
    static final Icon SEARCH = Icon.svg("/assets/aether/icons/search.svg");
    static final Icon PLAY = Icon.svg("/assets/aether/icons/play.svg");
    static final Icon HUD = Icon.svg("/assets/aether/icons/hud.svg");

    // inter's caps sit a little below the middle of its line box when drawn from the top
    private static final float CENTER_FACTOR = 0.60f;

    enum ButtonKind { PRIMARY, GHOST, SUBTLE, DANGER }

    private PanelPaint() {
    }

    // -- palette-derived tones --------------------------------------------------

    // solid, so the sky and ground behind a panel never shade it lighter above the horizon and darker below
    static int windowFill(Palette p) {
        return Argb.withAlpha(p.panel(), 1f);
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

    // the current menu's checkbox, kept on purpose; on is 0..1 so the fill and tick can spring in
    static void toggle(GuiCanvas c, Palette p, Rect r, float on, float hover, boolean enabled) {
        float size = Math.min(r.h(), 20f);
        Rect box = new Rect(r.right() - size, r.centerY() - size / 2f, size, size);
        c.save();
        if (!enabled) {
            c.alpha(0.45f);
        }
        c.roundedRect(box, 5f, Argb.mix(fieldFill(p), p.accent(), on));
        c.strokeRect(box, 5f, 1f, Argb.mix(Argb.mix(p.border(), p.text(), hover * 0.4f), p.accent(), on));
        if (on > 0.05f) {
            c.save();
            c.alpha(Math.min(1f, on));
            check(c, box.centerX(), box.centerY() + 0.5f, size * 0.36f, 2f, p.onAccent());
            c.restore();
        }
        c.restore();
    }

    // minecraft's own button: the stone sprite, its highlighted frame under the cursor, pushed in a pixel while
    // held, the label in the game's font with its drop shadow. primary and danger tint the stone
    static void button(GuiCanvas c, Palette p, Rect r, String label, Icon icon, ButtonKind kind, float hover,
                       boolean pressed, boolean enabled) {
        int text = !enabled ? 0xFFA0A0A0 : kind == ButtonKind.DANGER ? 0xFFFF5555
                : hover > 0.5f ? 0xFFFFFFA0 : 0xFFFFFFFF;
        int stone = switch (kind) {
            case PRIMARY -> Argb.mix(0xFFFFFFFF, p.accent(), 0.45f);
            case DANGER -> 0xFFFFC8C8;
            default -> 0xFFFFFFFF;
        };
        if (pressed) stone = Argb.mix(stone, 0xFF000000, 0.18f);
        Rect at = pressed ? r.offset(0f, 1.5f) : r;
        String sprite = !enabled ? "minecraft:widget/button_disabled"
                : hover > 0.5f || pressed ? "minecraft:widget/button_highlighted" : "minecraft:widget/button";
        int tint = stone;
        c.save();
        if (!pressed && enabled) c.rect(new Rect(at.x() + 1f, at.bottom(), at.w() - 2f, 1.5f), 0x55000000);
        c.legacy(nvg -> nvg.guiSprite(sprite, at.x(), at.y(), at.w(), at.h(), tint));
        float iconSize = icon == null ? 0f : 14f;
        int scale = 2;
        float mcW = label.isEmpty() ? 0f : dev.aether.renderer.McBitmapFont.widthLiteral(label, scale);
        boolean mcFits = mcW + iconSize + 16f <= at.w() && at.h() >= 22f;
        float size = at.h() >= 32f ? 13f : 12f;
        float labelW = label.isEmpty() ? 0f : mcFits ? mcW : c.textWidth(SEMIBOLD, size, label);
        float gap = icon != null && !label.isEmpty() ? 7f : 0f;
        float start = at.centerX() - (labelW + iconSize + gap) / 2f;
        if (icon != null) icon(c, icon, start + iconSize / 2f, at.centerY(), iconSize, text);
        if (!label.isEmpty()) {
            float tx = start + iconSize + gap;
            if (mcFits) {
                float ty = at.centerY() - 4f * scale + 1f;
                c.legacy(nvg -> nvg.mcTextLiteral(label, tx, ty, scale, text, true));
            } else {
                c.text(SEMIBOLD, size, label, tx + 1f, top(at.centerY(), size) + 1f, 0xFF3F3F3F);
                c.text(SEMIBOLD, size, label, tx, top(at.centerY(), size), text);
            }
        }
        c.restore();
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

    // a small magnifier when no svg icon is wanted, e.g. inside thumbnails
    static void magnifier(GuiCanvas c, float cx, float cy, float s, float width, int argb) {
        float r = s * 0.34f;
        c.strokeCircle(cx - s * 0.08f, cy - s * 0.08f, r, width, argb);
        c.line(cx + r * 0.62f, cy + r * 0.62f, cx + s * 0.45f, cy + s * 0.45f, width, argb);
    }
}
