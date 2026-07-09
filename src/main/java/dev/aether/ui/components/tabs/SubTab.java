package dev.aether.ui.components.tabs;

import dev.aether.ui.components.Component;
import dev.aether.renderer.NVGRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * A secondary tab nested inside a {@link Tab}.
 *
 * <p>Its header is rendered by the parent {@link SubTabBar}; only
 * {@link #renderContent} is called when this sub-tab is active.</p>
 */
public class SubTab {

    private final String          label;
    private final List<Component> content = new ArrayList<>();

    public SubTab(String label) {
        this.label = label;
    }

    // -- Children --------------------------------------------------------------

    public SubTab addContent(Component c) {
        content.add(c);
        return this;
    }

    // -- Rendering -------------------------------------------------------------

    public void renderContent(NVGRenderer nvg, float x, float y, float width, float height) {
        for (Component c : content) {
            if (c.isVisible()) c.render(nvg);
        }
    }

    // -- Input -----------------------------------------------------------------

    public boolean mousePressed(double mx, double my, int btn) {
        for (int i = content.size() - 1; i >= 0; i--) {
            Component c = content.get(i);
            if (c.isEnabled() && c.contains(mx, my) && c.mousePressed(mx, my, btn)) return true;
        }
        return false;
    }

    public boolean mouseReleased(double mx, double my, int btn) {
        for (Component c : content)
            if (c.isEnabled() && c.mouseReleased(mx, my, btn)) return true;
        return false;
    }

    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        for (Component c : content)
            if (c.isEnabled() && c.mouseDragged(mx, my, btn, dx, dy)) return true;
        return false;
    }

    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        for (Component c : content)
            if (c.isEnabled() && c.contains(mx, my) && c.mouseScrolled(mx, my, sx, sy)) return true;
        return false;
    }

    public boolean keyPressed(int key, int scan, int mods) {
        for (Component c : content) if (c.isEnabled() && c.keyPressed(key, scan, mods)) return true;
        return false;
    }

    public boolean charTyped(char ch, int mods) {
        for (Component c : content) if (c.isEnabled() && c.charTyped(ch, mods)) return true;
        return false;
    }

    // -- Accessors -------------------------------------------------------------

    public String getLabel() { return label; }
    public List<Component> getContent() { return content; }
}
