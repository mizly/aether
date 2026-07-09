package dev.aether.ui.theme;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;

import java.awt.Color;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Theme system for the UI library.
 * Core colors are mutable so users can customize them.
 */
public class Theme {

    // ============================================================
    // ANIMATION TIMINGS
    // ============================================================

    public static final long ANIM_FAST = 150L;
    public static final long ANIM_NORMAL = 250L;
    public static final long ANIM_SLOW = 400L;

    // ============================================================
    // BACKGROUND COLORS (mutable for theming)
    // ============================================================

    public static int BG_PRIMARY    = 0xFF141414;
    public static int BG_SECONDARY  = 0xFF1C1C1C;
    public static int BG_TERTIARY   = 0xFF111111;
    public static int BG_HOVER      = 0xFF252525;
    public static int BG_ACTIVE     = 0xFF222222;
    public static int BG_FIELD      = 0xFF1A1A1A;

    /** Main panel background. */
    public static int PANEL_BG      = 0xFF0E0E0F;
    /** Sidebar background. */
    public static int SIDEBAR_BG    = 0xFF111114;
    /** Setting-row and module-card background. */
    public static int CARD_BG       = 0xFF161619;
    /** Element background - slider value box, dropdown button, action button. */
    public static int ELEMENT_BG    = 0xFF1C1C20;
    /** Separator / divider line color. */
    public static int SEPARATOR     = 0xFF1B1B1D;

    // ============================================================
    // ACCENT COLORS (mutable for theming)
    // ============================================================

    public static int ACCENT_PRIMARY   = 0xFFD32F2F;
    public static int ACCENT_SECONDARY = 0xFFB71C1C;
    public static int ACCENT_DIM       = 0x40D32F2F;
    public static int ACCENT_GLOW      = 0x80D32F2F;
    public static int ACCENT_ENABLED   = 0xFF4CAF50;
    public static int ACCENT_WARNING   = 0xFFF59E0B;
    public static int ACCENT_ERROR     = 0xFFEF4444;

    public static int STATUS_SUCCESS = 0xFF10B981;
    public static int STATUS_WARNING = 0xFFF59E0B;
    public static int STATUS_ERROR   = 0xFFEF4444;

    // ============================================================
    // BORDER COLORS (mutable for theming)
    // ============================================================

    public static int BORDER_DEFAULT = 0xFF2A2A2A;
    public static int BORDER_HOVER   = 0xFF3A3A3A;
    public static int BORDER_ACTIVE  = 0xFFD32F2F;
    public static int BORDER_ACCENT  = 0xFFD32F2F;
    public static final int BORDER_DRAG   = 0xFFAAAAFF;
    public static final int BORDER_RESIZE = 0xFFFFAA00;

    // ============================================================
    // TEXT COLORS (mutable for theming)
    // ============================================================

    public static int TEXT_PRIMARY   = 0xFFEEEEEE;
    public static int TEXT_SECONDARY = 0xFF888888;
    public static final int TEXT_TERTIARY  = 0xFF6B7280;
    public static int TEXT_DIM       = 0xFF6B7280;
    public static final int TEXT_DISABLED  = 0xFF4B5563;
    public static final int TEXT_ACCENT    = 0xFF6366F1;

    /** Muted / inactive text - unselected tabs, descriptions, dim labels. */
    public static int TEXT_MUTED     = 0xFF44444C;
    /** Setting-row label text. */
    public static int TEXT_LABEL     = 0xFFE8E8EC;
    /** Secondary value text - slider value, dropdown value, action button default. */
    public static int TEXT_VALUE     = 0xFF7C7C88;
    /** Enabled group label color in flat (Colors/Settings) view. */
    public static int GROUP_ACTIVE   = 0xFF9090B8;

    // ============================================================
    // PER-SETTING-TYPE COLORS (mutable for theming)
    // ============================================================

    // Toggle pill
    /** Off-state track background for toggle pill. */
    public static int PILL_TRACK     = 0xFF222226;
    /** Off-state knob color for toggle pill. */
    public static int PILL_KNOB_OFF  = 0xFF44444C;

    // Slider
    /** Left/start color of slider gradient fill. */
    public static int SLIDER_LEFT    = 0xFF5C3FD4;

    // Dropdown
    public static int DROPDOWN_BTN_BG       = 0xFF1A1A1A;

    // Action button
    public static int ACTION_BTN_BG    = 0xFF1A1A1A;
    public static int ACTION_BTN_HOVER = 0xFF252525;

    // ============================================================
    // LAYOUT CONSTANTS
    // ============================================================

    public static final int PADDING       = 8;
    public static final int MARGIN        = 4;
    public static final int CORNER_RADIUS = 4;
    public static final int BORDER_WIDTH  = 1;

