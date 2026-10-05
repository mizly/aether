package dev.aether.ui.gui.widget;

import dev.aether.ui.gui.Rect;
import dev.aether.ui.gui.skin.RowLayout;

// one setting row as SettingsList hands it to a widget. rect is the row's content area (for height(), only
// its width counts). description is the inline text to show under the label, or null. dimmed rows belong to
// a feature that is off: drawn faded but still editable
public record Row(RowKey key, Rect rect, RowLayout layout, String description, boolean dimmed, float hoverT) {
    public Row withRect(Rect next) {
        return new Row(key, next, layout, description, dimmed, hoverT);
    }

    public Row withLayout(RowLayout next) {
        return new Row(key, rect, next, description, dimmed, hoverT);
    }
}
