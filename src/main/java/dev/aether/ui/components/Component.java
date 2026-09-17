package dev.aether.ui.components;

import dev.aether.renderer.NVGRenderer;

// positioned in screen space, drawn only through NVGRenderer; the parent NVGScreen forwards mouse and key events
public abstract class Component {

    // -- Bounds ----------------------------------------------------------------

    protected float x, y, width, height;

    protected boolean visible = true;
    protected boolean enabled = true;

    // -- Rendering -------------------------------------------------------------

    // called each frame while a nanovg frame is open
    public abstract void render(NVGRenderer nvg);

    // -- Input events ----------------------------------------------------------

    // button is the glfw index; true consumes the event
    public boolean mousePressed(double mouseX, double mouseY, int button) { return false; }

    // true consumes the event
    public boolean mouseReleased(double mouseX, double mouseY, int button) { return false; }

    // only while a button is held; true consumes the event
    public boolean mouseDragged(double mouseX, double mouseY, int button,
                                double deltaX, double deltaY) { return false; }

    // scrollY is positive upward; true consumes the event
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double scrollX, double scrollY) { return false; }

    // true consumes the event
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) { return false; }

    // true consumes the event
    public boolean charTyped(char codePoint, int modifiers) { return false; }

    // -- Bounds helpers --------------------------------------------------------

    public boolean contains(double px, double py) {
        return px >= x && px < x + width && py >= y && py < y + height;
    }

    public Component setPosition(float x, float y) {
        this.x = x;
        this.y = y;
        return this;
    }

    public Component setSize(float width, float height) {
        this.width  = width;
        this.height = height;
        return this;
    }

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
