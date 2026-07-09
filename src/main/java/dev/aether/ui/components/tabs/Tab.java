package dev.aether.ui.components.tabs;

import dev.aether.ui.components.Component;
import dev.aether.renderer.NVGRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * A top-level tab that holds content components and optional sub-tabs.
 *
 * <p>Tabs are managed by a {@link TabBar}. The tab itself does not render its
 * header - the {@link TabBar} handles that. Only {@link #renderContent(NVGRenderer)}
 * is called when this tab is active.</p>
 */
public class Tab {

    private final String            label;
    private final List<Component>   content  = new ArrayList<>();
    private final List<SubTab>      subTabs  = new ArrayList<>();
    private       SubTabBar         subTabBar;

    // -- Construction ----------------------------------------------------------

    public Tab(String label) {
        this.label = label;
    }

    // -- Children --------------------------------------------------------------

    public Tab addContent(Component c) {
        content.add(c);
        return this;
    }

    public Tab addSubTab(SubTab subTab) {
        subTabs.add(subTab);
        return this;
    }

    /** Call after all sub-tabs are added to build the {@link SubTabBar}. */
    public Tab buildSubTabBar(float x, float y, float width) {
        if (!subTabs.isEmpty()) {
            subTabBar = new SubTabBar(subTabs, x, y, width);
        }
        return this;
    }

    // -- Rendering -------------------------------------------------------------

    /**
     * Renders the content of this tab (sub-tab bar + active sub-tab or direct content).
     *
     * @param nvg     active renderer
     * @param x       content area left
     * @param y       content area top
     * @param width   content area width
     * @param height  content area height
     */
    public void renderContent(NVGRenderer nvg, float x, float y, float width, float height) {
        if (subTabBar != null) {
            subTabBar.setBounds(x, y, width, subTabBar.getHeight());
            subTabBar.render(nvg);

            float below = y + subTabBar.getHeight();
            SubTab active = subTabBar.getActiveSubTab();
            if (active != null) {
                active.renderContent(nvg, x, below, width, height - subTabBar.getHeight());
            }
        } else {
            for (Component c : content) {
                if (c.isVisible()) c.render(nvg);
            }
        }
    }

    // -- Input forwarding -----------------------------------------------------

    public boolean mousePressed(double mx, double my, int btn) {
        if (subTabBar != null && subTabBar.mousePressed(mx, my, btn)) return true;
        SubTab active = subTabBar != null ? subTabBar.getActiveSubTab() : null;
        if (active != null) return active.mousePressed(mx, my, btn);
        for (int i = content.size() - 1; i >= 0; i--) {
            Component c = content.get(i);
            if (c.isEnabled() && c.contains(mx, my) && c.mousePressed(mx, my, btn)) return true;
        }
        return false;
    }

    public boolean mouseReleased(double mx, double my, int btn) {
        if (subTabBar != null) subTabBar.mouseReleased(mx, my, btn);
        SubTab active = subTabBar != null ? subTabBar.getActiveSubTab() : null;
        if (active != null) return active.mouseReleased(mx, my, btn);
        for (Component c : content) {
            if (c.isEnabled() && c.mouseReleased(mx, my, btn)) return true;
        }
        return false;
    }

    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        SubTab active = subTabBar != null ? subTabBar.getActiveSubTab() : null;
        if (active != null) return active.mouseDragged(mx, my, btn, dx, dy);
        for (Component c : content) {
            if (c.isEnabled() && c.mouseDragged(mx, my, btn, dx, dy)) return true;
        }
        return false;
    }

    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        SubTab active = subTabBar != null ? subTabBar.getActiveSubTab() : null;
        if (active != null) return active.mouseScrolled(mx, my, sx, sy);
        for (Component c : content) {
            if (c.isEnabled() && c.contains(mx, my) && c.mouseScrolled(mx, my, sx, sy)) return true;
        }
        return false;
    }

    public boolean keyPressed(int key, int scan, int mods) {
        SubTab active = subTabBar != null ? subTabBar.getActiveSubTab() : null;
        if (active != null) return active.keyPressed(key, scan, mods);
        for (Component c : content) {
            if (c.isEnabled() && c.keyPressed(key, scan, mods)) return true;
        }
        return false;
    }

    public boolean charTyped(char ch, int mods) {
        SubTab active = subTabBar != null ? subTabBar.getActiveSubTab() : null;
        if (active != null) return active.charTyped(ch, mods);
        for (Component c : content) {
            if (c.isEnabled() && c.charTyped(ch, mods)) return true;
        }
        return false;
    }

    // -- Accessors -------------------------------------------------------------

    public String      getLabel()     { return label; }
    public SubTabBar   getSubTabBar() { return subTabBar; }
    public List<SubTab> getSubTabs()  { return subTabs; }
}
