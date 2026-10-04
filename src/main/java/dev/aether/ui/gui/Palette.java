package dev.aether.ui.gui;

import dev.aether.ui.theme.Theme;

// every colour the gui draws with, read from Theme once per frame; skins use this, never Theme directly.
// washes come from the text colour instead of white or black so light themes work, and the four menu
// tokens nothing painted before now have a job (toggle knob and track, dropdown field, action buttons)
public record Palette(
        int accent, int accent2, int onAccent,
        int panel, int sidebar, int card, int element, int surface, int hoverFill, int field,
        int border, int borderHover, int separator,
        int text, int textSecondary, int textMuted, int textDim, int textLabel, int textValue, int groupActive,
        int toggleTrack, int toggleKnob, int sliderLeft, int dropdown, int action, int actionHover,
        int success, int warning, int danger,
        int hover, int pressed, int selection, int shadow, int scrim,
        boolean light) {

    public static Palette fromTheme() {
        boolean light = Argb.luminance(Theme.PANEL_BG) > 0.5;
        int text = Theme.TEXT_PRIMARY;
        int accent = Theme.ACCENT_PRIMARY;
        return new Palette(
                accent, Theme.ACCENT_SECONDARY, onColour(accent),
                Theme.PANEL_BG, Theme.SIDEBAR_BG, Theme.CARD_BG, Theme.ELEMENT_BG, Theme.BG_SECONDARY, Theme.BG_HOVER,
                Theme.BG_FIELD,
                Theme.BORDER_DEFAULT, Argb.mix(Theme.BORDER_DEFAULT, text, 0.35f), Theme.SEPARATOR,
                text, Theme.TEXT_SECONDARY, Theme.TEXT_MUTED, Argb.mix(Theme.TEXT_MUTED, Theme.PANEL_BG, 0.35f),
                Theme.TEXT_LABEL, Theme.TEXT_VALUE, Theme.GROUP_ACTIVE,
                Theme.PILL_TRACK, Theme.PILL_KNOB_OFF, Theme.SLIDER_LEFT, Theme.DROPDOWN_BTN_BG, Theme.ACTION_BTN_BG,
                Theme.ACTION_BTN_HOVER,
                Theme.HUD_SUCCESS, Theme.HUD_WARNING, Theme.HUD_ERROR,
                Argb.withAlpha(text, 0.06f), Argb.withAlpha(text, 0.12f), Argb.withAlpha(accent, 0.28f),
                light ? 0x33000000 : 0x8C000000,
                light ? Argb.withAlpha(Theme.PANEL_BG, 0.45f) : 0x80000000,
                light);
    }

    // black or white, whichever reads better on the given fill
    public static int onColour(int fill) {
        return Argb.contrast(fill, 0xFF000000) >= Argb.contrast(fill, 0xFFFFFFFF) ? 0xFF000000 : 0xFFFFFFFF;
    }
}
