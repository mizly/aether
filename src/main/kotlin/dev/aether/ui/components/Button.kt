package dev.aether.ui.components

import dev.aether.ui.util.Colors
import dev.aether.ui.util.Fonts
import dev.aether.renderer.NVGRenderer

// rounded button with lerped hover and press transitions
class Button private constructor(label: String, width: Float, height: Float, accent: Boolean, onPress: Runnable?) : Component() {
    private var hoverAmt = 0f
    private var pressAmt = 0f
    private var label: String = label
    private var accent: Boolean = accent
    private var onPress: Runnable? = onPress
    private var hovered = false
    private var pressed = false

    init { this.width = width; this.height = height }

    class Builder internal constructor(private val label: String, private val width: Float, private val height: Float) {
        private var accent = false
        private var onPress: Runnable? = Runnable {}
        fun accent(accent: Boolean): Builder { this.accent = accent; return this }
        fun onPress(onPress: Runnable): Builder { this.onPress = onPress; return this }
        fun build(): Button = Button(label, width, height, accent, onPress)
    }

    override fun render(nvg: NVGRenderer) {
        if (!visible) return
        val hoverTarget = if (hovered) 1f else 0f
        val pressTarget = if (pressed) 1f else 0f
        hoverAmt += (hoverTarget - hoverAmt) * ANIM_SPEED
        pressAmt += (pressTarget - pressAmt) * ANIM_SPEED
        var bg: Int
        if (accent) {
            bg = Colors.lerp(Colors.ACCENT, Colors.ACCENT_DARK, pressAmt)
            bg = Colors.lerp(bg, Colors.lerp(Colors.ACCENT, Colors.withAlpha(Colors.WHITE, 30), 0.15f), hoverAmt)
        } else {
            bg = Colors.lerp(Colors.SURFACE, Colors.HOVER, hoverAmt)
            bg = Colors.lerp(bg, Colors.ACTIVE, pressAmt)
        }
        val border = Colors.lerp(Colors.BORDER, if (accent) Colors.ACCENT_DARK else Colors.BORDER_HOV, hoverAmt)
        val text = if (enabled) Colors.TEXT else Colors.TEXT_OFF
        nvg.roundedRect(x, y, width, height, RADIUS, bg)
        nvg.rectOutline(x, y, width, height, RADIUS, 1f, border)
        nvg.text(Fonts.REGULAR, label, x + (width - nvg.textWidth(Fonts.REGULAR, label, FONT_SIZE)) / 2f, y + (height - FONT_SIZE) / 2f, FONT_SIZE, text)
    }

    override fun mousePressed(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0 && enabled && contains(mouseX, mouseY)) { pressed = true; return true }
        return false
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0 && pressed) {
            pressed = false
            if (enabled && contains(mouseX, mouseY)) onPress?.run()
            return true
        }
        return false
    }

    fun updateHover(mouseX: Double, mouseY: Double) { hovered = enabled && contains(mouseX, mouseY) }
    fun setLabel(label: String): Button { this.label = label; return this }
    fun setAccent(accent: Boolean): Button { this.accent = accent; return this }
    fun setOnPress(handler: Runnable?): Button { this.onPress = handler; return this }

    companion object {
        private const val ANIM_SPEED = 0.15f
        private const val RADIUS = 6f
        private const val FONT_SIZE = 13f
        @JvmStatic fun builder(label: String, width: Float, height: Float): Builder = Builder(label, width, height)
    }
}
