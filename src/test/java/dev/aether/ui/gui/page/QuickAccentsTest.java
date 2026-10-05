package dev.aether.ui.gui.page;

import dev.aether.ui.gui.Argb;
import dev.aether.ui.gui.Palette;
import dev.aether.ui.gui.TestConfigDir;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.theme.ThemePreset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QuickAccentsTest {
    private Theme.Snapshot saved;

    @BeforeAll
    static void configDir() {
        TestConfigDir.ensure();
    }

    @BeforeEach
    void save() {
        saved = Theme.snapshot();
    }

    @AfterEach
    void restore() {
        Theme.restore(saved);
    }

    @Test
    void everyQuickAccentStaysReadableOnEveryPreset() {
        for (ThemePreset preset : ThemePreset.values()) {
            preset.apply();
            for (int accent : QuickAccents.forCurrentTheme()) {
                for (int surface : new int[]{Theme.PANEL_BG, Theme.CARD_BG, Theme.SIDEBAR_BG, Theme.ELEMENT_BG}) {
                    assertTrue(Argb.contrast(accent, surface) >= QuickAccents.MIN_CONTRAST,
                            preset.label() + " " + Integer.toHexString(accent));
                }
            }
        }
    }

    @Test
    void lightThemesDarkenTheHuesAndDarkThemesKeepThemBright() {
        ThemePreset.PAPER.apply();
        int paperRed = QuickAccents.forCurrentTheme().getFirst();
        ThemePreset.MIDNIGHT.apply();
        int midnightRed = QuickAccents.forCurrentTheme().getFirst();
        assertTrue(Argb.luminance(paperRed) < Argb.luminance(midnightRed));
    }

    @Test
    void applyingAnAccentIsAnEditAndFollowsTheTarget() {
        ThemePreset.NORD.apply();
        int hudAccent = Theme.HUD_ACCENT;
        int pick = QuickAccents.forCurrentTheme().get(4);
        QuickAccents.apply(pick, ThemePreset.Target.MENU);
        assertEquals(pick, Theme.ACCENT_PRIMARY);
        assertTrue(QuickAccents.isCurrent(pick));
        assertEquals(hudAccent, Theme.HUD_ACCENT);
        assertTrue(Theme.PRESET_MODIFIED);
        QuickAccents.apply(pick, ThemePreset.Target.MENU_AND_HUD);
        assertNotEquals(hudAccent, Theme.HUD_ACCENT);
        assertTrue(Argb.contrast(Theme.HUD_ACCENT, Theme.HUD_BG) >= QuickAccents.MIN_CONTRAST);
    }

    @Test
    void presetPalettesPreviewWithoutTouchingTheTheme() {
        ThemePreset.SAGE.apply();
        int accent = Theme.ACCENT_PRIMARY;
        Palette paper = ThemePalettes.of(ThemePreset.PAPER);
        assertEquals(ThemePreset.PAPER.colour("Accent"), paper.accent());
        assertTrue(paper.light());
        assertEquals(accent, Theme.ACCENT_PRIMARY);
        assertEquals(Theme.defaultColour(Theme.ENTRIES.getFirst()), ThemePalettes.classic().accent());
        assertFalse(Theme.PRESET_MODIFIED);
    }
}
