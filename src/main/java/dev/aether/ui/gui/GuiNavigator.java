package dev.aether.ui.gui;

import dev.aether.ui.MainGUIRegistry;

// navigation, search and the global shortcuts plug into the view here; every hook does nothing by default
public interface GuiNavigator {
    GuiNavigator NONE = new GuiNavigator() {
    };

    // null restores the last location of this session
    default void open(LaunchRequest launch) {
    }

    // remember the location for the next open
    default void close() {
    }

    // the registry was rebuilt (language, profile load): find the location and history again by stable id.
    // also called on the first frame with the snapshot the view starts from
    default void snapshotChanged(MainGUIRegistry.Snapshot snapshot) {
    }

    // escape, once no editor or overlay took it: true when a search query was cleared
    default boolean clearSearch() {
        return false;
    }

    // escape after that: module to category to home; false at the top, which closes the screen
    default boolean up() {
        return false;
    }

    // mouse 4, alt+left, or backspace with no editor focused
    default boolean back() {
        return false;
    }

    // keys no capture, overlay, editor or style took, e.g. ctrl+f, ctrl+z, ctrl+enter
    default boolean shortcut(GuiFrame frame, KeyInput key) {
        return false;
    }

    // typed text nothing took; '/' arrives here on every keyboard layout
    default boolean typed(GuiFrame frame, String chars) {
        return false;
    }
}
