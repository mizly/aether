package dev.aether.ui;

import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.util.Fonts;

// the config tab's button look, so every screen outside MainGUI draws the same one
final class AetherButton {
    enum Kind { NORMAL, DANGER, ACTIVE }

    static final float ROW_W = 60f;
    static final float ROW_H = 26f;
    static final float ROW_GAP = 5f;
    static final float FULL_H = 32f;

    private static final int DANGER = 0xFFCC3333;
    private static final int DANGER_TEXT = 0xFFFF8888;

    private AetherButton() {
    }

    // the small buttons that sit on a card row, like load, export and delete on a profile
    static void row(NVGRenderer nvg, float x, float y, float w, String label, boolean hovered, Kind kind) {
        draw(nvg, x, y, w, ROW_H, 5f, 11.5f, label, hovered, kind);
    }

    // the full size buttons, like save next to the profile name field
    static void full(NVGRenderer nvg, float x, float y, float w, String label, boolean hovered, Kind kind) {
        draw(nvg, x, y, w, FULL_H, 7f, 12.5f, label, hovered, kind);
    }

    private static void draw(NVGRenderer nvg, float x, float y, float w, float h, float radius, float fontSize,
                             String label, boolean hovered, Kind kind) {
        boolean lit = hovered || kind == Kind.ACTIVE;
        int bg;
        int border;
        int text;
        if (kind == Kind.DANGER) {
            bg = hovered ? Theme.withAlpha(DANGER, 0.20f) : Theme.ELEMENT_BG;
            border = hovered ? Theme.withAlpha(DANGER, 0.45f) : Theme.withAlpha(0xFFFFFFFF, 0.06f);
            text = hovered ? DANGER_TEXT : Theme.TEXT_VALUE;
        } else {
            bg = lit ? Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.15f) : Theme.ELEMENT_BG;
            border = lit ? Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.35f) : Theme.withAlpha(0xFFFFFFFF, 0.06f);
            text = lit ? Theme.ACCENT_PRIMARY : Theme.TEXT_VALUE;
        }
        nvg.roundedRect(x, y, w, h, radius, bg);
        nvg.rectOutline(x, y, w, h, radius, 1f, border);
        nvg.textCentered(Fonts.REGULAR, label, x, y, w, h, fontSize, text);
    }
}
