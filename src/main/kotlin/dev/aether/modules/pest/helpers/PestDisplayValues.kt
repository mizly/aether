package dev.aether.modules.pest.helpers

import net.minecraft.network.chat.Component
import java.util.Locale
import java.util.regex.Pattern

object PestDisplayValues {
    private const val NUMBER = "([\\d,]+(?:\\.\\d+)?[kKmMbB]?)"
    private val HEALTH = Pattern.compile(NUMBER + "(?:\\s*/\\s*" + NUMBER + ")?\\s*[❤♥]")
    private val PERCENT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*%")
    private val STAMINA = Pattern.compile("(?i)(?:stamina|progress)\\s*:?\\s*" + NUMBER + "\\s*/\\s*" + NUMBER)

    @JvmStatic
    fun health(name: String): Health? {
        val match = HEALTH.matcher(PestHuntingController.stripFormatting(name))
        if (!match.find()) return null
        val current = number(match.group(1))
        val maximum = if (match.group(2) == null) 0.0 else number(match.group(2))
        return Health(current, maximum)
    }

    // This is progress through the current stamina bar, not a guessed total catch duration.
    @JvmStatic
    fun huntingProgress(label: Component): Float {
        val plain = PestHuntingController.stripFormatting(label.string)
        if (PestHuntingController.isReelPrompt(plain)) return 1f
        if (health(plain) != null) return -1f
        val percent = PERCENT.matcher(plain)
        if (percent.find()) {
            val value = percent.group(1).toFloat() / 100f
            return clamp(if (plain.lowercase(Locale.ROOT).contains("stamina")) 1f - value else value)
        }
        val stamina = STAMINA.matcher(plain)
        if (stamina.find()) {
            val maximum = number(stamina.group(2))
            if (maximum <= 0.0) return -1f
            val value = (number(stamina.group(1)) / maximum).toFloat()
            return clamp(if (plain.lowercase(Locale.ROOT).contains("stamina")) 1f - value else value)
        }
        val segments = IntArray(2)
        label.visualOrderText.accept { _, style, codepoint ->
            val color = style.color
            if (color == null) return@accept true
            if (!isBarGlyph(codepoint) && !style.isBold) return@accept true
            val rgb = color.value and 0xFFFFFF
            segments[1]++
            if (rgb != 0x555555 && rgb != 0xAAAAAA && rgb != 0x000000) segments[0]++
            true
        }
        return if (segments[1] >= 4) 1f - segments[0].toFloat() / segments[1] else -1f
    }

    private fun clamp(value: Float): Float = value.coerceIn(0f, 1f)

    private fun number(text: String): Double {
        val value = text.replace(",", "").lowercase(Locale.ROOT)
        val suffix = value[value.length - 1]
        val multiplier = when (suffix) {
            'k' -> 1_000.0
            'm' -> 1_000_000.0
            'b' -> 1_000_000_000.0
            else -> 1.0
        }
        return (if (multiplier == 1.0) value else value.substring(0, value.length - 1)).toDouble() * multiplier
    }

    data class Health(val current: Double, val maximum: Double)

    private const val BAR_GLYPHS = "|▏▎▍▌▋▊▉█▮■▬"
    private fun isBarGlyph(codepoint: Int): Boolean = BAR_GLYPHS.indexOf(codepoint.toChar()) >= 0
}
