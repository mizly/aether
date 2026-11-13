package dev.aether.ui.theme

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.awt.Color
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.util.HashSet
import java.util.function.Consumer
import java.util.function.Supplier

// core colors are mutable so users can customize them
object Theme {
    const val ANIM_FAST: Long = 150L
    const val ANIM_NORMAL: Long = 250L
    const val ANIM_SLOW: Long = 400L

    @JvmField var BG_PRIMARY = 0xFF20252B.toInt()
    @JvmField var BG_SECONDARY = 0xFF2A3038.toInt()
    @JvmField var BG_TERTIARY = 0xFF171B21.toInt()
    @JvmField var BG_HOVER = 0xFF38414D.toInt()
    @JvmField var BG_ACTIVE = 0xFF414B58.toInt()
    @JvmField var BG_FIELD = 0xFF323A45.toInt()
    @JvmField var PANEL_BG = 0xFF20262D.toInt()
    @JvmField var SIDEBAR_BG = 0xFF171C23.toInt()
    // setting rows and module cards
    @JvmField var CARD_BG = 0xFF2B333D.toInt()
    // slider value box, dropdown button, action button
    @JvmField var ELEMENT_BG = 0xFF36404B.toInt()
    @JvmField var SEPARATOR = 0xFF4A5563.toInt()

    @JvmField var ACCENT_PRIMARY = 0xFFD32F2F.toInt()
    @JvmField var ACCENT_SECONDARY = 0xFFB71C1C.toInt()
    @JvmField var ACCENT_DIM = 0x55D32F2F
    @JvmField var ACCENT_GLOW = 0x99D32F2F.toInt()
    @JvmField var ACCENT_ENABLED = 0xFF4CAF50.toInt()
    @JvmField var ACCENT_WARNING = 0xFFF59E0B.toInt()
    @JvmField var ACCENT_ERROR = 0xFFEF4444.toInt()
    @JvmField var STATUS_SUCCESS = 0xFF10B981.toInt()
    @JvmField var STATUS_WARNING = 0xFFF59E0B.toInt()
    @JvmField var STATUS_ERROR = 0xFFEF4444.toInt()

    @JvmField var BORDER_DEFAULT = 0xFF667383.toInt()
    @JvmField var BORDER_HOVER = 0xFF8795A5.toInt()
    @JvmField var BORDER_ACTIVE = 0xFFD32F2F.toInt()
    @JvmField var BORDER_ACCENT = 0xFFD32F2F.toInt()
    const val BORDER_DRAG = 0xFFAAAAFF.toInt()
    const val BORDER_RESIZE = 0xFFFFAA00.toInt()

    @JvmField var TEXT_PRIMARY = 0xFFF5F7FA.toInt()
    @JvmField var TEXT_SECONDARY = 0xFFC3CBD5.toInt()
    const val TEXT_TERTIARY = 0xFF6B7280.toInt()
    @JvmField var TEXT_DIM = 0xFFB0BAC6.toInt()
    const val TEXT_DISABLED = 0xFF4B5563.toInt()
    const val TEXT_ACCENT = 0xFF6366F1.toInt()
    @JvmField var TEXT_MUTED = 0xFF9AA6B2.toInt()
    @JvmField var TEXT_LABEL = 0xFFF0F3F6.toInt()
    @JvmField var TEXT_VALUE = 0xFFD2D9E1.toInt()
    @JvmField var GROUP_ACTIVE = 0xFFB8C4D1.toInt()

    @JvmField var PILL_TRACK = 0xFF46515E.toInt()
    @JvmField var PILL_KNOB_OFF = 0xFFB4BEC9.toInt()
    @JvmField var SLIDER_LEFT = 0xFF4FA3D1.toInt()
    @JvmField var DROPDOWN_BTN_BG = 0xFF36404B.toInt()
    @JvmField var ACTION_BTN_BG = 0xFF36404B.toInt()
    @JvmField var ACTION_BTN_HOVER = 0xFF46515E.toInt()

