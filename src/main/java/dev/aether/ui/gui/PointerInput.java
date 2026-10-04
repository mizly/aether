package dev.aether.ui.gui;

import org.lwjgl.glfw.GLFW;

// a pointer press, release or drag in layout units; clicks is 2 on the second press of a double click
public record PointerInput(float x, float y, int button, int mods, int clicks) {
    public boolean isLeft() {
        return button == GLFW.GLFW_MOUSE_BUTTON_LEFT;
    }
}
