package dev.aether.hud;

import dev.aether.macro.MacroState;
import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.util.Fonts;
import dev.aether.util.AetherLang;

public final class HudStyle {
    static final float RADIUS = 8f;
    public static final float PAD = 12f;
    static final float CONTENT_Y = 38f;

    private HudStyle() {}

    public static int alpha(int color, float opacity) {
        return Theme.withAlpha(color, Math.round((color >>> 24) * Math.clamp(opacity, 0f, 1f)));
    }

    public static void panel(NVGRenderer nvg, float width, float height) {
        nvg.shadow(0, 2, width, height, RADIUS, 10f, alpha(Theme.HUD_BG & 0xFF000000, 0.3f));
        nvg.roundedRect(0, 0, width, height, RADIUS, Theme.HUD_BG);
        nvg.rectOutline(0, 0, width, height, RADIUS, 0.8f, Theme.HUD_BORDER);
    }

    public static void accent(NVGRenderer nvg, float width, int left, int right) {
        float length = width - PAD * 2;
        if (length <= 0) return;
        float fade = Math.min(28f, length * 0.25f);
        int fadeLeft = Theme.blend(left, right, fade / length);
        int fadeRight = Theme.blend(left, right, 1f - fade / length);
        nvg.horizontalGradient(PAD, 0, fade, 2f, 0, alpha(left, 0), fadeLeft);
        nvg.horizontalGradient(PAD + fade, 0, length - fade * 2, 2f, 0, fadeLeft, fadeRight);
        nvg.horizontalGradient(PAD + length - fade, 0, fade, 2f, 0, fadeRight, alpha(right, 0));
    }

    static void header(NVGRenderer nvg, float width, String title, String meta) {
        accent(nvg, width, Theme.HUD_ACCENT, Theme.HUD_ACCENT);
        float metaWidth = nvg.textWidth(Fonts.MONO, meta, 9f);
        text(nvg, Fonts.BOLD, title, PAD, 12f, width - PAD * 2 - metaWidth - 10f, 12f, Theme.HUD_TITLE);
        nvg.textRight(Fonts.MONO, meta, PAD, 14f, width - PAD * 2, 9f, Theme.HUD_LABEL);
        nvg.rect(PAD, 31f, width - PAD * 2, 0.7f, Theme.HUD_SEP);
    }

    static void row(NVGRenderer nvg, float x, float y, float width, String label, String value,
                    float size, int valueColor) {
        String fittedValue = fit(nvg, Fonts.MONO, value, size, width * 0.57f);
        float valueWidth = nvg.textWidth(Fonts.MONO, fittedValue, size);
        text(nvg, Fonts.REGULAR, label, x, y, width - valueWidth - 10f, size, Theme.HUD_LABEL);
        nvg.textRight(Fonts.MONO, fittedValue, x, y, width, size, valueColor);
    }

    public static void text(NVGRenderer nvg, String font, String value, float x, float y,
                     float width, float size, int color) {
        if (width <= 0) return;
        nvg.text(font, fit(nvg, font, value, size, width), x, y, size, color);
    }

    static String fit(NVGRenderer nvg, String font, String value, float size, float width) {
        String localized = AetherLang.localize(value);
        if (width <= 0) return "";
        if (nvg.textWidth(font, localized, size) <= width) return localized;
        String suffix = "...";
        if (nvg.textWidth(font, suffix, size) > width) return "";
        int count = localized.codePointCount(0, localized.length());
        int low = 0, high = count;
        while (low < high) {
            int mid = (low + high + 1) / 2;
            String candidate = localized.substring(0, localized.offsetByCodePoints(0, mid)) + suffix;
            if (nvg.textWidth(font, candidate, size) <= width) low = mid;
            else high = mid - 1;
        }
        return localized.substring(0, localized.offsetByCodePoints(0, low)).stripTrailing() + suffix;
    }

    static void progress(NVGRenderer nvg, float x, float y, float width, float height, float progress) {
        nvg.roundedRect(x, y, width, height, height / 2, Theme.HUD_BAR_BG);
        float filled = width * Math.clamp(progress, 0f, 1f);
        if (filled > 0) nvg.roundedRect(x, y, filled, height, height / 2, Theme.HUD_ACCENT);
    }

    static int stateColor(MacroState.State state) {
        return switch (state) {
            case OFF -> Theme.HUD_LABEL;
            case FARMING -> Theme.HUD_SUCCESS;
            case RECOVERING -> Theme.HUD_ERROR;
            case CLEANING, DROPPING_JUNK -> Theme.HUD_WARNING;
            default -> Theme.HUD_ACCENT;
        };
    }

    static int bpsColor(double bps) {
        if (bps >= 18.5) return Theme.HUD_SUCCESS;
        if (bps >= 16) return Theme.blend(Theme.HUD_WARNING, Theme.HUD_SUCCESS, (float) ((bps - 16) / 2.5));
        return Theme.blend(Theme.HUD_ERROR, Theme.HUD_WARNING, (float) (Math.max(0, bps) / 16));
    }
}
