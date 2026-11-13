package dev.aether.ui.components.tabs

import dev.aether.ui.util.Colors
import dev.aether.ui.util.Fonts
import dev.aether.ui.components.Component
import dev.aether.renderer.NVGRenderer
import java.util.ArrayList

// top-level tab bar with an animated accent underline; the active tab's content draws below it
class TabBar(x: Float, y: Float, width: Float, height: Float) : Component() {
    private val tabs = ArrayList<Tab>()
    private var selectedIndex = 0
    private var indX = 0f
    private var indW = 0f
    private var firstFrame = true
    init { this.x = x; this.y = y; this.width = width; this.height = height }
    fun addTab(tab: Tab): TabBar { tabs.add(tab); return this }
    override fun render(nvg: NVGRenderer) {
        if (!visible || tabs.isEmpty()) return
        nvg.rect(x, y, width, TAB_H, Colors.SURFACE)
        nvg.line(x, y + TAB_H, x + width, y + TAB_H, 1f, Colors.BORDER)
        val tabW = width / tabs.size
        val targetX = x + selectedIndex * tabW
        val targetW = tabW
        if (firstFrame) { indX = targetX; indW = targetW; firstFrame = false }
        else { indX += (targetX - indX) * ANIM_SPEED * 4f; indW += (targetW - indW) * ANIM_SPEED * 4f }
        for (i in tabs.indices) {
            val active = i == selectedIndex
            val tx = x + i * tabW
            val color = if (active) Colors.TEXT else Colors.TEXT_DIM
            val label = tabs[i].getLabel()
            val font = if (active) Fonts.BOLD else Fonts.REGULAR
            val lx = tx + (tabW - nvg.textWidth(font, label, FONT_SIZE)) / 2f
            val ly = y + (TAB_H - FONT_SIZE) / 2f
            nvg.text(font, label, lx, ly, FONT_SIZE, color)
        }
        nvg.rect(indX, y + TAB_H - 2f, indW, 2f, Colors.ACCENT)
        val contentY = y + TAB_H + 1f
        val contentH = height - TAB_H - 1f
        tabs[selectedIndex].renderContent(nvg, x, contentY, width, contentH)
    }
    override fun mousePressed(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (!visible || !enabled || button != 0) return false
        if (mouseY >= y && mouseY < y + TAB_H) {
            val tabW = width / tabs.size
            for (i in tabs.indices) {
                val tx = x + i * tabW
                if (mouseX >= tx && mouseX < tx + tabW) { selectedIndex = i; return true }
            }
        }
        return if (tabs.isEmpty()) false else tabs[selectedIndex].mousePressed(mouseX, mouseY, button)
    }
    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean { if (!visible || !enabled || tabs.isEmpty()) return false; return tabs[selectedIndex].mouseReleased(mouseX, mouseY, button) }
    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, deltaX: Double, deltaY: Double): Boolean { if (!visible || !enabled || tabs.isEmpty()) return false; return tabs[selectedIndex].mouseDragged(mouseX, mouseY, button, deltaX, deltaY) }
    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean { if (!visible || !enabled || tabs.isEmpty()) return false; return tabs[selectedIndex].mouseScrolled(mouseX, mouseY, scrollX, scrollY) }
    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean { if (!visible || !enabled || tabs.isEmpty()) return false; return tabs[selectedIndex].keyPressed(keyCode, scanCode, modifiers) }
    override fun charTyped(codePoint: Char, modifiers: Int): Boolean { if (!visible || !enabled || tabs.isEmpty()) return false; return tabs[selectedIndex].charTyped(codePoint, modifiers) }
    fun getActiveTab(): Tab? = if (tabs.isEmpty()) null else tabs[selectedIndex]
    fun getSelectedIndex(): Int = selectedIndex
    fun setSelectedIndex(i: Int) { selectedIndex = Math.max(0, Math.min(i, tabs.size - 1)) }
    fun getTabs(): MutableList<Tab> = tabs
    fun getTabBarHeight(): Float = TAB_H
    companion object {
        private const val TAB_H = 38f
        private const val FONT_SIZE = 14f
        private const val ANIM_SPEED = 0.2f
    }
}
