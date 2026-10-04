package dev.aether.ui.gui;

import org.lwjgl.glfw.GLFW;

// press returning true consumes the press and captures the pointer: drag and release then come here
// until the button is released, wherever the pointer goes. scroll returning false bubbles to regions below
public interface HitHandler {
    default boolean press(PointerEvent e) {
        return false;
    }

    default void drag(PointerEvent e) {
    }

    default void release(PointerEvent e) {
    }

    default boolean scroll(PointerEvent e, double dy) {
        return false;
    }

    // fires on the left press, the way vanilla buttons and the old menu react
    static HitHandler click(Runnable action) {
        return new HitHandler() {
            @Override
            public boolean press(PointerEvent e) {
                if (e.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                    return false;
                }
                action.run();
                return true;
            }
        };
    }
}