    const val PADDING = 8
    const val MARGIN = 4
    const val CORNER_RADIUS = 4
    const val BORDER_WIDTH = 1
    const val TITLE_BAR_HEIGHT = 28
    const val TAB_WIDTH = 100
    const val TAB_HEIGHT = 30
    const val SCROLLBAR_WIDTH = 6
    const val WINDOW_MIN_WIDTH = 450
    const val WINDOW_MIN_HEIGHT = 400
    const val WINDOW_DEFAULT_WIDTH = 620
    const val WINDOW_DEFAULT_HEIGHT = 450
    const val BORDER_THIN = 1
    const val BORDER_MEDIUM = 2
    const val BORDER_THICK = 3
    const val SPACING_XS = 2
    const val SPACING_SM = 5
    const val SPACING_MD = 10
    const val SPACING_LG = 15
    const val SPACING_XL = 20

    @JvmField var HUD_BG = 0xFF1F2730.toInt()
    @JvmField var HUD_BORDER = 0xFF7E9AB2.toInt()
    @JvmField var HUD_TITLE = 0xFFFFFFFF.toInt()
    @JvmField var HUD_LABEL = 0xFFC3CBD5.toInt()
    @JvmField var HUD_VALUE = 0xFFFFFFFF.toInt()
    @JvmField var HUD_ACCENT = 0xFF70B7D9.toInt()
    @JvmField var HUD_SEP = 0xFF5D7182.toInt()
    @JvmField var HUD_BAR_BG = 0xFF2F3A45.toInt()
    @JvmField var HUD_SUCCESS = 0xFF8AD9A5.toInt()
    @JvmField var HUD_WARNING = 0xFFE8BF78.toInt()
    @JvmField var HUD_ERROR = 0xFFED8A93.toInt()

    const val ANIM_TIME_MIN_MS = 50f
    const val ANIM_TIME_MAX_MS = 1000f
    @JvmField var ANIM_TIME_MS = 250f
    const val UI_SCALE_MIN = 0.5f
    const val UI_SCALE_MAX = 3.0f
    // 1.0 is pixel-perfect; mirrored into MainGUI.uiScale at load
    @JvmField var UI_SCALE = 1.5f
    const val TEXT_SCALE_MIN = 0.75f
    const val TEXT_SCALE_MAX = 2.0f
    @JvmField var TEXT_SCALE = 1.0f
    @JvmField var SETTING_SPACING = 4

    // ThemeEntry labels that cycle through rainbow colors each frame
    @JvmField val rainbowEntries: MutableSet<String> = HashSet()
    private var rainbowHue = 0f

    @JvmStatic fun tickRainbow() {
        if (rainbowEntries.isEmpty()) return
        rainbowHue = (rainbowHue + 0.003f) % 1.0f
        val rgb = Color.HSBtoRGB(rainbowHue, 0.8f, 1.0f)
        val color = withAlpha(rgb, 0xFF)
        for (e in ENTRIES) if (rainbowEntries.contains(e.label)) e.setter.accept(color)
    }

    class ThemeEntry {
        @JvmField val label: String
        @JvmField val getter: Supplier<Int>
        @JvmField val setter: Consumer<Int>
        constructor(label: String, getter: Supplier<Int>, setter: Consumer<Int>) {
            this.label = label
            this.getter = getter
            this.setter = setter
        }
    }

    private fun entry(label: String, getter: Supplier<Int>, setter: Consumer<Int>) = ThemeEntry(label, getter, setter)