    public static final int TITLE_BAR_HEIGHT = 28;
    public static final int TAB_WIDTH        = 100;
    public static final int TAB_HEIGHT       = 30;
    public static final int SCROLLBAR_WIDTH  = 6;

    public static final int WINDOW_MIN_WIDTH      = 450;
    public static final int WINDOW_MIN_HEIGHT     = 400;
    public static final int WINDOW_DEFAULT_WIDTH  = 620;
    public static final int WINDOW_DEFAULT_HEIGHT = 450;

    public static final int BORDER_THIN   = 1;
    public static final int BORDER_MEDIUM = 2;
    public static final int BORDER_THICK  = 3;

    public static final int SPACING_XS = 2;
    public static final int SPACING_SM = 5;
    public static final int SPACING_MD = 10;
    public static final int SPACING_LG = 15;
    public static final int SPACING_XL = 20;

    // ============================================================
    // HUD COLORS (independent from GUI theme)
    // ============================================================

    public static int HUD_BG     = 0xFF141424;
    public static int HUD_BORDER = 0xFF6464B4;
    public static int HUD_TITLE  = 0xFFFFFFFF;
    public static int HUD_LABEL  = 0xFFAAAAAA;
    public static int HUD_VALUE  = 0xFFFFFFFF;
    public static int HUD_ACCENT = 0xFF6464B4;
    public static int HUD_SEP    = 0xFF4A4A88;
    public static int HUD_BAR_BG = 0xFF1A1A32;

    // ============================================================
    // ANIMATION SPEED / SPACING
    // ============================================================

    public static final float ANIM_TIME_MIN_MS = 50f;
    public static final float ANIM_TIME_MAX_MS = 1000f;
    /** Target animation time for GUI components in milliseconds. */
    public static float ANIM_TIME_MS = 250f;

    /** Extra vertical spacing between settings within a module card (px). */
    public static int SETTING_SPACING = 4;

    /** Set of ThemeEntry labels that cycle through rainbow colors each frame. */
    public static final Set<String> rainbowEntries = new HashSet<>();
    private static float rainbowHue = 0f;

    public static void tickRainbow() {
        if (rainbowEntries.isEmpty()) return;
        rainbowHue = (rainbowHue + 0.003f) % 1.0f;
        int rgb = Color.HSBtoRGB(rainbowHue, 0.8f, 1.0f);
        int color = withAlpha(rgb, 0xFF);
        for (ThemeEntry e : ENTRIES) {
            if (rainbowEntries.contains(e.label)) {
                e.setter.accept(color);
            }
        }
    }

    // ============================================================
    // THEME ENTRIES
    // ============================================================

    public static class ThemeEntry {
        public final String label;
        public final Supplier<Integer> getter;
        public final Consumer<Integer> setter;

        public ThemeEntry(String label, Supplier<Integer> getter, Consumer<Integer> setter) {
            this.label = label;
            this.getter = getter;
            this.setter = setter;
        }
    }

    private static ThemeEntry entry(String label, Supplier<Integer> getter, Consumer<Integer> setter) {
        return new ThemeEntry(label, getter, setter);
    }

    public static final java.util.List<ThemeEntry> ENTRIES = java.util.List.of(
        // Accent
        entry("Accent",          () -> ACCENT_PRIMARY,   v -> ACCENT_PRIMARY   = v),
        entry("Accent 2",        () -> ACCENT_SECONDARY, v -> ACCENT_SECONDARY = v),
        // Backgrounds
        entry("Panel BG",        () -> BG_SECONDARY,     v -> BG_SECONDARY     = v),
        entry("Hover BG",        () -> BG_HOVER,         v -> BG_HOVER         = v),
        entry("Input BG",        () -> BG_FIELD,         v -> BG_FIELD         = v),
        entry("Card BG",         () -> CARD_BG,          v -> CARD_BG          = v),
        entry("Element BG",      () -> ELEMENT_BG,       v -> ELEMENT_BG       = v),
        entry("Sidebar BG",      () -> SIDEBAR_BG,       v -> SIDEBAR_BG       = v),
        entry("Main Panel BG",   () -> PANEL_BG,         v -> PANEL_BG         = v),
        // Borders / separators
        entry("Border",          () -> BORDER_DEFAULT,   v -> BORDER_DEFAULT   = v),
        entry("Separator",       () -> SEPARATOR,        v -> SEPARATOR        = v),
        // Text
        entry("Text",            () -> TEXT_PRIMARY,     v -> TEXT_PRIMARY     = v),
        entry("Text Dim",        () -> TEXT_SECONDARY,   v -> TEXT_SECONDARY   = v),
        entry("Text Muted",      () -> TEXT_MUTED,       v -> TEXT_MUTED       = v),
        entry("Text Label",      () -> TEXT_LABEL,       v -> TEXT_LABEL       = v),
        entry("Text Value",      () -> TEXT_VALUE,       v -> TEXT_VALUE       = v),
        entry("Group Active",    () -> GROUP_ACTIVE,     v -> GROUP_ACTIVE     = v),
        // Toggle
        entry("[Toggle] Track",  () -> PILL_TRACK,       v -> PILL_TRACK       = v),
        entry("[Toggle] Knob",   () -> PILL_KNOB_OFF,    v -> PILL_KNOB_OFF    = v),
        // Slider
        entry("[Slider] Left",   () -> SLIDER_LEFT,      v -> SLIDER_LEFT      = v),
        // Dropdown
        entry("[Dropdown] BG",   () -> DROPDOWN_BTN_BG,  v -> DROPDOWN_BTN_BG  = v),
        // Action
        entry("[Action] BG",     () -> ACTION_BTN_BG,    v -> ACTION_BTN_BG    = v),
        entry("[Action] Hover",  () -> ACTION_BTN_HOVER, v -> ACTION_BTN_HOVER = v)
    );

