package dev.aether.ui.gui.overlay;

import dev.aether.ui.gui.Icon;
import dev.aether.ui.gui.Rect;

import java.util.List;

// a small menu of actions, such as a row's reset / pin / copy menu, opened under its anchor
public record MenuRequest(Object anchorId, Rect anchor, List<Item> items) {
    public MenuRequest {
        items = List.copyOf(items);
    }

    public static MenuRequest of(Object anchorId, Rect anchor, List<Item> items) {
        return new MenuRequest(anchorId, anchor, items);
    }

    // separator draws a rule above this item; hint is right-aligned text such as a shortcut, or null
    public record Item(String label, Icon icon, Runnable action, boolean enabled, boolean destructive,
                       boolean separator, String hint) {
        public static Item of(String label, Runnable action) {
            return new Item(label, null, action, true, false, false, null);
        }

        public Item withEnabled(boolean value) {
            return new Item(label, icon, action, value, destructive, separator, hint);
        }

        public Item asDestructive() {
            return new Item(label, icon, action, enabled, true, separator, hint);
        }

        public Item withSeparator() {
            return new Item(label, icon, action, enabled, destructive, true, hint);
        }

        public Item withIcon(Icon value) {
            return new Item(label, value, action, enabled, destructive, separator, hint);
        }

        public Item withHint(String value) {
            return new Item(label, icon, action, enabled, destructive, separator, value);
        }
    }
}
