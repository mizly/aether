package dev.aether.ui.theme;

import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ThemePresetTest {
    private String savedTheme;
    private float savedUiScale;
    private float savedTextScale;
    private String savedGuiStyle;
    private String savedPresetId;
    private boolean savedPresetModified;

    @BeforeAll
    static void configureLoader() throws Exception {
        var loader = FabricLoader.getInstance();
        var configDir = loader.getClass().getDeclaredField("configDir");
        configDir.setAccessible(true);
        if (configDir.get(loader) == null) configDir.set(loader, Files.createTempDirectory("aether-theme-test"));
    }

    @BeforeEach
    void saveTheme() {
        savedTheme = Theme.exportJson();
        savedUiScale = Theme.UI_SCALE;
        savedTextScale = Theme.TEXT_SCALE;
        savedGuiStyle = Theme.GUI_STYLE;
        savedPresetId = Theme.PRESET_ID;
        savedPresetModified = Theme.PRESET_MODIFIED;
    }

    @AfterEach
    void restoreTheme() {
        Theme.importJson(savedTheme);
        Theme.UI_SCALE = savedUiScale;
        Theme.TEXT_SCALE = savedTextScale;
        Theme.GUI_STYLE = savedGuiStyle;
        Theme.PRESET_ID = savedPresetId;
        Theme.PRESET_MODIFIED = savedPresetModified;
    }

    @Test
    void sharedThemesNeverCarryOrApplyGuiScale() {
        Theme.UI_SCALE = 1.75f;
        Theme.TEXT_SCALE = 1.1f;
        Theme.importJson("{\"Accent\":\"FF112233\",\"uiScale\":0.5,\"textScale\":2.0}");
        assertEquals(0xFF112233, Theme.ACCENT_PRIMARY);
        assertEquals(1.75f, Theme.UI_SCALE);
        assertEquals(1.1f, Theme.TEXT_SCALE);
        var exported = JsonParser.parseString(Theme.exportJson()).getAsJsonObject();
        assertFalse(exported.has("uiScale"));
        assertFalse(exported.has("textScale"));
    }

    @Test
    void sharedThemesNeverCarryOrApplyTheGuiStyleOrPresetState() {
        Theme.GUI_STYLE = "terminal";
        Theme.PRESET_ID = "sage";
        Theme.PRESET_MODIFIED = true;
        Theme.importJson("{\"Accent\":\"FF112233\",\"guiStyle\":\"inventory\",\"presetId\":\"dusk\",\"presetModified\":false}");
        assertEquals("terminal", Theme.GUI_STYLE);
        assertEquals("sage", Theme.PRESET_ID);
        assertTrue(Theme.PRESET_MODIFIED);
        var exported = JsonParser.parseString(Theme.exportJson()).getAsJsonObject();
        assertFalse(exported.has("guiStyle"));
        assertFalse(exported.has("presetId"));
        assertFalse(exported.has("presetModified"));
    }

    @Test
    void presetsDefineEveryEditableColourWithoutChangingLayout() {
        Set<String> labels = Stream.concat(Theme.ENTRIES.stream(), Theme.HUD_ENTRIES.stream())
                .map(entry -> entry.label).collect(Collectors.toSet());
        Theme.UI_SCALE = 2.25f;
        Theme.TEXT_SCALE = 1.25f;
        Theme.ANIM_TIME_MS = 400f;
        Theme.SETTING_SPACING = 9;
        Theme.GUI_STYLE = "terminal";
        Theme.PRESET_ID = "slate";
        Theme.PRESET_MODIFIED = true;

        for (ThemePreset preset : ThemePreset.values()) {
            var json = JsonParser.parseString(preset.json()).getAsJsonObject();
            assertEquals(labels, json.keySet(), preset.label());
            Theme.rainbowEntries.add("Accent");
            preset.apply();
            Stream.concat(Theme.ENTRIES.stream(), Theme.HUD_ENTRIES.stream()).forEach(entry ->
                    assertEquals((int) Long.parseLong(json.get(entry.label).getAsString(), 16), entry.getter.get()));
            assertTrue(Theme.rainbowEntries.isEmpty());
            assertEquals(2.25f, Theme.UI_SCALE);
            assertEquals(1.25f, Theme.TEXT_SCALE);
            assertEquals(400f, Theme.ANIM_TIME_MS);
            assertEquals(9, Theme.SETTING_SPACING);
            assertEquals("terminal", Theme.GUI_STYLE);
            assertEquals("slate", Theme.PRESET_ID);
            assertTrue(Theme.PRESET_MODIFIED);
        }
    }

    @Test
    void presetsStayReadableAcrossMenuAndHudSurfaces() {
        for (ThemePreset preset : ThemePreset.values()) {
            preset.apply();
            for (int background : new int[]{Theme.PANEL_BG, Theme.CARD_BG, Theme.SIDEBAR_BG, Theme.ELEMENT_BG}) {
                for (int text : new int[]{Theme.TEXT_PRIMARY, Theme.TEXT_SECONDARY, Theme.TEXT_MUTED,
                        Theme.TEXT_LABEL, Theme.TEXT_VALUE, Theme.ACCENT_PRIMARY}) {
                    assertTrue(contrast(text, background) >= 4.5, preset.label() + " menu contrast");
                }
            }
            for (int text : new int[]{Theme.HUD_TITLE, Theme.HUD_LABEL, Theme.HUD_VALUE, Theme.HUD_ACCENT,
                    Theme.HUD_SUCCESS, Theme.HUD_WARNING, Theme.HUD_ERROR}) {
                assertTrue(contrast(text, Theme.HUD_BG) >= 4.5, preset.label() + " HUD contrast");
            }
        }
    }

    @Test
    void presetsPersistAndDefaultColoursPreserveScale() {
        ThemePreset.SAGE.apply();
        Theme.UI_SCALE = 2f;
        Theme.GUI_STYLE = "inventory";
        Theme.PRESET_ID = "sage";
        Theme.PRESET_MODIFIED = true;
        String expected = Theme.exportJson();
        Theme.saveTheme();
        ThemePreset.EMBER.apply();
        Theme.GUI_STYLE = "aurora";
        Theme.PRESET_ID = "";
        Theme.PRESET_MODIFIED = false;
        Theme.loadTheme();
        assertEquals(expected, Theme.exportJson());
        assertEquals("inventory", Theme.GUI_STYLE);
        assertEquals("sage", Theme.PRESET_ID);
        assertTrue(Theme.PRESET_MODIFIED);

        Theme.resetColorsToDefaults();
        assertEquals(2f, Theme.UI_SCALE);
        assertEquals("inventory", Theme.GUI_STYLE);
        assertEquals("sage", Theme.PRESET_ID);
        assertTrue(Theme.PRESET_MODIFIED);
        int defaultAccent = Theme.ACCENT_PRIMARY;
        int defaultHud = Theme.HUD_ACCENT;
        ThemePreset.DUSK.apply();
        Theme.resetToDefaults();
        assertEquals(defaultAccent, Theme.ACCENT_PRIMARY);
        assertEquals(defaultHud, Theme.HUD_ACCENT);
        assertEquals(1.5f, Theme.UI_SCALE);
        // resetting the theme is about colours and sizes; the chosen gui style stays
        assertEquals("inventory", Theme.GUI_STYLE);
    }

    private static double contrast(int first, int second) {
        double a = luminance(first), b = luminance(second);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static double luminance(int color) {
        return 0.2126 * linear((color >> 16) & 255)
                + 0.7152 * linear((color >> 8) & 255) + 0.0722 * linear(color & 255);
    }

    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }
}