    public static final java.util.List<ThemeEntry> HUD_ENTRIES = java.util.List.of(
        entry("HUD Background", () -> HUD_BG,     v -> HUD_BG     = v),
        entry("HUD Border",     () -> HUD_BORDER,  v -> HUD_BORDER  = v),
        entry("HUD Title",      () -> HUD_TITLE,   v -> HUD_TITLE   = v),
        entry("HUD Label",      () -> HUD_LABEL,   v -> HUD_LABEL   = v),
        entry("HUD Value",      () -> HUD_VALUE,   v -> HUD_VALUE   = v),
        entry("HUD Accent",     () -> HUD_ACCENT,  v -> HUD_ACCENT  = v),
        entry("HUD Separator",  () -> HUD_SEP,     v -> HUD_SEP     = v),
        entry("HUD Bar BG",     () -> HUD_BAR_BG,  v -> HUD_BAR_BG  = v)
    );

    // ============================================================
    // SAVE / LOAD
    // ============================================================

    private static final File THEME_FILE = FabricLoader.getInstance().getConfigDir()
            .resolve("aether_theme.json").toFile();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void saveTheme() {
        JsonObject obj = new JsonObject();
        for (ThemeEntry e : ENTRIES) {
            obj.addProperty(e.label, String.format("%08X", e.getter.get()));
        }
        for (ThemeEntry e : HUD_ENTRIES) {
            obj.addProperty(e.label, String.format("%08X", e.getter.get()));
        }
        obj.addProperty("animSpeed", ANIM_TIME_MS);
        obj.addProperty("settingSpacing", SETTING_SPACING);
        JsonArray rainbowArr = new JsonArray();
        for (String s : rainbowEntries) rainbowArr.add(s);
        obj.add("rainbowEntries", rainbowArr);
        try (FileWriter fw = new FileWriter(THEME_FILE)) {
            fw.write(GSON.toJson(obj));
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    public static void loadTheme() {
        if (!THEME_FILE.exists()) return;
        try (FileReader fr = new FileReader(THEME_FILE)) {
            JsonObject obj = JsonParser.parseReader(fr).getAsJsonObject();
            for (ThemeEntry e : ENTRIES) {
                if (obj.has(e.label)) {
                    String hex = obj.get(e.label).getAsString();
                    e.setter.accept((int) Long.parseLong(hex, 16));
                }
            }
            for (ThemeEntry e : HUD_ENTRIES) {
                if (obj.has(e.label)) {
                    String hex = obj.get(e.label).getAsString();
                    e.setter.accept((int) Long.parseLong(hex, 16));
                }
            }
            if (obj.has("animSpeed"))     ANIM_TIME_MS    = parseAnimationTime(obj.get("animSpeed").getAsFloat());
            if (obj.has("settingSpacing")) SETTING_SPACING = obj.get("settingSpacing").getAsInt();
            rainbowEntries.clear();
            if (obj.has("rainbowEntries")) {
                obj.get("rainbowEntries").getAsJsonArray()
                        .forEach(e -> rainbowEntries.add(e.getAsString()));
            } else if (obj.has("rainbowTheme") && obj.get("rainbowTheme").getAsBoolean()) {
                rainbowEntries.add("Accent"); // backward compat
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public static String exportJson() {
        JsonObject obj = new JsonObject();
        for (ThemeEntry e : ENTRIES) {
            obj.addProperty(e.label, String.format("%08X", e.getter.get()));
        }
        for (ThemeEntry e : HUD_ENTRIES) {
            obj.addProperty(e.label, String.format("%08X", e.getter.get()));
        }
        obj.addProperty("animSpeed", ANIM_TIME_MS);
        obj.addProperty("settingSpacing", SETTING_SPACING);
        JsonArray rainbowArr2 = new JsonArray();
        for (String s : rainbowEntries) rainbowArr2.add(s);
        obj.add("rainbowEntries", rainbowArr2);
        return GSON.toJson(obj);
    }

    public static void importJson(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            for (ThemeEntry e : ENTRIES) {
                if (obj.has(e.label)) {
                    String hex = obj.get(e.label).getAsString();
                    e.setter.accept((int) Long.parseLong(hex, 16));
                }
            }
            for (ThemeEntry e : HUD_ENTRIES) {
                if (obj.has(e.label)) {
                    String hex = obj.get(e.label).getAsString();
                    e.setter.accept((int) Long.parseLong(hex, 16));
                }
            }
            if (obj.has("animSpeed"))     ANIM_TIME_MS    = parseAnimationTime(obj.get("animSpeed").getAsFloat());
            if (obj.has("settingSpacing")) SETTING_SPACING = obj.get("settingSpacing").getAsInt();
            rainbowEntries.clear();
            if (obj.has("rainbowEntries")) {
                obj.get("rainbowEntries").getAsJsonArray()
                        .forEach(e -> rainbowEntries.add(e.getAsString()));
            } else if (obj.has("rainbowTheme") && obj.get("rainbowTheme").getAsBoolean()) {
                rainbowEntries.add("Accent"); // backward compat
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    // ============================================================
    // COLOR UTILITY METHODS
    // ============================================================

    public static int withAlpha(int color, int alpha) {
        alpha = Math.max(0, Math.min(255, alpha));
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    public static float animationFactor() {
        return animationFactor(1f);
    }

    public static float animationFactor(float multiplier) {
        float durationMs = Math.max(1f, ANIM_TIME_MS / Math.max(0.001f, multiplier));
        float frameMs = 1000f / 60f;
        return 1f - (float) Math.exp(-frameMs / (durationMs / 3f));
    }

    private static float parseAnimationTime(float storedValue) {
        if (storedValue <= 1f) {
            return legacyAnimSpeedToDuration(storedValue);
        }
        return Math.max(ANIM_TIME_MIN_MS, Math.min(ANIM_TIME_MAX_MS, storedValue));
    }

    private static float legacyAnimSpeedToDuration(float legacySpeed) {
        float speed = Math.max(0.001f, Math.min(0.95f, legacySpeed));
        float frameMs = 1000f / 60f;
        float durationMs = (float) (-frameMs * 3f / Math.log(1f - speed));
        return Math.max(ANIM_TIME_MIN_MS, Math.min(ANIM_TIME_MAX_MS, durationMs));
    }

    public static int withAlpha(int color, float alphaPercent) {
        return withAlpha(color, (int) (alphaPercent * 255));
    }

    public static int blend(int color1, int color2, float ratio) {
        ratio = Math.max(0f, Math.min(1f, ratio));

        int a1 = (color1 >> 24) & 0xFF;
        int r1 = (color1 >> 16) & 0xFF;
        int g1 = (color1 >> 8)  & 0xFF;
        int b1 =  color1        & 0xFF;

        int a2 = (color2 >> 24) & 0xFF;
        int r2 = (color2 >> 16) & 0xFF;
        int g2 = (color2 >> 8)  & 0xFF;
        int b2 =  color2        & 0xFF;

        int a = (int) (a1 + (a2 - a1) * ratio);
        int r = (int) (r1 + (r2 - r1) * ratio);
        int g = (int) (g1 + (g2 - g1) * ratio);
        int b = (int) (b1 + (b2 - b1) * ratio);

        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static int lighter(int color, float amount) {
        return blend(color, 0xFFFFFFFF, amount);
    }

    public static int darker(int color, float amount) {
        return blend(color, 0xFF000000, amount);
    }

    public static int getAlpha(int color) { return (color >> 24) & 0xFF; }
    public static int getRed(int color)   { return (color >> 16) & 0xFF; }
    public static int getGreen(int color) { return (color >> 8)  & 0xFF; }
    public static int getBlue(int color)  { return  color        & 0xFF; }

    public static int fromARGB(int alpha, int red, int green, int blue) {
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    public static int fromRGB(int red, int green, int blue) {
        return fromARGB(255, red, green, blue);
    }

    public static int getRainbowColor(float time, float position, int alpha) {
        float hue = (time + position) % 1.0f;
        int rgb = Color.HSBtoRGB(hue, 0.8f, 1.0f);
        return withAlpha(rgb, alpha);
    }

    // ============================================================
    // HSV UTILITIES
    // ============================================================

    public static int hsvToArgb(float h, float s, float v, int alpha) {
        int rgb = Color.HSBtoRGB(h, s, v);
        return withAlpha(rgb, alpha);
    }

    /** Returns [hue, saturation, value] in 0..1 range. */
    public static float[] argbToHsv(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8)  & 0xFF;
        int b =  argb        & 0xFF;
        return Color.RGBtoHSB(r, g, b, null);
    }
}
