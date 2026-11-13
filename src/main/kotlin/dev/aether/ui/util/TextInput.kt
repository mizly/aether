package dev.aether.ui.util

import dev.aether.renderer.NVGRenderer
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft

// no vanilla widgets - handles its own keyboard input and rendering
class TextInput {
    private var value: String = ""
    private var cursor = 0
    private var maxLength = 256
    private var focused = false

    // -- Config ----------------------------------------------------------------
    fun setValue(v: String?) {
        value = v ?: ""
        cursor = value.length
    }

    fun getValue(): String = value
    fun setMaxLength(max: Int) { maxLength = max }
    fun isFocused(): Boolean = focused
    fun setFocused(f: Boolean) { focused = f }

    // -- Input -----------------------------------------------------------------
    fun charTyped(ch: Char): Boolean {
        if (!focused) return false
        // Ignore control characters
        if (ch.code < 32 || ch.code == 127) return true
        if (value.length >= maxLength) return true
        value = value.substring(0, cursor) + ch + value.substring(cursor)
        cursor++
        return true
    }

    fun keyPressed(key: Int, mods: Int): Boolean {
        if (!focused) return false
        val ctrl = (mods and InputConstants.MOD_CONTROL) != 0

        when (key) {
            InputConstants.KEY_BACKSPACE -> {
                if (cursor > 0) {
                    if (ctrl) {
                        // Delete word
                        val start = wordStart(cursor - 1)
                        value = value.substring(0, start) + value.substring(cursor)
                        cursor = start
                    } else {
                        value = value.substring(0, cursor - 1) + value.substring(cursor)
                        cursor--
                    }
                }
                return true
            }
            InputConstants.KEY_DELETE -> {
                if (cursor < value.length) {
                    if (ctrl) {
                        val end = wordEnd(cursor)
                        value = value.substring(0, cursor) + value.substring(end)
                    } else {
                        value = value.substring(0, cursor) + value.substring(cursor + 1)
                    }
                }
                return true
            }
            InputConstants.KEY_LEFT -> {
                if (cursor > 0) cursor = if (ctrl) wordStart(cursor - 1) else cursor - 1
                return true
            }
            InputConstants.KEY_RIGHT -> {
                if (cursor < value.length) cursor = if (ctrl) wordEnd(cursor) else cursor + 1
                return true
            }
            InputConstants.KEY_HOME -> { cursor = 0; return true }
            InputConstants.KEY_END -> { cursor = value.length; return true }
            InputConstants.KEY_A -> if (ctrl) { cursor = value.length; return true }
            InputConstants.KEY_C -> if (ctrl) { Minecraft.getInstance().keyboardHandler.setClipboard(value); return true }
            InputConstants.KEY_X -> if (ctrl) {
                Minecraft.getInstance().keyboardHandler.setClipboard(value)
                value = ""; cursor = 0
                return true
            }
            InputConstants.KEY_V -> if (ctrl) {
                var clip = Minecraft.getInstance().keyboardHandler.getClipboard()
                if (clip != null && clip.isNotEmpty()) {
                    // strip newlines, trim to max
                    clip = clip.replace("\n", "").replace("\r", "")
                    val room = maxLength - value.length
                    if (room > 0) {
                        clip = clip.substring(0, Math.min(clip.length, room))
                        value = value.substring(0, cursor) + clip + value.substring(cursor)
                        cursor += clip.length
                    }
                }
                return true
            }
        }
        return false
    }

    // -- Render ----------------------------------------------------------------
    // the caller draws the field background behind this
    fun render(nvg: NVGRenderer, x: Float, y: Float, w: Float, h: Float, fontSize: Float, textColor: Int) {
        val padX = 8f
        val textX = x + padX
        val textY = y + (h - fontSize) / 2f
        val clipW = w - padX * 2f

        nvg.save()
        nvg.scissor(x, y, w, h)

        // Measure cursor offset to determine scroll
        val beforeCursor = value.substring(0, cursor)
        val cursorPx = nvg.textWidth(Fonts.REGULAR, beforeCursor, fontSize)
        val totalPx = nvg.textWidth(Fonts.REGULAR, value, fontSize)

        // Scroll so cursor stays visible
        var scroll = 0f
        if (cursorPx > clipW) scroll = cursorPx - clipW + 4f

        nvg.text(Fonts.REGULAR, value, textX - scroll, textY, fontSize, textColor)

        // Blinking cursor when focused
        if (focused && (System.currentTimeMillis() / 530) % 2 == 0L) {
            val cx = textX - scroll + cursorPx
            nvg.rect(cx, textY + 1f, 1f, fontSize - 2f, textColor)
        }

        nvg.resetScissor()
        nvg.restore()
    }

    // -- Word navigation helpers -----------------------------------------------
    private fun wordStart(pos: Int): Int {
        var index = pos
        while (index > 0 && value[index - 1] != ' ') index--
        return index
    }

    private fun wordEnd(pos: Int): Int {
        var index = pos
        while (index < value.length && value[index] != ' ') index++
        return index
    }
}
