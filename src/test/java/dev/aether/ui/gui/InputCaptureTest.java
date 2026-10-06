package dev.aether.ui.gui;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static dev.aether.ui.gui.Inputs.button;
import static dev.aether.ui.gui.Inputs.key;
import static dev.aether.ui.gui.Inputs.left;
import static org.junit.jupiter.api.Assertions.*;

class InputCaptureTest {
    private final InputCapture capture = new InputCapture();
    private final Target target = new Target();

    @Test
    void anyButtonButTheLeftOneBindsAsAMouseKey() {
        capture.begin("freelook", target);
        assertTrue(capture.capturing("freelook"));
        assertTrue(capture.pointer(button(GLFW.GLFW_MOUSE_BUTTON_4, 1f, 1f)));
        assertEquals(List.of(BoundKey.mouse(GLFW.GLFW_MOUSE_BUTTON_4)), target.bound);
        assertFalse(capture.active());
    }

    @Test
    void aLeftClickCancelsAndStillReachesTheGui() {
        capture.begin("freelook", target);
        assertFalse(capture.pointer(left(1f, 1f)));
        assertFalse(capture.active());
        assertTrue(target.bound.isEmpty());
        assertEquals(0, target.cleared);
    }

    @Test
    void escapeCancelsWithoutTouchingTheBinding() {
        capture.begin("freelook", target);
        assertTrue(capture.key(key(GLFW.GLFW_KEY_ESCAPE)));
        assertFalse(capture.active());
        assertTrue(target.bound.isEmpty());
        assertEquals(0, target.cleared);
    }

    @Test
    void deleteAndBackspaceUnbind() {
        capture.begin("a", target);
        assertTrue(capture.key(key(GLFW.GLFW_KEY_DELETE)));
        capture.begin("b", target);
        assertTrue(capture.key(key(GLFW.GLFW_KEY_BACKSPACE)));
        assertEquals(2, target.cleared);
        assertTrue(target.bound.isEmpty());
    }

    @Test
    void keysBindByKeysymOrByScancodeWhenTheyHaveNone() {
        capture.begin("a", target);
        assertTrue(capture.key(key(GLFW.GLFW_KEY_SLASH)));
        capture.begin("b", target);
        assertTrue(capture.key(new KeyInput(GLFW.GLFW_KEY_UNKNOWN, 94, 0, false, false, false)));
        assertEquals(List.of(BoundKey.keysym(GLFW.GLFW_KEY_SLASH), BoundKey.scancode(94)), target.bound);
    }

    @Test
    void charsAreSwallowedOnlyWhileCapturing() {
        capture.begin("a", target);
        assertTrue(capture.chars("/"));
        capture.cancel();
        assertFalse(capture.chars("/"));
        assertFalse(capture.key(key(GLFW.GLFW_KEY_A)));
        assertFalse(capture.pointer(button(GLFW.GLFW_MOUSE_BUTTON_RIGHT, 0f, 0f)));
        assertFalse(capture.capturing("a"));
    }

    private static final class Target implements KeyTarget {
        private final List<BoundKey> bound = new ArrayList<>();
        private int cleared;

        @Override
        public void bind(BoundKey key) {
            bound.add(key);
        }

        @Override
        public void clear() {
            cleared++;
        }
    }
}