    @JvmField val ENTRIES: List<ThemeEntry> = listOf(
        entry("Accent", Supplier { ACCENT_PRIMARY }, Consumer { ACCENT_PRIMARY = it }),
        entry("Accent 2", Supplier { ACCENT_SECONDARY }, Consumer { ACCENT_SECONDARY = it }),
        entry("Panel BG", Supplier { BG_SECONDARY }, Consumer { BG_SECONDARY = it }),
        entry("Hover BG", Supplier { BG_HOVER }, Consumer { BG_HOVER = it }),
        entry("Input BG", Supplier { BG_FIELD }, Consumer { BG_FIELD = it }),
        entry("Card BG", Supplier { CARD_BG }, Consumer { CARD_BG = it }),
        entry("Element BG", Supplier { ELEMENT_BG }, Consumer { ELEMENT_BG = it }),
        entry("Sidebar BG", Supplier { SIDEBAR_BG }, Consumer { SIDEBAR_BG = it }),
        entry("Main Panel BG", Supplier { PANEL_BG }, Consumer { PANEL_BG = it }),
        entry("Border", Supplier { BORDER_DEFAULT }, Consumer { BORDER_DEFAULT = it }),
        entry("Separator", Supplier { SEPARATOR }, Consumer { SEPARATOR = it }),
        entry("Text", Supplier { TEXT_PRIMARY }, Consumer { TEXT_PRIMARY = it }),
        entry("Text Dim", Supplier { TEXT_SECONDARY }, Consumer { TEXT_SECONDARY = it }),
        entry("Text Muted", Supplier { TEXT_MUTED }, Consumer { TEXT_MUTED = it }),
        entry("Text Label", Supplier { TEXT_LABEL }, Consumer { TEXT_LABEL = it }),
        entry("Text Value", Supplier { TEXT_VALUE }, Consumer { TEXT_VALUE = it }),
        entry("Group Active", Supplier { GROUP_ACTIVE }, Consumer { GROUP_ACTIVE = it }),
        entry("[Toggle] Track", Supplier { PILL_TRACK }, Consumer { PILL_TRACK = it }),
        entry("[Toggle] Knob", Supplier { PILL_KNOB_OFF }, Consumer { PILL_KNOB_OFF = it }),
        entry("[Slider] Left", Supplier { SLIDER_LEFT }, Consumer { SLIDER_LEFT = it }),
        entry("[Dropdown] BG", Supplier { DROPDOWN_BTN_BG }, Consumer { DROPDOWN_BTN_BG = it }),
        entry("[Action] BG", Supplier { ACTION_BTN_BG }, Consumer { ACTION_BTN_BG = it }),
        entry("[Action] Hover", Supplier { ACTION_BTN_HOVER }, Consumer { ACTION_BTN_HOVER = it })
    )

    @JvmField val HUD_ENTRIES: List<ThemeEntry> = listOf(
        entry("HUD Background", Supplier { HUD_BG }, Consumer { HUD_BG = it }),
        entry("HUD Border", Supplier { HUD_BORDER }, Consumer { HUD_BORDER = it }),
        entry("HUD Title", Supplier { HUD_TITLE }, Consumer { HUD_TITLE = it }),
        entry("HUD Label", Supplier { HUD_LABEL }, Consumer { HUD_LABEL = it }),
        entry("HUD Value", Supplier { HUD_VALUE }, Consumer { HUD_VALUE = it }),
        entry("HUD Accent", Supplier { HUD_ACCENT }, Consumer { HUD_ACCENT = it }),
        entry("HUD Separator", Supplier { HUD_SEP }, Consumer { HUD_SEP = it }),
        entry("HUD Bar BG", Supplier { HUD_BAR_BG }, Consumer { HUD_BAR_BG = it }),
        entry("HUD Success", Supplier { HUD_SUCCESS }, Consumer { HUD_SUCCESS = it }),
        entry("HUD Warning", Supplier { HUD_WARNING }, Consumer { HUD_WARNING = it }),
        entry("HUD Error", Supplier { HUD_ERROR }, Consumer { HUD_ERROR = it })
    )

    private val DEFAULT_MENU_COLORS = IntArray(ENTRIES.size) { ENTRIES[it].getter.get() }
    private val DEFAULT_HUD_COLORS = IntArray(HUD_ENTRIES.size) { HUD_ENTRIES[it].getter.get() }
    private val THEME_FILE: File = FabricLoader.getInstance().configDir.resolve("aether_theme.json").toFile()
    private val GSON: Gson = GsonBuilder().setPrettyPrinting().create()

