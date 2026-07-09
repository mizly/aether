package dev.aether.ui.components.tabs;

import dev.aether.ui.util.Colors;
import dev.aether.ui.util.Fonts;
import dev.aether.renderer.NVGRenderer;

import java.util.List;

/**
 * Renders a horizontal row of sub-tab labels with an animated underline indicator.
 */
public class SubTabBar {

    private static final float HEIGHT    = 34f;
    private static final float FONT_SIZE = 13f;
    private static final float ANIM_SPEED = 0.18f;

    private float x, y, width;
    private final List<SubTab> tabs;
    private int selectedIndex = 0;

    // Animated indicator position (in tab-index space)
    private float indicatorX     = 0f; // left edge of indicator
    private float indicatorWidth = 0f;
    private boolean firstFrame   = true;

    public SubTabBar(List<SubTab> tabs, float x, float y, float width) {
        this.tabs  = tabs;
        this.x     = x;
        this.y     = y;
        this.width = width;
    }

    // -- Rendering -------------------------------------------------------------

    public void render(NVGRenderer nvg) {
        if (tabs.isEmpty()) return;

        // Background / separator
        nvg.rect(x, y, width, HEIGHT, Colors.SURFACE);
        nvg.line(x, y + HEIGHT - 1f, x + width, y + HEIGHT - 1f, 1f, Colors.BORDER);

        float tabW = width / tabs.size();

        // Compute indicator target
        float targetX = x + selectedIndex * tabW;
        float targetW = tabW;

        if (firstFrame) {
            indicatorX     = targetX;
            indicatorWidth = targetW;
            firstFrame     = false;
        } else {
            indicatorX     += (targetX - indicatorX)     * ANIM_SPEED * 4f;
            indicatorWidth += (targetW - indicatorWidth) * ANIM_SPEED * 4f;
        }

        // Draw labels
        for (int i = 0; i < tabs.size(); i++) {
            float tx = x + i * tabW;
            boolean active = (i == selectedIndex);
            int textColor = active ? Colors.ACCENT : Colors.TEXT_DIM;
            float labelX  = tx + (tabW - nvg.textWidth(Fonts.REGULAR, tabs.get(i).getLabel(), FONT_SIZE)) / 2f;
            float labelY  = y + (HEIGHT - FONT_SIZE) / 2f;
            nvg.text(Fonts.REGULAR, tabs.get(i).getLabel(), labelX, labelY, FONT_SIZE, textColor);
        }

        // Animated underline
        nvg.rect(indicatorX, y + HEIGHT - 2f, indicatorWidth, 2f, Colors.ACCENT);
    }

    // -- Input -----------------------------------------------------------------

    public boolean mousePressed(double mouseX, double mouseY, int button) {
        if (button != 0 || mouseY < y || mouseY >= y + HEIGHT) return false;
        float tabW = width / tabs.size();
        for (int i = 0; i < tabs.size(); i++) {
            float tx = x + i * tabW;
            if (mouseX >= tx && mouseX < tx + tabW) {
                selectedIndex = i;
                return true;
            }
        }
        return false;
    }

    public void mouseReleased(double mouseX, double mouseY, int button) {}

    // -- API -------------------------------------------------------------------

    public void setBounds(float x, float y, float width, float height) {
        this.x = x; this.y = y; this.width = width;
    }

    public SubTab getActiveSubTab() {
        return tabs.isEmpty() ? null : tabs.get(selectedIndex);
    }

    public int getSelectedIndex() { return selectedIndex; }
    public void setSelectedIndex(int i) { selectedIndex = i; }
    public float getHeight() { return HEIGHT; }
}
