package dev.aether.hud

import dev.aether.renderer.NVGRenderer
import dev.aether.ui.theme.Theme
import dev.aether.ui.util.Fonts
import net.minecraft.client.gui.Font
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.Style
import net.minecraft.util.FormattedCharSequence
import java.util.ArrayList

internal class ScoreboardText private constructor(parts: List<Part>, private val heading: Boolean) {
    private val parts: List<Part> = java.util.List.copyOf(parts)
    fun isHeading(): Boolean = heading

    fun width(nvg: NVGRenderer, title: Boolean): Float {
        var width = 0f
        for (part in parts) width += part.width(nvg, title)
        return width
    }
    fun render(nvg: NVGRenderer, x: Float, y: Float, maxWidth: Float, title: Boolean) {
        val width = width(nvg, title)
        if (width <= 0 || maxWidth <= 0) return
        nvg.save()
        try {
            nvg.translate(x, y)
            nvg.scale(Math.min(1f, maxWidth / width), 1f)
            var cursor = 0f
            for (part in parts) { part.render(nvg, cursor, title); cursor += part.width(nvg, title) }
        } finally { nvg.restore() }
    }

    class Run(private val textValue: String, private val styleValue: Style, private val nativeGlyphValue: Boolean) {
        fun text(): String = textValue
        fun style(): Style = styleValue
        fun nativeGlyph(): Boolean = nativeGlyphValue
        override fun equals(other: Any?): Boolean = other is Run && textValue == other.textValue && styleValue == other.styleValue && nativeGlyphValue == other.nativeGlyphValue
        override fun hashCode(): Int = java.util.Objects.hash(textValue, styleValue, nativeGlyphValue)
        override fun toString(): String = "Run[text=$textValue, style=$styleValue, nativeGlyph=$nativeGlyphValue]"
    }

    private class Part(private val run: Run, private val color: Int, private val size: Float, private val glyphs: Font.PreparedText?, private val nativeWidth: Float) {
        private fun font(title: Boolean): String = if (run.style().isBold) Fonts.SCOREBOARD_BOLD else Fonts.REGULAR
        fun width(nvg: NVGRenderer, title: Boolean): Float = if (glyphs != null) nativeWidth * size / SIZE else nvg.textWidthLiteral(font(title), run.text(), size)
        fun render(nvg: NVGRenderer, x: Float, title: Boolean) {
            nvg.save()
            try {
                nvg.translate(x, 0f)
                if (glyphs != null) {
                    nvg.scale(size / SIZE, size / SIZE)
                    nvg.minecraftText(glyphs)
                    return
                }
                val tint = textColor(run.style(), color, title)
                if (run.style().isItalic) { nvg.translate(size * 0.2f, 0f); nvg.skewX(-0.2f) }
                nvg.textLiteral(font(title), run.text(), 0f, 0f, size, tint)
                val width = width(nvg, title)
                if (run.style().isUnderlined) nvg.rect(0f, size - 1, width, 0.6f, tint)
                if (run.style().isStrikethrough) nvg.rect(0f, size * 0.5f, width, 0.6f, tint)
            } finally { nvg.restore() }
        }
    }

    companion object {
        const val SIZE = 9f
        const val HEADING_SIZE = 10f
        @JvmStatic fun customize(original: FormattedCharSequence, title: Boolean, titleText: String, serverText: String): FormattedCharSequence {
            val runs = split(original)
            val replacement = if (title) titleText else if (isServerAddress(original)) serverText else ""
            if (replacement.isBlank()) return original
            val style = if (runs.isEmpty()) Style.EMPTY else runs.first().style()
            return FormattedCharSequence.forward(replacement.replace('\n', ' ').replace('\r', ' '), style)
        }

        @JvmStatic fun prepare(font: Font?, text: FormattedCharSequence, color: Int, shadow: Boolean): ScoreboardText = prepare(font, text, color, shadow, false)
        @JvmStatic fun isServerAddress(text: FormattedCharSequence): Boolean {
            val plain = StringBuilder()
            text.accept { _, _, codePoint -> plain.appendCodePoint(codePoint); true }
            return plain.toString().trim().matches("(?i)(?:www\\.)?hypixel\\.net".toRegex())
        }
        @JvmStatic fun prepare(font: Font?, text: FormattedCharSequence, color: Int, shadow: Boolean, heading: Boolean): ScoreboardText {
            val parts = ArrayList<Part>()
            for (originalRun in split(text)) {
                val run = if (heading) Run(originalRun.text(), originalRun.style().withBold(true), originalRun.nativeGlyph()) else originalRun
                val sequence = FormattedCharSequence.forward(run.text(), run.style())
                parts.add(Part(run, color, if (heading) HEADING_SIZE else SIZE,
                    if (run.nativeGlyph() && font != null) font.prepareText(sequence, 0f, 0f, color, shadow, false, 0) else null,
                    if (run.nativeGlyph() && font != null) font.width(sequence).toFloat() else 0f))
            }
            return ScoreboardText(parts, heading)
        }
        @JvmStatic fun split(text: FormattedCharSequence): List<Run> {
            val runs = ArrayList<Run>()
            val value = StringBuilder()
            var currentStyle = Style.EMPTY
            var nativeGlyph = false
            text.accept { _, style, codePoint ->
                val useNative = usesNativeGlyph(style, codePoint)
                if (value.isNotEmpty() && (style != currentStyle || useNative != nativeGlyph)) {
                    runs.add(Run(value.toString(), currentStyle, nativeGlyph)); value.setLength(0)
                }
                currentStyle = style; nativeGlyph = useNative; value.appendCodePoint(codePoint); true
            }
            if (value.isNotEmpty()) runs.add(Run(value.toString(), currentStyle, nativeGlyph))
            return java.util.List.copyOf(runs)
        }
        private fun usesNativeGlyph(style: Style, codePoint: Int): Boolean {
            if (style.isObfuscated || FontDescription.DEFAULT != style.font) return true
            if (Character.isLetterOrDigit(codePoint) && Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.LATIN && Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.COMMON) return true
            return when (Character.getType(codePoint)) {
                Character.PRIVATE_USE.toInt(), Character.UNASSIGNED.toInt(), Character.OTHER_SYMBOL.toInt(), Character.MATH_SYMBOL.toInt(), Character.MODIFIER_SYMBOL.toInt() -> true
                else -> false
            }
        }
        @JvmStatic fun textColor(style: Style, baseColor: Int, title: Boolean): Int {
            val alpha = baseColor ushr 24
            if (title) return HudStyle.alpha(Theme.HUD_TITLE, alpha / 255f)
            val rgb = if (style.color == null) baseColor and 0xFFFFFF else style.color!!.value
            return when (rgb) {
                0xFFFFFF -> HudStyle.alpha(Theme.HUD_VALUE, alpha / 255f)
                0xAAAAAA, 0x555555 -> HudStyle.alpha(Theme.HUD_LABEL, alpha / 255f)
                else -> alpha shl 24 or rgb
            }
        }
    }
}
