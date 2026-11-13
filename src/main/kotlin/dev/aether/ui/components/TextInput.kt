package dev.aether.ui.components

import com.mojang.blaze3d.platform.InputConstants
import dev.aether.ui.util.Colors
import dev.aether.ui.util.Fonts
import dev.aether.renderer.NVGRenderer
import java.util.function.Consumer

// handles printable characters, backspace, delete, home, end, arrows, and ctrl+a as select-all-then-clear
class TextInput(placeholder: String) : Component() {
    private val text = StringBuilder()
    private var cursor = 0
    private var focused = false
    private var blinkStart = System.currentTimeMillis()
    private var placeholder: String = placeholder
    private var onChange: Consumer<String>? = null

    init { height = 28f }
    override fun render(nvg: NVGRenderer) {
        if (!visible) return
        val borderColor = if (focused) Colors.ACCENT else Colors.BORDER
        nvg.roundedRect(x, y, width, height, RADIUS, Colors.FIELD)
        nvg.rectOutline(x, y, width, height, RADIUS, 1f, borderColor)
        val textY = y + (height - FONT_SIZE) / 2f
        if (text.length == 0 && !focused) {
            nvg.text(Fonts.REGULAR, placeholder, x + PAD_X, textY, FONT_SIZE, Colors.TEXT_DIM)
        } else {
            nvg.pushScissor(x + 1, y + 1, width - 2, height - 2)
            val display = text.toString()
            nvg.text(Fonts.REGULAR, display, x + PAD_X, textY, FONT_SIZE, Colors.TEXT)
            if (focused) {
                val elapsed = (System.currentTimeMillis() - blinkStart) % (BLINK_MS * 2)
                if (elapsed < BLINK_MS) {
                    val beforeCursor = display.substring(0, Math.min(cursor, display.length))
                    val cursorX = x + PAD_X + nvg.textWidth(Fonts.REGULAR, beforeCursor, FONT_SIZE)
                    nvg.line(cursorX, textY + 1f, cursorX, textY + FONT_SIZE - 1f, 1.5f, Colors.TEXT)
                }
            }
            nvg.popScissor()
        }
    }
    override fun mousePressed(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0) { val wasHere = contains(mouseX, mouseY); setFocused(wasHere); return wasHere }
        return false
    }
    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (!focused) return false
        val ctrl = modifiers and InputConstants.MOD_CONTROL != 0
        when (keyCode) {
            InputConstants.KEY_BACKSPACE -> {
                if (ctrl) {
                    while (cursor > 0 && text[cursor - 1] == ' ') cursor--
                    while (cursor > 0 && text[cursor - 1] != ' ') text.deleteCharAt(--cursor)
                } else if (cursor > 0) text.deleteCharAt(--cursor)
                notifyChange(); return true
            }
            InputConstants.KEY_DELETE -> { if (cursor < text.length) { text.deleteCharAt(cursor); notifyChange() }; return true }
            InputConstants.KEY_LEFT -> { if (cursor > 0) cursor--; resetBlink(); return true }
            InputConstants.KEY_RIGHT -> { if (cursor < text.length) cursor++; resetBlink(); return true }
            InputConstants.KEY_HOME -> { cursor = 0; resetBlink(); return true }
            InputConstants.KEY_END -> { cursor = text.length; resetBlink(); return true }
            InputConstants.KEY_A -> if (ctrl) { text.setLength(0); cursor = 0; notifyChange(); return true }
            InputConstants.KEY_ESCAPE -> { setFocused(false); return true }
        }
        return false
    }
    override fun charTyped(codePoint: Char, modifiers: Int): Boolean {
        if (!focused) return false
        text.insert(cursor++, codePoint); notifyChange(); return true
    }
    fun getText(): String = text.toString()
    fun setText(s: String): TextInput { text.setLength(0); text.append(s); cursor = text.length; return this }
    fun setOnChange(handler: Consumer<String>?): TextInput { onChange = handler; return this }
    fun setPlaceholder(ph: String): TextInput { placeholder = ph; return this }
    fun isFocused(): Boolean = focused
    fun setFocused(focused: Boolean) { this.focused = focused; if (focused) resetBlink() }
    private fun notifyChange() { resetBlink(); onChange?.accept(text.toString()) }
    private fun resetBlink() { blinkStart = System.currentTimeMillis() }

    companion object {
        private const val PAD_X = 10f
        private const val FONT_SIZE = 13f
        private const val RADIUS = 6f
        private const val BLINK_MS = 530L
    }
}
