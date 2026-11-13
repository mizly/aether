package dev.aether.hud

import dev.aether.macro.MacroState
import dev.aether.renderer.NVGRenderer
import dev.aether.ui.theme.Theme
import dev.aether.ui.util.Fonts
import dev.aether.util.AetherLang

class HudStyle private constructor() {
    companion object {
        @JvmField val RADIUS = 8f
        @JvmField val PAD = 12f
        @JvmField val CONTENT_Y = 38f

    @JvmStatic
    fun alpha(color: Int, opacity: Float): Int =
        Theme.withAlpha(color, Math.round((color ushr 24) * opacity.coerceIn(0f, 1f)))

    @JvmStatic
    fun panel(nvg: NVGRenderer, width: Float, height: Float) {
        nvg.shadow(0f, 2f, width, height, RADIUS, 10f, alpha(Theme.HUD_BG and 0xFF000000.toInt(), 0.3f))
        nvg.roundedRect(0f, 0f, width, height, RADIUS, Theme.HUD_BG)
        nvg.rectOutline(0f, 0f, width, height, RADIUS, 0.8f, Theme.HUD_BORDER)
    }

    @JvmStatic
    fun accent(nvg: NVGRenderer, width: Float, left: Int, right: Int) {
        val length = width - PAD * 2
        if (length <= 0f) return
        val fade = minOf(28f, length * 0.25f)
        val fadeLeft = Theme.blend(left, right, fade / length)
        val fadeRight = Theme.blend(left, right, 1f - fade / length)
        nvg.horizontalGradient(PAD, 0f, fade, 2f, 0f, alpha(left, 0f), fadeLeft)
        nvg.horizontalGradient(PAD + fade, 0f, length - fade * 2, 2f, 0f, fadeLeft, fadeRight)
        nvg.horizontalGradient(PAD + length - fade, 0f, fade, 2f, 0f, fadeRight, alpha(right, 0f))
    }

    @JvmStatic internal fun header(nvg: NVGRenderer, width: Float, title: String, meta: String) {
        accent(nvg, width, Theme.HUD_ACCENT, Theme.HUD_ACCENT)
        val metaWidth = nvg.textWidth(Fonts.MONO, meta, 9f)
        text(nvg, Fonts.BOLD, title, PAD, 12f, width - PAD * 2 - metaWidth - 10f, 12f, Theme.HUD_TITLE)
        nvg.textRight(Fonts.MONO, meta, PAD, 14f, width - PAD * 2, 9f, Theme.HUD_LABEL)
        nvg.rect(PAD, 31f, width - PAD * 2, 0.7f, Theme.HUD_SEP)
    }

    @JvmStatic internal fun row(nvg: NVGRenderer, x: Float, y: Float, width: Float, label: String, value: String, size: Float, valueColor: Int) {
        val fittedValue = fit(nvg, Fonts.MONO, value, size, width * 0.57f)
        val valueWidth = nvg.textWidth(Fonts.MONO, fittedValue, size)
        text(nvg, Fonts.REGULAR, label, x, y, width - valueWidth - 10f, size, Theme.HUD_LABEL)
        nvg.textRight(Fonts.MONO, fittedValue, x, y, width, size, valueColor)
    }

    @JvmStatic
    fun text(nvg: NVGRenderer, font: String, value: String, x: Float, y: Float, width: Float, size: Float, color: Int) {
        if (width <= 0f) return
        nvg.text(font, fit(nvg, font, value, size, width), x, y, size, color)
    }

    @JvmStatic internal fun fit(nvg: NVGRenderer, font: String, value: String, size: Float, width: Float): String {
        val localized = AetherLang.localize(value)
        if (width <= 0f) return ""
        if (nvg.textWidth(font, localized, size) <= width) return localized
        val suffix = "..."
        if (nvg.textWidth(font, suffix, size) > width) return ""
        val count = localized.codePointCount(0, localized.length)
        var low = 0
        var high = count
        while (low < high) {
            val mid = (low + high + 1) / 2
            val candidate = localized.substring(0, localized.offsetByCodePoints(0, mid)) + suffix
            if (nvg.textWidth(font, candidate, size) <= width) low = mid else high = mid - 1
        }
        return localized.substring(0, localized.offsetByCodePoints(0, low)).trimEnd() + suffix
    }

    @JvmStatic internal fun progress(nvg: NVGRenderer, x: Float, y: Float, width: Float, height: Float, progress: Float) {
        nvg.roundedRect(x, y, width, height, height / 2, Theme.HUD_BAR_BG)
        val filled = width * progress.coerceIn(0f, 1f)
        if (filled > 0f) nvg.roundedRect(x, y, filled, height, height / 2, Theme.HUD_ACCENT)
    }

    @JvmStatic fun stateColor(state: MacroState.State): Int = when (state) {
        MacroState.State.OFF -> Theme.HUD_LABEL
        MacroState.State.FARMING -> Theme.HUD_SUCCESS
        MacroState.State.RECOVERING -> Theme.HUD_ERROR
        MacroState.State.CLEANING, MacroState.State.DROPPING_JUNK -> Theme.HUD_WARNING
        else -> Theme.HUD_ACCENT
    }

    @JvmStatic fun bpsColor(bps: Double): Int = when {
        bps >= 18.5 -> Theme.HUD_SUCCESS
        bps >= 16 -> Theme.blend(Theme.HUD_WARNING, Theme.HUD_SUCCESS, ((bps - 16) / 2.5).toFloat())
        else -> Theme.blend(Theme.HUD_ERROR, Theme.HUD_WARNING, (Math.max(0.0, bps) / 16).toFloat())
    }
    }
}
