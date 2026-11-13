package dev.aether.ui.components

import dev.aether.renderer.NVGRenderer

// positioned in screen space, drawn only through NVGRenderer; the parent NVGScreen forwards mouse and key events
abstract class Component {
    @JvmField protected var x: Float = 0f
    @JvmField protected var y: Float = 0f
    @JvmField protected var width: Float = 0f
    @JvmField protected var height: Float = 0f

    protected var visible: Boolean = true
    protected var enabled: Boolean = true

    abstract fun render(nvg: NVGRenderer)

    open fun mousePressed(mouseX: Double, mouseY: Double, button: Int): Boolean = false
    open fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean = false
    open fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, deltaX: Double, deltaY: Double): Boolean = false
    open fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean = false
    open fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean = false
    open fun charTyped(codePoint: Char, modifiers: Int): Boolean = false

    fun contains(px: Double, py: Double): Boolean = px >= x && px < x + width && py >= y && py < y + height

    fun setPosition(x: Float, y: Float): Component {
        this.x = x
        this.y = y
        return this
    }

    fun setSize(width: Float, height: Float): Component {
        this.width = width
        this.height = height
        return this
    }

    fun setBounds(x: Float, y: Float, width: Float, height: Float): Component {
        this.x = x
        this.y = y
        this.width = width
        this.height = height
        return this
    }

    fun getX(): Float = x
    fun getY(): Float = y
    fun getWidth(): Float = width
    fun getHeight(): Float = height
    fun isVisible(): Boolean = visible
    fun isEnabled(): Boolean = enabled
    fun setVisible(visible: Boolean): Component { this.visible = visible; return this }
    fun setEnabled(enabled: Boolean): Component { this.enabled = enabled; return this }
}
