package dev.aether.ui.components

import dev.aether.ui.util.Colors
import dev.aether.ui.util.Fonts
import dev.aether.renderer.NVGRenderer
import java.util.ArrayList
import java.util.function.Consumer

// expands its options on click, collapses on select or on a click outside
class Dropdown<T>(options: List<T>, initialIndex: Int) : Component() {
    private val options = ArrayList(options)
    private var selectedIndex = Math.max(0, Math.min(initialIndex, options.size - 1))
    private var expanded = false
    private var onSelect: Consumer<T>? = null
    init { height = ITEM_H }
    override fun render(nvg: NVGRenderer) {
        if (!visible) return
        val borderColor = if (expanded) Colors.BORDER_ACT else Colors.BORDER
        val headerColor = if (expanded) Colors.ACTIVE else Colors.FIELD
        nvg.roundedRect(x, y, width, ITEM_H, RADIUS, headerColor)
        nvg.rectOutline(x, y, width, ITEM_H, RADIUS, 1f, borderColor)
        val selectedLabel = if (options.isEmpty()) "" else options[selectedIndex].toString()
        nvg.text(Fonts.REGULAR, selectedLabel, x + PAD_X, y + (ITEM_H - FONT_SIZE) / 2f, FONT_SIZE, Colors.TEXT)
        drawChevron(nvg, x + width - 14f, y + ITEM_H / 2f, expanded)
        if (expanded && options.isNotEmpty()) {
            val listY = y + ITEM_H + 2f
            val listH = options.size * ITEM_H
            nvg.shadow(x, listY, width, listH, RADIUS, 12f, Colors.withAlpha(Colors.BLACK, 0.5f))
            nvg.roundedRect(x, listY, width, listH, RADIUS, Colors.SURFACE)
            nvg.rectOutline(x, listY, width, listH, RADIUS, 1f, Colors.BORDER)
            for (i in options.indices) {
                val iy = listY + i * ITEM_H
                if (i == selectedIndex) nvg.rect(x + 1, iy, width - 2, ITEM_H, Colors.ACCENT_DIM)
                val textColor = if (i == selectedIndex) Colors.ACCENT else Colors.TEXT
                nvg.text(Fonts.REGULAR, options[i].toString(), x + PAD_X, iy + (ITEM_H - FONT_SIZE) / 2f, FONT_SIZE, textColor)
            }
        }
    }
    override fun mousePressed(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != 0) return false
        if (mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + ITEM_H) { expanded = !expanded; return true }
        if (expanded) {
            val listY = y + ITEM_H + 2f
            if (mouseX >= x && mouseX < x + width) for (i in options.indices) {
                val iy = listY + i * ITEM_H
                if (mouseY >= iy && mouseY < iy + ITEM_H) { select(i); expanded = false; return true }
            }
            expanded = false
            return false
        }
        return false
    }
    fun closeIfOutside(mouseX: Double, mouseY: Double) {
        if (expanded) {
            val listH = options.size * ITEM_H
            val insideHeader = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + ITEM_H
            val insideList = mouseX >= x && mouseX < x + width && mouseY >= y + ITEM_H + 2f && mouseY < y + ITEM_H + 2f + listH
            if (!insideHeader && !insideList) expanded = false
        }
    }
    fun getSelected(): T? = if (options.isEmpty()) null else options[selectedIndex]
    fun getSelectedIndex(): Int = selectedIndex
    fun setOnSelect(handler: Consumer<T>?): Dropdown<T> { onSelect = handler; return this }
    private fun select(index: Int) { selectedIndex = index; if (onSelect != null && options.isNotEmpty()) onSelect?.accept(options[index]) }
    fun isExpanded(): Boolean = expanded
    companion object {
        private const val ITEM_H = 28f
        private const val FONT_SIZE = 13f
        private const val PAD_X = 10f
        private const val RADIUS = 6f
        private fun drawChevron(nvg: NVGRenderer, cx: Float, cy: Float, up: Boolean) {
            val hs = 4f
            nvg.line(cx - hs, cy + if (up) hs else -hs, cx, cy - if (up) hs else -hs, 1.5f, Colors.TEXT_DIM)
            nvg.line(cx, cy - if (up) hs else -hs, cx + hs, cy + if (up) hs else -hs, 1.5f, Colors.TEXT_DIM)
        }
    }
}
