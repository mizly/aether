package dev.aether.ui

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent

class MainGUIKeyboardController(private val owner: MainGUI) {
    fun keyPressed(input: KeyEvent): Boolean {
        val key = input.key()
        val modifiers = input.modifiers()
        if (owner.handleKeybindCapture(input)) return true
        if (key == InputConstants.KEY_ESCAPE) return owner.handleEscapeKey()
        val ctrl = (modifiers and InputConstants.MOD_CONTROL) != 0
        val shift = (modifiers and InputConstants.MOD_SHIFT) != 0
        if (ctrl && (key == InputConstants.KEY_A || key == InputConstants.KEY_C || key == InputConstants.KEY_V) && owner.handleInlineControlShortcut(key)) return true
        if (owner.handleSearchEditorKey(key, ctrl, shift)) return true
        if (owner.handleProfileNameKey(key)) return true
        if (owner.handleInlineEditorKey(key, ctrl, shift)) return true
        return owner.handleOverlayHexKey(key)
    }

    fun charTyped(input: CharacterEvent): Boolean {
        if (owner.isAnyKeybindCaptureActive()) return true
        val ch = input.codepoint().toChar()
        if (owner.handleInlineCharTyped(ch)) return true
        return owner.handleOverlayHexChar(ch)
    }
}
