package dev.aether.ui.gui;

import org.lwjgl.glfw.GLFW;

// short constructors for pointer and key events in tests
public final class Inputs {
    private Inputs() {
    }

    public static PointerInput left(float x, float y) {
        return button(GLFW.GLFW_MOUSE_BUTTON_LEFT, x, y);
    }

    public static PointerInput button(int glfwButton, float x, float y) {
        return new PointerInput(x, y, glfwButton, 0, 1);
    }

    public static KeyInput key(int glfwKey) {
        return new KeyInput(glfwKey, 0, 0, false, false, false);
    }

    public static KeyInput shortcut(int glfwKey) {
        return new KeyInput(glfwKey, 0, GLFW.GLFW_MOD_CONTROL, true, false, false);
    }

    public static KeyInput shift(int glfwKey) {
        return new KeyInput(glfwKey, 0, GLFW.GLFW_MOD_SHIFT, false, true, false);
    }

    public static KeyInput shortcutShift(int glfwKey) {
        return new KeyInput(glfwKey, 0, GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SHIFT, true, true, false);
    }

    public static KeyInput alt(int glfwKey) {
        return new KeyInput(glfwKey, 0, GLFW.GLFW_MOD_ALT, false, false, true);
    }
}
