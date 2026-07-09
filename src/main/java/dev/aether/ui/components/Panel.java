package dev.aether.ui.components;

import dev.aether.ui.util.Colors;
import dev.aether.ui.util.Fonts;
import dev.aether.renderer.NVGRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * A draggable, rounded panel window that clips its children.
 *
 * <p>Children are positioned relative to the panel's top-left corner and
 * clipped to the content area (below the title bar).</p>
 *
 * <h3>Example</h3>
 * <pre>{@code
 * Panel panel = new Panel("Settings", 100, 100, 300, 400);
 * panel.add(new Button(...));
 * }</pre>
 */
public class Panel extends Component {

    // -- Layout constants ------------------------------------------------------

    private static final float TITLE_H   = 32f;
    private static final float RADIUS    = 10f;
    private static final float SHADOW_BL = 20f;

    // -- State -----------------------------------------------------------------

    private String title;
    private boolean dragging  = false;
    private double  dragOffX  = 0;
    private double  dragOffY  = 0;

    private final List<Component> children = new ArrayList<>();

    // -- Construction ----------------------------------------------------------

    public Panel(String title, float x, float y, float width, float height) {
        this.title  = title;
        this.x      = x;
        this.y      = y;
        this.width  = width;
        this.height = height;
    }

    // -- Children --------------------------------------------------------------

    public <T extends Component> T add(T child) {
        children.add(child);
        return child;
    }

    public void remove(Component child) {
        children.remove(child);
    }

    // -- Rendering -------------------------------------------------------------

    @Override
    public void render(NVGRenderer nvg) {
        if (!visible) return;

        // Drop shadow
        nvg.shadow(x, y, width, height, RADIUS, SHADOW_BL, Colors.withAlpha(Colors.BLACK, 0.55f));

        // Background
        nvg.roundedRect(x, y, width, height, RADIUS, Colors.BG);

        // Title bar background
        nvg.roundedRect(x, y, width, TITLE_H, RADIUS, Colors.SURFACE);
        // Square off the bottom corners of the title bar
        nvg.rect(x, y + RADIUS, width, TITLE_H - RADIUS, Colors.SURFACE);

        // Title text
        float titleY = y + (TITLE_H - 14f) / 2f;
        nvg.text(Fonts.BOLD, title, x + 14f, titleY, 14f, Colors.TEXT);

        // Border
        nvg.rectOutline(x, y, width, height, RADIUS, 1f, Colors.BORDER);

        // Accent line under title
        nvg.rect(x + 1, y + TITLE_H - 1f, width - 2f, 1f, Colors.BORDER);

        // Clip children to content area
        float contentY = y + TITLE_H;
        float contentH = height - TITLE_H;
        nvg.pushScissor(x, contentY, width, contentH);

        for (Component child : children) {
            if (child.isVisible()) {
                child.render(nvg);
            }
        }

        nvg.popScissor();
    }

    // -- Drag -----------------------------------------------------------------

    @Override
    public boolean mousePressed(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseY >= y && mouseY < y + TITLE_H
                && mouseX >= x && mouseX < x + width) {
            dragging = true;
            dragOffX = mouseX - x;
            dragOffY = mouseY - y;
            return true;
        }
        // Forward to children
        for (int i = children.size() - 1; i >= 0; i--) {
            Component c = children.get(i);
            if (c.isVisible() && c.isEnabled() && c.contains(mouseX, mouseY)) {
                if (c.mousePressed(mouseX, mouseY, button)) return true;
            }
        }
        return contains(mouseX, mouseY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && dragging) {
            dragging = false;
            return true;
        }
        for (Component c : children) {
            if (c.isVisible() && c.isEnabled()) {
                if (c.mouseReleased(mouseX, mouseY, button)) return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button,
                                double deltaX, double deltaY) {
        if (dragging && button == 0) {
            x = (float) (mouseX - dragOffX);
            y = (float) (mouseY - dragOffY);
            return true;
        }
        for (Component c : children) {
            if (c.isVisible() && c.isEnabled()) {
                if (c.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)) return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double scrollX, double scrollY) {
        for (int i = children.size() - 1; i >= 0; i--) {
            Component c = children.get(i);
            if (c.isVisible() && c.isEnabled() && c.contains(mouseX, mouseY)) {
                if (c.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) return true;
            }
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        for (int i = children.size() - 1; i >= 0; i--) {
            Component c = children.get(i);
            if (c.isVisible() && c.isEnabled()) {
                if (c.keyPressed(keyCode, scanCode, modifiers)) return true;
            }
        }
        return false;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        for (int i = children.size() - 1; i >= 0; i--) {
            Component c = children.get(i);
            if (c.isVisible() && c.isEnabled()) {
                if (c.charTyped(codePoint, modifiers)) return true;
            }
        }
        return false;
    }

    // -- Accessors -------------------------------------------------------------

    public String getTitle() { return title; }
    public Panel setTitle(String title) { this.title = title; return this; }

    /** Returns the Y coordinate of the top of the content area (below the title bar). */
    public float getContentY() { return y + TITLE_H; }
    /** Returns the height of the content area (panel height minus title bar). */
    public float getContentHeight() { return height - TITLE_H; }
}
