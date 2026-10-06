package dev.aether.ui.gui;

// key is a GLFW_KEY_* code; shortcut is ctrl, or cmd where minecraft's edit-shortcut quirk applies
public record KeyInput(int key, int scancode, int mods, boolean shortcut, boolean shift, boolean alt) {
    public boolean is(int glfwKey) {
        return key == glfwKey;
    }
}
