package dev.aether.ui.components

import dev.aether.ui.util.Colors
import dev.aether.ui.util.Fonts
import dev.aether.renderer.NVGRenderer
import java.util.ArrayList

// children are positioned relative to the panel's top-left and clipped to the content area below the title bar
class Panel(title: String, x: Float, y: Float, width: Float, height: Float) : Component() {
    private var title: String = title
    private var dragging = false
    private var dragOffX: Double = 0.0
    private var dragOffY: Double = 0.0
    private val children = ArrayList<Component>()

    init { this.x = x; this.y = y; this.width = width; this.height = height }
    fun <T : Component> add(child: T): T { children.add(child); return child }
    fun remove(child: Component) { children.remove(child) }

    override fun render(nvg: NVGRenderer) {
        if (!visible) return
        nvg.shadow(x, y, width, height, RADIUS, SHADOW_BL, Colors.withAlpha(Colors.BLACK, 0.55f))
        nvg.roundedRect(x, y, width, height, RADIUS, Colors.BG)
        nvg.roundedRect(x, y, width, TITLE_H, RADIUS, Colors.SURFACE)
        nvg.rect(x, y + RADIUS, width, TITLE_H - RADIUS, Colors.SURFACE)
        val titleY = y + (TITLE_H - 14f) / 2f
        nvg.text(Fonts.BOLD, title, x + 14f, titleY, 14f, Colors.TEXT)
        nvg.rectOutline(x, y, width, height, RADIUS, 1f, Colors.BORDER)
        nvg.rect(x + 1, y + TITLE_H - 1f, width - 2f, 1f, Colors.BORDER)
        val contentY = y + TITLE_H
        val contentH = height - TITLE_H
        nvg.pushScissor(x, contentY, width, contentH)
        for (child in children) if (child.isVisible()) child.render(nvg)
        nvg.popScissor()
    }

    override fun mousePressed(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0 && mouseY >= y && mouseY < y + TITLE_H && mouseX >= x && mouseX < x + width) {
            dragging = true; dragOffX = mouseX - x; dragOffY = mouseY - y; return true
        }
        for (i in children.size - 1 downTo 0) {
            val c = children[i]
            if (c.isVisible() && c.isEnabled() && c.contains(mouseX, mouseY) && c.mousePressed(mouseX, mouseY, button)) return true
        }
        return contains(mouseX, mouseY)
    }
    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0 && dragging) { dragging = false; return true }
        for (c in children) if (c.isVisible() && c.isEnabled() && c.mouseReleased(mouseX, mouseY, button)) return true
        return false
    }
    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, deltaX: Double, deltaY: Double): Boolean {
        if (dragging && button == 0) { x = (mouseX - dragOffX).toFloat(); y = (mouseY - dragOffY).toFloat(); return true }
        for (c in children) if (c.isVisible() && c.isEnabled() && c.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)) return true
        return false
    }
    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        for (i in children.size - 1 downTo 0) { val c = children[i]; if (c.isVisible() && c.isEnabled() && c.contains(mouseX, mouseY) && c.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) return true }
        return false
    }
    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        for (i in children.size - 1 downTo 0) { val c = children[i]; if (c.isVisible() && c.isEnabled() && c.keyPressed(keyCode, scanCode, modifiers)) return true }
        return false
    }
    override fun charTyped(codePoint: Char, modifiers: Int): Boolean {
        for (i in children.size - 1 downTo 0) { val c = children[i]; if (c.isVisible() && c.isEnabled() && c.charTyped(codePoint, modifiers)) return true }
        return false
    }
    fun getTitle(): String = title
    fun setTitle(title: String): Panel { this.title = title; return this }
    fun getContentY(): Float = y + TITLE_H
    fun getContentHeight(): Float = height - TITLE_H

    companion object {
        private const val TITLE_H = 32f
        private const val RADIUS = 10f
        private const val SHADOW_BL = 20f
    }
}
