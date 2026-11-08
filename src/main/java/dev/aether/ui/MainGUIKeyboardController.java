package dev.aether.ui;

import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import com.mojang.blaze3d.platform.InputConstants;

final class MainGUIKeyboardController {
    private final MainGUI owner;

    MainGUIKeyboardController(MainGUI owner) {
        this.owner = owner;
    }

    boolean keyPressed(KeyEvent input) {
        int key = input.key();
        int modifiers = input.modifiers();

        if (owner.handleKeybindCapture(input)) {
            return true;
        }

        if (key == InputConstants.KEY_ESCAPE) {
            return owner.handleEscapeKey();
        }

        boolean ctrl = (modifiers & InputConstants.MOD_CONTROL) != 0;
        boolean shift = (modifiers & InputConstants.MOD_SHIFT) != 0;

        if (ctrl && (key == InputConstants.KEY_A || key == InputConstants.KEY_C || key == InputConstants.KEY_V)
                && owner.handleInlineControlShortcut(key)) {
            return true;
        }

        if (owner.handleSearchEditorKey(key, ctrl, shift)) {
            return true;
        }

        if (owner.handleProfileNameKey(key)) {
            return true;
        }

        if (owner.handleInlineEditorKey(key, ctrl, shift)) {
            return true;
        }

        return owner.handleOverlayHexKey(key);
    }

    boolean charTyped(CharacterEvent input) {
        if (owner.isAnyKeybindCaptureActive()) {
            return true;
        }
        char ch = (char) input.codepoint();
        if (owner.handleInlineCharTyped(ch)) {
            return true;
        }
        return owner.handleOverlayHexChar(ch);
    }
}
