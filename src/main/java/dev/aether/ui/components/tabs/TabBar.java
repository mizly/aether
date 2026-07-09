package dev.aether.ui.components.tabs;

import dev.aether.ui.util.Colors;
import dev.aether.ui.util.Fonts;
import dev.aether.ui.components.Component;
import dev.aether.renderer.NVGRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * A horizontal top-level tab bar with animated accent underline.
 *
 * <p>The active tab's content is rendered below the bar. Tabs can contain
 * sub-tabs (see {@link Tab#addSubTab}).</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * Tab farming = new Tab("Farming").addContent(someComponent);
 * Tab visuals = new Tab("Visuals");
 *
 * TabBar bar = new TabBar(x, y, width, height);
 * bar.addTab(farming).addTab(visuals);
 * // Add bar as a component to an NVGScreen or Panel.
 * }</pre>
 */
public class TabBar extends Component {

    // -- Layout ----------------------------------------------------------------

    private static final float TAB_H      = 38f;
    private static final float FONT_SIZE  = 14f;
    private static final float ANIM_SPEED = 0.2f;

    // -- State -----------------------------------------------------------------

    private final List<Tab> tabs = new ArrayList<>();
    private int   selectedIndex = 0;

    // Animated indicator
    private float indX = 0f, indW = 0f;
    private boolean firstFrame = true;

    // -- Construction ----------------------------------------------------------

    public TabBar(float x, float y, float width, float height) {
        this.x      = x;
        this.y      = y;
        this.width  = width;
        this.height = height;
    }

    public TabBar addTab(Tab tab) {
        tabs.add(tab);
        return this;
    }

    // -- Rendering -------------------------------------------------------------

    @Override
    public void render(NVGRenderer nvg) {
        if (!visible || tabs.isEmpty()) return;

        // Tab bar background
        nvg.rect(x, y, width, TAB_H, Colors.SURFACE);
        nvg.line(x, y + TAB_H, x + width, y + TAB_H, 1f, Colors.BORDER);

        float tabW = width / tabs.size();

        // Indicator target
        float targetX = x + selectedIndex * tabW;
        float targetW = tabW;
        if (firstFrame) {
            indX = targetX; indW = targetW; firstFrame = false;
        } else {
            indX += (targetX - indX) * ANIM_SPEED * 4f;
            indW += (targetW - indW) * ANIM_SPEED * 4f;
        }

        // Labels
        for (int i = 0; i < tabs.size(); i++) {
            boolean active = (i == selectedIndex);
            float tx = x + i * tabW;
            int textColor = active ? Colors.TEXT : Colors.TEXT_DIM;
            String label = tabs.get(i).getLabel();
            float lx = tx + (tabW - nvg.textWidth(active ? Fonts.BOLD : Fonts.REGULAR, label, FONT_SIZE)) / 2f;
            float ly = y + (TAB_H - FONT_SIZE) / 2f;
            nvg.text(active ? Fonts.BOLD : Fonts.REGULAR, label, lx, ly, FONT_SIZE, textColor);
        }

        // Indicator underline
        nvg.rect(indX, y + TAB_H - 2f, indW, 2f, Colors.ACCENT);

        // Content area
        float contentY = y + TAB_H + 1f;
        float contentH = height - TAB_H - 1f;

        Tab active = tabs.get(selectedIndex);
        active.renderContent(nvg, x, contentY, width, contentH);
    }

    // -- Input -----------------------------------------------------------------

    @Override
    public boolean mousePressed(double mouseX, double mouseY, int button) {
        if (!visible || !enabled || button != 0) return false;

        // Tab header click
        if (mouseY >= y && mouseY < y + TAB_H) {
            float tabW = width / tabs.size();
            for (int i = 0; i < tabs.size(); i++) {
                float tx = x + i * tabW;
                if (mouseX >= tx && mouseX < tx + tabW) {
                    selectedIndex = i;
                    return true;
                }
            }
        }

        // Forward to active tab content
        return tabs.isEmpty() ? false : tabs.get(selectedIndex).mousePressed(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (!visible || !enabled || tabs.isEmpty()) return false;
        return tabs.get(selectedIndex).mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button,
                                double deltaX, double deltaY) {
        if (!visible || !enabled || tabs.isEmpty()) return false;
        return tabs.get(selectedIndex).mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double scrollX, double scrollY) {
        if (!visible || !enabled || tabs.isEmpty()) return false;
        return tabs.get(selectedIndex).mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!visible || !enabled || tabs.isEmpty()) return false;
        return tabs.get(selectedIndex).keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (!visible || !enabled || tabs.isEmpty()) return false;
        return tabs.get(selectedIndex).charTyped(codePoint, modifiers);
    }

    // -- API -------------------------------------------------------------------

    public Tab    getActiveTab()        { return tabs.isEmpty() ? null : tabs.get(selectedIndex); }
    public int    getSelectedIndex()    { return selectedIndex; }
    public void   setSelectedIndex(int i) { selectedIndex = Math.max(0, Math.min(i, tabs.size() - 1)); }
    public List<Tab> getTabs()          { return tabs; }
    public float  getTabBarHeight()     { return TAB_H; }
}
