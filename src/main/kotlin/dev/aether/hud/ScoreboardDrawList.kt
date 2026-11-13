package dev.aether.hud

import dev.aether.renderer.NVGRenderer
import dev.aether.ui.theme.Theme

internal class ScoreboardDrawList {
    private enum class Alignment { LEFT, CENTER, RIGHT }

    private data class Line(
        val text: ScoreboardText?,
        val x: Int,
        val y: Int,
        val vanillaWidth: Int,
        val alignment: Alignment,
    )

    private val lines = ArrayList<Line>()
    private val rowYs = ArrayList<Int>()
    private val headingYs = ArrayList<Int>()
    private var minimumWidth = 0
    private var left = Int.MAX_VALUE
    private var top = Int.MAX_VALUE
    private var right = Int.MIN_VALUE
    private var bottom = Int.MIN_VALUE

    fun fill(x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
        val x = minOf(x1, x2)
        val y = minOf(y1, y2)
        val r = maxOf(x1, x2)
        val b = maxOf(y1, y2)
        left = minOf(left, x)
        top = minOf(top, y)
        right = maxOf(right, r)
        bottom = maxOf(bottom, b)
    }

    fun text(text: ScoreboardText?, x: Int, y: Int, vanillaWidth: Int) =
        text(text, x, y, vanillaWidth, 0)

    fun text(text: ScoreboardText?, x: Int, y: Int, vanillaWidth: Int, replacementWidth: Int) {
        val alignment = if (lines.isEmpty()) Alignment.CENTER else if (x == left + 2) Alignment.LEFT else Alignment.RIGHT
        lines.add(Line(text, x, y, vanillaWidth, alignment))
        if (y !in rowYs) rowYs.add(y)
        if (text != null && text.isHeading() && y !in headingYs) headingYs.add(y)
        if (replacementWidth > 0) minimumWidth = maxOf(minimumWidth, replacementWidth + 4)
    }

    fun hasText(): Boolean = lines.isNotEmpty()
    fun isEmpty(): Boolean = left == Int.MAX_VALUE
    fun left(): Int = if (isEmpty()) 0 else left
    fun top(): Int = if (isEmpty()) 0 else top
    fun width(): Int = if (isEmpty()) 0 else right - left
    fun height(): Int = if (isEmpty()) 0 else bottom - top
    private fun contentWidth(): Int = maxOf(width(), minimumWidth)
    fun panelWidth(): Float = if (isEmpty()) 0f else contentWidth() + PADDING * 2
    fun panelHeight(): Float = if (isEmpty()) 0f else height() + maxOf(0, rowYs.size - 1) * EXTRA_LINE_SPACING +
        headingYs.size * (ScoreboardText.HEADING_SIZE - ScoreboardText.SIZE) + PADDING * 2

    fun lineY(vanillaY: Int): Float = vanillaY + rowYs.count { it < vanillaY } * EXTRA_LINE_SPACING +
        headingYs.count { it < vanillaY } * (ScoreboardText.HEADING_SIZE - ScoreboardText.SIZE)

    fun render(nvg: NVGRenderer) {
        if (isEmpty()) return
        HudStyle.panel(nvg, panelWidth(), panelHeight())
        HudStyle.accent(nvg, panelWidth(), Theme.HUD_ACCENT, Theme.HUD_ACCENT)
        nvg.save()
        try {
            nvg.translate(PADDING - left(), PADDING - top())
            val addedWidth = contentWidth() - width()
            for ((i, line) in lines.withIndex()) {
                val title = line.alignment == Alignment.CENTER
                var available = if (title) contentWidth() - 4 else if (line.alignment == Alignment.RIGHT)
                    line.vanillaWidth else right + addedWidth - line.x
                if (line.alignment == Alignment.LEFT && i + 1 < lines.size) {
                    val score = lines[i + 1]
                    if (score.y == line.y && score.vanillaWidth > 0) available = score.x + addedWidth - line.x - 2
                }
                val text = line.text ?: continue
                val textWidth = minOf(available.toFloat(), text.width(nvg, title))
                val x = when (line.alignment) {
                    Alignment.LEFT -> line.x.toFloat()
                    Alignment.CENTER -> left + contentWidth() / 2f - textWidth / 2f
                    Alignment.RIGHT -> line.x + line.vanillaWidth + addedWidth - textWidth
                }
                text.render(nvg, x, lineY(line.y), available.toFloat(), title)
            }
        } finally {
            nvg.restore()
        }
    }

    companion object {
        const val PADDING = 10f
        private const val EXTRA_LINE_SPACING = 1f
    }
}
