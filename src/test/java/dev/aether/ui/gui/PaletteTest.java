package dev.aether.ui.gui;

import dev.aether.ui.theme.Theme;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PaletteTest {
    private String savedTheme;

    @BeforeAll
    static void configDir() {
        TestConfigDir.ensure();
    }

    @BeforeEach
    void saveTheme() {
        savedTheme = Theme.exportJson();
    }

    @AfterEach
    void restoreTheme() {
        Theme.importJson(savedTheme);
    }

    @Test
    void washesFollowTheTextColourSoLightThemesDarkenInsteadOfVanishing() {
        Theme.resetColorsToDefaults();
        Palette dark = Palette.fromTheme();
        Theme.importJson("""
                {"Main Panel BG": "FFF7F5F0", "Card BG": "FFFFFFFF", "Text": "FF1B1E24", "Accent": "FF2D5BD7",
                 "Text Muted": "FF5A6170", "Border": "FFB9BEC8"}
                """);
        Palette light = Palette.fromTheme();

        assertFalse(dark.light());
        assertTrue(light.light());
        assertTrue(Argb.luminance(dark.hover() | 0xFF000000) > 0.5);
        assertTrue(Argb.luminance(light.hover() | 0xFF000000) < 0.1);
        assertNotEquals(dark.hover() & 0xFFFFFF, light.hover() & 0xFFFFFF);
        assertEquals(Argb.alpha(dark.hover()), Argb.alpha(light.hover()));
        assertTrue(Argb.alpha(dark.pressed()) > Argb.alpha(dark.hover()));
        assertNotEquals(dark.scrim(), light.scrim());
        assertTrue(Argb.alpha(light.shadow()) < Argb.alpha(dark.shadow()));
    }

    @Test
    void onAccentPicksWhicheverOfBlackOrWhiteReadsBetter() {
        Theme.ACCENT_PRIMARY = 0xFFBBA7E8;
        assertEquals(0xFF000000, Palette.fromTheme().onAccent());
        Theme.ACCENT_PRIMARY = 0xFF1B3A8C;
        assertEquals(0xFFFFFFFF, Palette.fromTheme().onAccent());
    }

    @Test
    void statusColoursComeFromTheHudStatusTokens() {
        Theme.HUD_SUCCESS = 0xFF00AA00;
        Theme.HUD_WARNING = 0xFFAAAA00;
        Theme.HUD_ERROR = 0xFFAA0000;
        Palette palette = Palette.fromTheme();
        assertEquals(0xFF00AA00, palette.success());
        assertEquals(0xFFAAAA00, palette.warning());
        assertEquals(0xFFAA0000, palette.danger());
    }

    @Test
    void formerlyDeadTokensReachThePalette() {
        Theme.PILL_KNOB_OFF = 0xFF010203;
        Theme.PILL_TRACK = 0xFF040506;
        Theme.DROPDOWN_BTN_BG = 0xFF070809;
        Theme.ACTION_BTN_BG = 0xFF0A0B0C;
        Theme.ACTION_BTN_HOVER = 0xFF0D0E0F;
        Palette palette = Palette.fromTheme();
        assertEquals(0xFF010203, palette.toggleKnob());
        assertEquals(0xFF040506, palette.toggleTrack());
        assertEquals(0xFF070809, palette.dropdown());
        assertEquals(0xFF0A0B0C, palette.action());
        assertEquals(0xFF0D0E0F, palette.actionHover());
    }

    @Test
    void dimTextSitsBetweenMutedTextAndThePanel() {
        Theme.resetColorsToDefaults();
        Palette palette = Palette.fromTheme();
        double muted = Argb.contrast(palette.textMuted(), palette.panel());
        double dim = Argb.contrast(palette.textDim(), palette.panel());
        assertTrue(dim < muted && dim > 1.0, "dim " + dim + " muted " + muted);
    }
}
