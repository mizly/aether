package dev.aether.ui.gui.page;

import dev.aether.ui.gui.Argb;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.theme.ThemePreset;

import java.util.ArrayList;
import java.util.List;

// one-click accent swatches: fixed hues, each nudged lighter (dark themes) or darker (light themes) until it
// reads as text on every surface the preset contrast test checks, so a quick pick never hides a label
public final class QuickAccents {
    public static final double MIN_CONTRAST = 4.5;
    private static final int[] HUES = {
            0xFFFF6B6B, 0xFFFF9F43, 0xFFF2C86E, 0xFFA6E05A, 0xFF4ADE80, 0xFF2DD4BF,
            0xFF38BDF8, 0xFF60A5FA, 0xFF818CF8, 0xFFA78BFA, 0xFFE58CFF, 0xFFF472B6};

    private QuickAccents() {
    }

    public static List<Integer> forCurrentTheme() {
        List<Integer> accents = new ArrayList<>(HUES.length);
        for (int hue : HUES) {
            accents.add(readable(hue, Theme.PANEL_BG, Theme.CARD_BG, Theme.SIDEBAR_BG, Theme.ELEMENT_BG));
        }
        return accents;
    }

    public static int readable(int base, int... surfaces) {
        double light = 0;
        for (int surface : surfaces) {
            light += Argb.luminance(surface);
        }
        int towards = light / surfaces.length > 0.5 ? 0xFF000000 : 0xFFFFFFFF;
        for (int step = 0; step <= 40; step++) {
            int candidate = Argb.mix(base, towards, step / 40f);
            if (minContrast(candidate, surfaces) >= MIN_CONTRAST) {
                return candidate;
            }
        }
        return towards;
    }

    // the gradient partner and second accent follow the pick, so sliders and hovers stay in one family
    public static void apply(int accent, ThemePreset.Target target) {
        Theme.ACCENT_PRIMARY = accent;
        Theme.ACCENT_SECONDARY = Argb.mix(accent, Theme.PANEL_BG, 0.3f);
        float[] hsv = Theme.argbToHsv(accent);
        Theme.SLIDER_LEFT = Theme.hsvToArgb((hsv[0] + 0.92f) % 1f, hsv[1], hsv[2], 0xFF);
        if (target == ThemePreset.Target.MENU_AND_HUD) {
            Theme.HUD_ACCENT = readable(accent, Theme.HUD_BG);
        }
        Theme.saveTheme();
    }

    public static boolean isCurrent(int accent) {
        return (Theme.ACCENT_PRIMARY | 0xFF000000) == (accent | 0xFF000000);
    }

    private static double minContrast(int colour, int... surfaces) {
        double min = Double.MAX_VALUE;
        for (int surface : surfaces) {
            min = Math.min(min, Argb.contrast(colour, surface));
        }
        return min;
    }
}
