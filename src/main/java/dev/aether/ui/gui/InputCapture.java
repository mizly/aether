package dev.aether.ui.gui;

import org.lwjgl.glfw.GLFW;

// keybind capture: while active it sees pointer, key and char input before anything else in the gui,
// so binding '/', space, an arrow or mouse 4 never triggers a gui action instead
public final class InputCapture {
    private Object id;
    private KeyTarget target;

    public void begin(Object id, KeyTarget target) {
        this.id = id;
        this.target = target;
    }

    public boolean active() {
        return target != null;
    }

    public boolean capturing(Object id) {
        return target != null && this.id.equals(id);
    }

    public void cancel() {
        id = null;
        target = null;
    }

    // any button but the left one binds as a mouse key; a left click cancels and still reaches the gui
    public boolean pointer(PointerInput in) {
        if (target == null) {
            return false;
        }
        KeyTarget bound = target;
        cancel();
        if (in.isLeft()) {
            return false;
        }
        bound.bind(BoundKey.mouse(in.button()));
        return true;
    }

    // escape cancels, delete and backspace unbind, a key without a keysym binds by scancode
    public boolean key(KeyInput in) {
        if (target == null) {
            return false;
        }
        KeyTarget bound = target;
        cancel();
        switch (in.key()) {
            case GLFW.GLFW_KEY_ESCAPE -> {
            }
            case GLFW.GLFW_KEY_DELETE, GLFW.GLFW_KEY_BACKSPACE -> bound.clear();
            case GLFW.GLFW_KEY_UNKNOWN -> bound.bind(BoundKey.scancode(in.scancode()));
            default -> bound.bind(BoundKey.keysym(in.key()));
        }
        return true;
    }

    public boolean chars(String chars) {
        return target != null;
    }
}
