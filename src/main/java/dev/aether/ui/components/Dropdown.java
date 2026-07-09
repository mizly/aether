package dev.aether.ui.components;

import dev.aether.ui.util.Colors;
import dev.aether.ui.util.Fonts;
import dev.aether.renderer.NVGRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A dropdown selector that expands a list of options when clicked and
 * collapses when an item is selected or the user clicks outside.
 *
 * <pre>{@code
 * Dropdown<String> dd = new Dropdown<>(List.of("Option A", "Option B"), 0);
 * dd.setBounds(x, y, 160, 28);
 * dd.setOnSelect(v -> config.mode = v);
 * }</pre>
 */
public class Dropdown<T> extends Component {

    // -- Layout ----------------------------------------------------------------

    private static final float ITEM_H    = 28f;
    private static final float FONT_SIZE = 13f;
    private static final float PAD_X     = 10f;
    private static final float RADIUS    =  6f;

    // -- State -----------------------------------------------------------------

    private final List<T>   options;
    private int             selectedIndex;
    private boolean         expanded = false;
    private Consumer<T>     onSelect;

    // -- Construction ----------------------------------------------------------

    public Dropdown(List<T> options, int initialIndex) {
        this.options       = new ArrayList<>(options);
        this.selectedIndex = Math.max(0, Math.min(initialIndex, options.size() - 1));
        this.height        = ITEM_H;
    }

    // -- Rendering -------------------------------------------------------------

    @Override
    public void render(NVGRenderer nvg) {
        if (!visible) return;

        // Header row
        int borderColor  = expanded ? Colors.BORDER_ACT : Colors.BORDER;
        int headerColor  = expanded ? Colors.ACTIVE : Colors.FIELD;
        nvg.roundedRect(x, y, width, ITEM_H, RADIUS, headerColor);
        nvg.rectOutline(x, y, width, ITEM_H, RADIUS, 1f, borderColor);

        // Selected label
        String selectedLabel = options.isEmpty() ? "" : options.get(selectedIndex).toString();
        nvg.text(Fonts.REGULAR, selectedLabel, x + PAD_X, y + (ITEM_H - FONT_SIZE) / 2f, FONT_SIZE, Colors.TEXT);

        // Chevron
        float cx = x + width - 14f;
        float cy = y + ITEM_H / 2f;
        drawChevron(nvg, cx, cy, expanded);

        // Drop-down list (drawn on top, same frame)
        if (expanded && !options.isEmpty()) {
            float listY  = y + ITEM_H + 2f;
            float listH  = options.size() * ITEM_H;

            nvg.shadow(x, listY, width, listH, RADIUS, 12f, Colors.withAlpha(Colors.BLACK, 0.5f));
            nvg.roundedRect(x, listY, width, listH, RADIUS, Colors.SURFACE);
            nvg.rectOutline(x, listY, width, listH, RADIUS, 1f, Colors.BORDER);

            for (int i = 0; i < options.size(); i++) {
                float iy = listY + i * ITEM_H;
                if (i == selectedIndex) {
                    // Highlight
                    nvg.rect(x + 1, iy, width - 2, ITEM_H, Colors.ACCENT_DIM);
                }
                int textColor = (i == selectedIndex) ? Colors.ACCENT : Colors.TEXT;
                nvg.text(Fonts.REGULAR, options.get(i).toString(),
                        x + PAD_X, iy + (ITEM_H - FONT_SIZE) / 2f, FONT_SIZE, textColor);
            }
        }
    }

    private static void drawChevron(NVGRenderer nvg, float cx, float cy, boolean up) {
        float hs = 4f; // half-size
        float dy = up ? -hs : hs;
        // && or || - draw as two lines
        nvg.line(cx - hs, cy + (up ? hs : -hs),
                 cx,      cy - (up ? hs : -hs),
                 1.5f, Colors.TEXT_DIM);
        nvg.line(cx,      cy - (up ? hs : -hs),
                 cx + hs, cy + (up ? hs : -hs),
                 1.5f, Colors.TEXT_DIM);
    }

    // -- Input -----------------------------------------------------------------

    @Override
    public boolean mousePressed(double mouseX, double mouseY, int button) {
        if (button != 0) return false;

        // Click on header -> toggle
        if (mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + ITEM_H) {
            expanded = !expanded;
            return true;
        }

        // Click on list item
        if (expanded) {
            float listY = y + ITEM_H + 2f;
            if (mouseX >= x && mouseX < x + width) {
                for (int i = 0; i < options.size(); i++) {
                    float iy = listY + i * ITEM_H;
                    if (mouseY >= iy && mouseY < iy + ITEM_H) {
                        select(i);
                        expanded = false;
                        return true;
                    }
                }
            }
            // Clicked outside -> close
            expanded = false;
            return false;
        }

        return false;
    }

    /** Collapse without selecting when a click is detected outside this component. */
    public void closeIfOutside(double mouseX, double mouseY) {
        if (expanded) {
            float listH = options.size() * ITEM_H;
            boolean insideHeader = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + ITEM_H;
            boolean insideList   = mouseX >= x && mouseX < x + width
                    && mouseY >= y + ITEM_H + 2f && mouseY < y + ITEM_H + 2f + listH;
            if (!insideHeader && !insideList) expanded = false;
        }
    }

    // -- API -------------------------------------------------------------------

    public T getSelected() {
        return options.isEmpty() ? null : options.get(selectedIndex);
    }

    public int getSelectedIndex() { return selectedIndex; }

    public Dropdown<T> setOnSelect(Consumer<T> handler) {
        this.onSelect = handler;
        return this;
    }

    private void select(int index) {
        selectedIndex = index;
        if (onSelect != null && !options.isEmpty()) {
            onSelect.accept(options.get(index));
        }
    }

    /** Returns {@code true} if the option list is currently shown. */
    public boolean isExpanded() { return expanded; }
}
