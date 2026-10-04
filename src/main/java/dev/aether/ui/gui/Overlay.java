package dev.aether.ui.gui;

// a layer above the page: dropdowns, the colour picker, confirms, menus, the command palette.
// a modal overlay blocks its bounds for everything below and closes when pressed outside
public interface Overlay {
    // root-space bounds, for the outside-press rule and the block under modal overlays
    Rect bounds();

    default boolean modal() {
        return true;
    }

    // checked every frame; false closes the overlay, e.g. once its anchor or setting is gone
    default boolean alive(GuiFrame frame) {
        return true;
    }

    void render(GuiFrame frame);

    default boolean key(KeyInput key) {
        return false;
    }

    default boolean chars(String chars) {
        return false;
    }

    default void onClose() {
    }

    // the colour picker applies its colour on an outside press and lets the press through, as it always has
    default boolean outsidePressPassesThrough() {
        return false;
    }
}