    @JvmStatic fun saveTheme() {
        val obj = JsonObject()
        for (e in ENTRIES) obj.addProperty(e.label, String.format("%08X", e.getter.get()))
        for (e in HUD_ENTRIES) obj.addProperty(e.label, String.format("%08X", e.getter.get()))
        obj.addProperty("animSpeed", ANIM_TIME_MS)
        obj.addProperty("settingSpacing", SETTING_SPACING)
        obj.addProperty("uiScale", UI_SCALE)
        obj.addProperty("textScale", TEXT_SCALE)
        val rainbowArr = JsonArray()
        for (s in rainbowEntries) rainbowArr.add(s)
        obj.add("rainbowEntries", rainbowArr)
        try { FileWriter(THEME_FILE).use { it.write(GSON.toJson(obj)) } } catch (ex: IOException) { ex.printStackTrace() }
    }

    @JvmStatic fun loadTheme() {
        if (!THEME_FILE.exists()) return
        try {
            FileReader(THEME_FILE).use { fr ->
                val obj = JsonParser.parseReader(fr).asJsonObject
                for (e in ENTRIES) if (obj.has(e.label)) e.setter.accept(java.lang.Long.parseLong(obj.get(e.label).asString, 16).toInt())
                for (e in HUD_ENTRIES) if (obj.has(e.label)) e.setter.accept(java.lang.Long.parseLong(obj.get(e.label).asString, 16).toInt())
                if (obj.has("animSpeed")) ANIM_TIME_MS = parseAnimationTime(obj.get("animSpeed").asFloat)
                if (obj.has("settingSpacing")) SETTING_SPACING = obj.get("settingSpacing").asInt
                if (obj.has("uiScale")) UI_SCALE = Math.max(UI_SCALE_MIN, Math.min(UI_SCALE_MAX, obj.get("uiScale").asFloat))
                if (obj.has("textScale")) TEXT_SCALE = Math.max(TEXT_SCALE_MIN, Math.min(TEXT_SCALE_MAX, obj.get("textScale").asFloat))
                rainbowEntries.clear()
                if (obj.has("rainbowEntries")) obj.getAsJsonArray("rainbowEntries").forEach { rainbowEntries.add(it.asString) }
                else if (obj.has("rainbowTheme") && obj.get("rainbowTheme").asBoolean) rainbowEntries.add("Accent")
            }
        } catch (ex: Exception) { ex.printStackTrace() }
    }

    @JvmStatic fun exportJson(): String {
        val obj = JsonObject()
        for (e in ENTRIES) obj.addProperty(e.label, String.format("%08X", e.getter.get()))
        for (e in HUD_ENTRIES) obj.addProperty(e.label, String.format("%08X", e.getter.get()))
        obj.addProperty("animSpeed", ANIM_TIME_MS)
        obj.addProperty("settingSpacing", SETTING_SPACING)
        val rainbowArr2 = JsonArray()
        for (s in rainbowEntries) rainbowArr2.add(s)
        obj.add("rainbowEntries", rainbowArr2)
        return GSON.toJson(obj)
    }

    // Scale is a display preference, not theme content; only loadTheme() restores it.
    @JvmStatic fun importJson(json: String) {
        try {
            val obj = JsonParser.parseString(json).asJsonObject
            for (e in ENTRIES) if (obj.has(e.label)) e.setter.accept(java.lang.Long.parseLong(obj.get(e.label).asString, 16).toInt())
            for (e in HUD_ENTRIES) if (obj.has(e.label)) e.setter.accept(java.lang.Long.parseLong(obj.get(e.label).asString, 16).toInt())
            if (obj.has("animSpeed")) ANIM_TIME_MS = parseAnimationTime(obj.get("animSpeed").asFloat)
            if (obj.has("settingSpacing")) SETTING_SPACING = obj.get("settingSpacing").asInt
            rainbowEntries.clear()
            if (obj.has("rainbowEntries")) obj.getAsJsonArray("rainbowEntries").forEach { rainbowEntries.add(it.asString) }
            else if (obj.has("rainbowTheme") && obj.get("rainbowTheme").asBoolean) rainbowEntries.add("Accent")
        } catch (ex: Exception) { ex.printStackTrace() }
    }

    @JvmStatic fun resetToDefaults() {
        resetColorsToDefaults()
        ANIM_TIME_MS = 250f
        SETTING_SPACING = 4
        UI_SCALE = 1.5f
        TEXT_SCALE = 1.0f
    }

