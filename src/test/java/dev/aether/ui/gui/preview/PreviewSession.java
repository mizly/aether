package dev.aether.ui.gui.preview;

import dev.aether.ui.gui.GuiView;
import dev.aether.ui.gui.KeyInput;
import dev.aether.ui.gui.LaunchRequest;
import dev.aether.ui.gui.ManualClock;
import dev.aether.ui.gui.PointerInput;
import org.lwjgl.glfw.GLFW;

// what a scenario's steps drive: one view, its clock and the pointer. every step draws a frame, because
// hover and hit regions come from the frame before
public final class PreviewSession {
    private static final long FRAME_MS = 16L;

    private final GuiView view;
    private final ManualClock clock;
    private final float width;
    private final float height;
    private final FrameDrawer drawer;
    private float mouseX = -1f;
    private float mouseY = -1f;

    PreviewSession(GuiView view, ManualClock clock, float width, float height, FrameDrawer drawer) {
        this.view = view;
        this.clock = clock;
        this.width = width;
        this.height = height;
        this.drawer = drawer;
    }

    public void frame() {
        drawer.draw(view, width, height, mouseX, mouseY);
    }

    public void hover(float x, float y) {
        mouseX = x;
        mouseY = y;
        frame();
    }

    public void click(float x, float y) {
        hover(x, y);
        PointerInput input = new PointerInput(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0, 1);
        view.pointerPressed(input);
        view.pointerReleased(input);
        frame();
    }

    public void drag(float fromX, float fromY, float toX, float toY) {
        hover(fromX, fromY);
        view.pointerPressed(new PointerInput(fromX, fromY, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0, 1));
        for (int step = 1; step <= 8; step++) {
            float x = fromX + (toX - fromX) * step / 8f;
            float y = fromY + (toY - fromY) * step / 8f;
            view.pointerDragged(new PointerInput(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0, 1));
            hover(x, y);
        }
        view.pointerReleased(new PointerInput(toX, toY, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0, 1));
        frame();
    }

    public void scroll(float x, float y, double dy) {
        hover(x, y);
        view.scrolled(x, y, 0.0, dy);
        frame();
    }

    public void key(KeyInput key) {
        view.keyPressed(key);
        frame();
    }

    public void type(String text) {
        text.codePoints().forEach(codePoint -> view.charTyped(Character.toString(codePoint)));
        frame();
    }

    // in 16 ms frames, since scrolling and other motion step per frame
    public void advance(long millis) {
        for (long left = millis; left > 0L; left -= FRAME_MS) {
            clock.advanceMillis(Math.min(FRAME_MS, left));
            frame();
        }
    }

    public void navigate(LaunchRequest launch) {
        view.open(launch);
        frame();
    }

    @FunctionalInterface
    interface FrameDrawer {
        void draw(GuiView view, float width, float height, float mouseX, float mouseY);
    }
}
