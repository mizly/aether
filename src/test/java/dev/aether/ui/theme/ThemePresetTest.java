package dev.aether.ui.theme;

import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ThemePresetTest {
    private Theme.Snapshot savedTheme;
    private float savedUiScale;
    private float savedTextScale;
    private float savedAnimTime;
    private String savedGuiStyle;

    @BeforeAll
    static void configureLoader() throws Exception {
        var loader = FabricLoader.getInstance();
        var configDir = loader.getClass().getDeclaredField("configDir");
        configDir.setAccessible(true);
        if (configDir.get(loader) == null) configDir.set(loader, Files.createTempDirectory("aether-theme-test"));
    }

    @BeforeEach
    void saveTheme() {
        savedTheme = Theme.snapshot();
        savedUiScale = Theme.UI_SCALE;
        savedTextScale = Theme.TEXT_SCALE;
        savedAnimTime = Theme.ANIM_TIME_MS;
        savedGuiStyle = Theme.GUI_STYLE;
    }

    @AfterEach
    void restoreTheme() throws Exception {
        Theme.restore(savedTheme);
        Theme.UI_SCALE = savedUiScale;
        Theme.TEXT_SCALE = savedTextScale;
        Theme.ANIM_TIME_MS = savedAnimTime;
        Theme.GUI_STYLE = savedGuiStyle;
        Files.deleteIfExists(themeFile());
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
        Theme.HUD_EDITED = true;
        Theme.importJson("{\"Accent\":\"FF112233\",\"guiStyle\":\"inventory\",\"presetId\":\"dusk\","
                + "\"presetModified\":false,\"hudEdited\":false}");
        assertEquals("terminal", Theme.GUI_STYLE);
        assertEquals("sage", Theme.PRESET_ID);
        assertTrue(Theme.PRESET_MODIFIED);
        assertTrue(Theme.HUD_EDITED);
        var exported = JsonParser.parseString(Theme.exportJson()).getAsJsonObject();
        assertFalse(exported.has("guiStyle"));
        assertFalse(exported.has("presetId"));
        assertFalse(exported.has("presetModified"));
        assertFalse(exported.has("hudEdited"));
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

        for (ThemePreset preset : ThemePreset.values()) {
            var json = JsonParser.parseString(preset.json()).getAsJsonObject();
            assertEquals(labels, json.keySet(), preset.label());
            Theme.rainbowEntries.add("Accent");
            Theme.PRESET_MODIFIED = true;
            preset.apply();
            Stream.concat(Theme.ENTRIES.stream(), Theme.HUD_ENTRIES.stream()).forEach(entry ->
                    assertEquals((int) Long.parseLong(json.get(entry.label).getAsString(), 16), entry.getter.get()));
            assertTrue(Theme.rainbowEntries.isEmpty());
            assertEquals(2.25f, Theme.UI_SCALE);
            assertEquals(1.25f, Theme.TEXT_SCALE);
            assertEquals(400f, Theme.ANIM_TIME_MS);
            assertEquals(9, Theme.SETTING_SPACING);
            assertEquals("terminal", Theme.GUI_STYLE);
            assertEquals(preset.id(), Theme.PRESET_ID);
            assertFalse(Theme.PRESET_MODIFIED);
            assertSame(preset, ThemePreset.current());
        }
    }

    @Test
    void presetsHaveUniqueIdsAndResolveByIdOrName() {
        Set<String> ids = new HashSet<>();
        for (ThemePreset preset : ThemePreset.values()) {
            assertTrue(ids.add(preset.id()), preset.id());
            assertSame(preset, ThemePreset.byName(preset.id()));
            assertSame(preset, ThemePreset.byName(preset.label()));
        }
        assertFalse(ids.contains(Theme.DEFAULT_COLOURS_ID));
        assertSame(ThemePreset.TOKYO_NIGHT, ThemePreset.byName("tokyo-night"));
        assertSame(ThemePreset.ROSE_PINE, ThemePreset.byName("Rose Pine"));
        assertNull(ThemePreset.byName("neon"));
        assertNull(ThemePreset.byName(null));
    }

    @Test
    void applyingAPresetRecordsItAndAnyColourEditMarksItModified() {
        ThemePreset.NORD.apply();
        Theme.saveTheme();
        assertEquals("nord", Theme.PRESET_ID);
        assertFalse(Theme.PRESET_MODIFIED);
        assertFalse(Theme.HUD_EDITED);
        assertEquals(ThemePreset.Target.MENU_AND_HUD, ThemePreset.defaultTarget());

        edit("Accent", 0xFF123456);
        assertTrue(Theme.PRESET_MODIFIED);
        assertFalse(Theme.HUD_EDITED);

        edit("HUD Accent", 0xFF654321);
        assertTrue(Theme.HUD_EDITED);
        assertEquals(ThemePreset.Target.MENU, ThemePreset.defaultTarget());

        ThemePreset.DRACULA.apply(ThemePreset.defaultTarget());
        assertEquals("dracula", Theme.PRESET_ID);
        assertFalse(Theme.PRESET_MODIFIED);
        assertEquals(ThemePreset.DRACULA.colour("Accent"), Theme.ACCENT_PRIMARY);
        assertEquals(0xFF654321, Theme.HUD_ACCENT);
        assertEquals(ThemePreset.NORD.colour("HUD Title"), Theme.HUD_TITLE);
        assertTrue(Theme.HUD_EDITED);

        ThemePreset.DRACULA.apply(ThemePreset.Target.MENU_AND_HUD);
        assertEquals(ThemePreset.DRACULA.colour("HUD Accent"), Theme.HUD_ACCENT);
        assertFalse(Theme.HUD_EDITED);
        assertEquals(ThemePreset.Target.MENU_AND_HUD, ThemePreset.defaultTarget());
    }

    @Test
    void loadingAThemeOverAPresetCountsAsAnEdit() {
        ThemePreset.SAGE.apply();
        Theme.importJson(ThemePreset.EMBER.json());
        Theme.saveTheme();
        assertEquals("sage", Theme.PRESET_ID);
        assertTrue(Theme.PRESET_MODIFIED);
        assertTrue(Theme.HUD_EDITED);
    }

    @Test
    void rainbowCyclingIsNotAnEdit() {
        ThemePreset.SLATE.apply();
        Theme.rainbowEntries.add("Accent");
        Theme.tickRainbow();
        Theme.saveTheme();
        assertFalse(Theme.PRESET_MODIFIED);
    }

    @Test
    void defaultColoursAreAPickOfTheirOwn() {
        ThemePreset.OCEAN.apply();
        edit("Text", 0xFF010203);
        Theme.applyDefaultColours(false);
        assertEquals(Theme.DEFAULT_COLOURS_ID, Theme.PRESET_ID);
        assertFalse(Theme.PRESET_MODIFIED);
        assertNull(ThemePreset.current());
        assertEquals(Theme.defaultColour(Theme.ENTRIES.getFirst()), Theme.ACCENT_PRIMARY);
        assertEquals(ThemePreset.OCEAN.colour("HUD Accent"), Theme.HUD_ACCENT);
    }

    @Test
    void revertBringsBackColoursAndPresetStateButNotDisplayPreferences() {
        ThemePreset.MIDNIGHT.apply();
        Theme.Snapshot opened = Theme.snapshot();
        ThemePreset.PAPER.apply();
        edit("HUD Error", 0xFFAA0000);
        Theme.ANIM_TIME_MS = 600f;
        Theme.restore(opened);
        assertEquals(ThemePreset.MIDNIGHT.colour("Accent"), Theme.ACCENT_PRIMARY);
        assertEquals(ThemePreset.MIDNIGHT.colour("HUD Error"), Theme.HUD_ERROR);
        assertEquals("midnight", Theme.PRESET_ID);
        assertFalse(Theme.PRESET_MODIFIED);
        assertFalse(Theme.HUD_EDITED);
        assertEquals(600f, Theme.ANIM_TIME_MS);
        Theme.saveTheme();
        assertFalse(Theme.PRESET_MODIFIED);
    }

    @Test
    void aFreshInstallStartsOnTheDefaultPreset() throws Exception {
        Files.deleteIfExists(themeFile());
        Theme.resetColorsToDefaults();
        Theme.PRESET_ID = "";
        Theme.loadTheme();
        assertEquals(ThemePreset.DEFAULT.id(), Theme.PRESET_ID);
        assertEquals(ThemePreset.DEFAULT.colour("Accent"), Theme.ACCENT_PRIMARY);
        assertEquals(ThemePreset.DEFAULT.colour("HUD Background"), Theme.HUD_BG);
        assertNotEquals(Theme.defaultColour(Theme.ENTRIES.getFirst()), Theme.ACCENT_PRIMARY);
    }

    @Test
    void themesSavedBeforePresetIdsAdoptTheMatchingPreset() throws Exception {
        writeLegacyTheme(ThemePreset.GRUVBOX.json());
        Theme.loadTheme();
        assertEquals("gruvbox", Theme.PRESET_ID);
        assertFalse(Theme.PRESET_MODIFIED);
        assertFalse(Theme.HUD_EDITED);

        var customHud = JsonParser.parseString(ThemePreset.GRUVBOX.json()).getAsJsonObject();
        customHud.addProperty("HUD Accent", "FF00FF00");
        writeLegacyTheme(customHud.toString());
        Theme.loadTheme();
        assertEquals("gruvbox", Theme.PRESET_ID);
        assertTrue(Theme.HUD_EDITED);

        var custom = JsonParser.parseString(ThemePreset.GRUVBOX.json()).getAsJsonObject();
        custom.addProperty("Accent", "FF00FF00");
        writeLegacyTheme(custom.toString());
        Theme.loadTheme();
        assertEquals("", Theme.PRESET_ID);

        Theme.resetColorsToDefaults();
        writeLegacyTheme(Theme.exportJson());
        Theme.loadTheme();
        assertEquals(Theme.DEFAULT_COLOURS_ID, Theme.PRESET_ID);
        assertFalse(Theme.HUD_EDITED);
    }

    private static void edit(String label, int argb) {
        Stream.concat(Theme.ENTRIES.stream(), Theme.HUD_ENTRIES.stream())
                .filter(entry -> entry.label.equals(label)).findFirst().orElseThrow().setter.accept(argb);
        Theme.saveTheme();
    }

    private static void writeLegacyTheme(String json) throws Exception {
        Files.writeString(themeFile(), json);
    }

    private static Path themeFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("aether_theme.json");
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
