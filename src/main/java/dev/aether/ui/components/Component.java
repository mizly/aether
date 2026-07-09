package dev.aether.ui.components;

import dev.aether.renderer.NVGRenderer;

/**
 * Abstract base class for all NanoVG UI components.
 *
 * <p>Components are positioned in screen-space and receive mouse/keyboard
 * events forwarded by their parent {@link dev.aether.renderer.NVGScreen}.
 * Rendering is done exclusively via {@link NVGRenderer}.</p>
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>Construct the component and set its bounds.</li>
 *   <li>Add it to a {@link dev.aether.renderer.NVGScreen} or a parent
 *       container such as {@link Panel}.</li>
 *   <li>The screen calls {@link #render(NVGRenderer)} each frame.</li>
 *   <li>Mouse/key events are forwarded to the focused component hierarchy.</li>
 * </ol>
 */
public abstract class Component {

    // -- Bounds ----------------------------------------------------------------

    protected float x, y, width, height;

    /** Whether this component receives input / is rendered. */
    protected boolean visible = true;
    /** Whether this component can receive mouse/keyboard focus. */
    protected boolean enabled = true;

    // -- Rendering -------------------------------------------------------------

    /**
     * Renders this component using the supplied {@link NVGRenderer}.
     * Called each frame while a NanoVG frame is open.
     *
     * @param nvg the active renderer (frame already open)
     */
    public abstract void render(NVGRenderer nvg);

    // -- Input events ----------------------------------------------------------

    /**
     * Called when a mouse button is pressed inside this component's bounds.
     *
     * @param mouseX screen X in pixels
     * @param mouseY screen Y in pixels
     * @param button GLFW mouse button index (0 = left, 1 = right, 2 = middle)
     * @return {@code true} if the event was consumed
     */
    public boolean mousePressed(double mouseX, double mouseY, int button) { return false; }

    /**
     * Called when a mouse button is released.
     *
     * @param mouseX screen X in pixels
     * @param mouseY screen Y in pixels
     * @param button GLFW mouse button index
     * @return {@code true} if the event was consumed
     */
    public boolean mouseReleased(double mouseX, double mouseY, int button) { return false; }

    /**
     * Called when the mouse moves while at least one button is held.
     *
     * @param mouseX screen X
     * @param mouseY screen Y
     * @param deltaX movement since last event
     * @param deltaY movement since last event
     * @return {@code true} if the event was consumed
     */
    public boolean mouseDragged(double mouseX, double mouseY, int button,
                                double deltaX, double deltaY) { return false; }

    /**
     * Called when the scroll wheel moves.
     *
     * @param mouseX  screen X
     * @param mouseY  screen Y
     * @param scrollX horizontal scroll (usually 0)
     * @param scrollY vertical scroll (positive = up)
     * @return {@code true} if the event was consumed
     */
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double scrollX, double scrollY) { return false; }

    /**
     * Called when a key is pressed.
     *
     * @param keyCode   GLFW key code
     * @param scanCode  platform scan code
     * @param modifiers GLFW modifier flags
     * @return {@code true} if the event was consumed
     */
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) { return false; }

    /**
     * Called when a Unicode character is typed.
     *
     * @param codePoint the character
     * @param modifiers GLFW modifier flags
     * @return {@code true} if the event was consumed
     */
    public boolean charTyped(char codePoint, int modifiers) { return false; }

    // -- Bounds helpers --------------------------------------------------------

    /** Returns {@code true} if the point {@code (px, py)} is within this component's bounds. */
    public boolean contains(double px, double py) {
        return px >= x && px < x + width && py >= y && py < y + height;
    }

    /** Sets the position of this component. */
    public Component setPosition(float x, float y) {
        this.x = x;
        this.y = y;
        return this;
    }

    /** Sets the size of this component. */
    public Component setSize(float width, float height) {
        this.width  = width;
        this.height = height;
        return this;
    }

    /** Sets position and size in one call. */
    public Component setBounds(float x, float y, float width, float height) {
        this.x      = x;
        this.y      = y;
        this.width  = width;
        this.height = height;
        return this;
    }

    public float getX()      { return x; }
    public float getY()      { return y; }
    public float getWidth()  { return width; }
    public float getHeight() { return height; }

    public boolean isVisible() { return visible; }
    public boolean isEnabled() { return enabled; }

    public Component setVisible(boolean visible) { this.visible = visible; return this; }
    public Component setEnabled(boolean enabled) { this.enabled = enabled; return this; }
}
