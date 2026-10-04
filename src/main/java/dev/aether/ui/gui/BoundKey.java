package dev.aether.ui.gui;

// a key or mouse button to bind; the host turns it into minecraft's InputConstants.Key
public record BoundKey(Type type, int code) {
    public enum Type { KEYSYM, SCANCODE, MOUSE }

    public static BoundKey keysym(int glfwKey) {
        return new BoundKey(Type.KEYSYM, glfwKey);
    }

    public static BoundKey scancode(int scancode) {
        return new BoundKey(Type.SCANCODE, scancode);
    }

    public static BoundKey mouse(int glfwButton) {
        return new BoundKey(Type.MOUSE, glfwButton);
    }
}
