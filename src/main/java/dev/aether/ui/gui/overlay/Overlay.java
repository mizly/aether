package dev.aether.ui.gui.overlay;

import dev.aether.ui.gui.KeyInput;
import dev.aether.ui.gui.Rect;
import dev.aether.ui.gui.control.UiContext;

// a layer above the page: dropdown menus, the colour picker, dialogs, the command palette, the plot picker.
// OverlayStack registers a catcher under each layer that closes it on an outside press, so render must
// block() its own panel before registering its controls, or presses on empty panel space close it
public interface Overlay {
    // root-space panel as last drawn
    Rect bounds();

    // modal layers dim the page and take every key
    default boolean modal() {
        return false;
    }

    // checked every frame before drawing; false closes the layer, e.g. when its anchor row is gone
    default boolean alive(UiContext ui) {
        return true;
    }

    void render(UiContext ui);

    default boolean key(KeyInput k) {
        return false;
    }

    default boolean chars(String chars) {
        return false;
    }

    default void onClose() {
    }

    // true for the colour picker: an outside press commits it and still reaches what is underneath
    default boolean passesOutsidePress() {
        return false;
    }
}