    @JvmStatic fun resetColorsToDefaults() {
        for (i in ENTRIES.indices) ENTRIES[i].setter.accept(DEFAULT_MENU_COLORS[i])
        for (i in HUD_ENTRIES.indices) HUD_ENTRIES[i].setter.accept(DEFAULT_HUD_COLORS[i])
        rainbowEntries.clear()
    }

    @JvmStatic fun withAlpha(color: Int, alpha: Int): Int {
        val clamped = Math.max(0, Math.min(255, alpha))
        return (clamped shl 24) or (color and 0x00FFFFFF)
    }

    @JvmStatic fun animationFactor(): Float = animationFactor(1f)

    @JvmStatic fun animationFactor(multiplier: Float): Float {
        val durationMs = Math.max(1f, ANIM_TIME_MS / Math.max(0.001f, multiplier))
        val frameMs = 1000f / 60f
        return 1f - Math.exp(-(frameMs / (durationMs / 3f)).toDouble()).toFloat()
    }

    private fun parseAnimationTime(storedValue: Float): Float {
        if (storedValue <= 1f) return legacyAnimSpeedToDuration(storedValue)
        return Math.max(ANIM_TIME_MIN_MS, Math.min(ANIM_TIME_MAX_MS, storedValue))
    }

    private fun legacyAnimSpeedToDuration(legacySpeed: Float): Float {
        val speed = Math.max(0.001f, Math.min(0.95f, legacySpeed))
        val frameMs = 1000f / 60f
        val durationMs = (-frameMs * 3f / Math.log((1f - speed).toDouble())).toFloat()
        return Math.max(ANIM_TIME_MIN_MS, Math.min(ANIM_TIME_MAX_MS, durationMs))
    }

    @JvmStatic fun withAlpha(color: Int, alphaPercent: Float): Int = withAlpha(color, (alphaPercent * 255).toInt())

    @JvmStatic fun blend(color1: Int, color2: Int, ratio: Float): Int {
        val clamped = Math.max(0f, Math.min(1f, ratio))
        val a1 = (color1 shr 24) and 0xFF
        val r1 = (color1 shr 16) and 0xFF
        val g1 = (color1 shr 8) and 0xFF
        val b1 = color1 and 0xFF
        val a2 = (color2 shr 24) and 0xFF
        val r2 = (color2 shr 16) and 0xFF
        val g2 = (color2 shr 8) and 0xFF
        val b2 = color2 and 0xFF
        val a = (a1 + (a2 - a1) * clamped).toInt()
        val r = (r1 + (r2 - r1) * clamped).toInt()
        val g = (g1 + (g2 - g1) * clamped).toInt()
        val b = (b1 + (b2 - b1) * clamped).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    @JvmStatic fun lighter(color: Int, amount: Float): Int = blend(color, 0xFFFFFFFF.toInt(), amount)
    @JvmStatic fun darker(color: Int, amount: Float): Int = blend(color, 0xFF000000.toInt(), amount)
    @JvmStatic fun getAlpha(color: Int): Int = (color shr 24) and 0xFF
    @JvmStatic fun getRed(color: Int): Int = (color shr 16) and 0xFF
    @JvmStatic fun getGreen(color: Int): Int = (color shr 8) and 0xFF
    @JvmStatic fun getBlue(color: Int): Int = color and 0xFF

    @JvmStatic fun fromARGB(alpha: Int, red: Int, green: Int, blue: Int): Int = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    @JvmStatic fun fromRGB(red: Int, green: Int, blue: Int): Int = fromARGB(255, red, green, blue)

    @JvmStatic fun getRainbowColor(time: Float, position: Float, alpha: Int): Int {
        val hue = (time + position) % 1.0f
        val rgb = Color.HSBtoRGB(hue, 0.8f, 1.0f)
        return withAlpha(rgb, alpha)
    }

    @JvmStatic fun hsvToArgb(h: Float, s: Float, v: Float, alpha: Int): Int = withAlpha(Color.HSBtoRGB(h, s, v), alpha)

    // [hue, saturation, value], all 0..1
    @JvmStatic fun argbToHsv(argb: Int): FloatArray {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return Color.RGBtoHSB(r, g, b, null)
    }
}
