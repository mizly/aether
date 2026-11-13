package dev.aether.ui.components.tabs

import dev.aether.ui.util.Colors
import dev.aether.ui.util.Fonts
import dev.aether.renderer.NVGRenderer

// row of sub-tab labels with an animated underline
class SubTabBar(private val tabs: List<SubTab>, x: Float, y: Float, width: Float) {
    private var x = x
    private var y = y
    private var width = width
    private var selectedIndex = 0
    private var indicatorX = 0f
    private var indicatorWidth = 0f
    private var firstFrame = true
    fun render(nvg: NVGRenderer) {
        if (tabs.isEmpty()) return
        nvg.rect(x, y, width, HEIGHT, Colors.SURFACE)
        nvg.line(x, y + HEIGHT - 1f, x + width, y + HEIGHT - 1f, 1f, Colors.BORDER)
        val tabW = width / tabs.size
        val targetX = x + selectedIndex * tabW
        val targetW = tabW
        if (firstFrame) { indicatorX = targetX; indicatorWidth = targetW; firstFrame = false }
        else {
            indicatorX += (targetX - indicatorX) * ANIM_SPEED * 4f
            indicatorWidth += (targetW - indicatorWidth) * ANIM_SPEED * 4f
        }
        for (i in tabs.indices) {
            val tx = x + i * tabW
            val active = i == selectedIndex
            val textColor = if (active) Colors.ACCENT else Colors.TEXT_DIM
            val label = tabs[i].getLabel()
            val labelX = tx + (tabW - nvg.textWidth(Fonts.REGULAR, label, FONT_SIZE)) / 2f
            val labelY = y + (HEIGHT - FONT_SIZE) / 2f
            nvg.text(Fonts.REGULAR, label, labelX, labelY, FONT_SIZE, textColor)
        }
        nvg.rect(indicatorX, y + HEIGHT - 2f, indicatorWidth, 2f, Colors.ACCENT)
    }
    fun mousePressed(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != 0 || mouseY < y || mouseY >= y + HEIGHT) return false
        val tabW = width / tabs.size
        for (i in tabs.indices) {
            val tx = x + i * tabW
            if (mouseX >= tx && mouseX < tx + tabW) { selectedIndex = i; return true }
        }
        return false
    }
    fun mouseReleased(mouseX: Double, mouseY: Double, button: Int) {}
    fun setBounds(x: Float, y: Float, width: Float, height: Float) { this.x = x; this.y = y; this.width = width }
    fun getActiveSubTab(): SubTab? = if (tabs.isEmpty()) null else tabs[selectedIndex]
    fun getSelectedIndex(): Int = selectedIndex
    fun setSelectedIndex(i: Int) { selectedIndex = i }
    fun getHeight(): Float = HEIGHT
    companion object {
        private const val HEIGHT = 34f
        private const val FONT_SIZE = 13f
        private const val ANIM_SPEED = 0.18f
    }
}
