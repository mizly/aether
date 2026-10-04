package dev.aether.ui.gui;

// one complete look of the gui: shell, navigation, pages and widgets. render draws everything below the
// overlays; keyPressed sees keys only when nothing captured them and no editor is focused
public interface GuiStyle {
    String id();

    String displayName();

    void render(GuiFrame frame);

    default Backdrop backdrop() {
        return Backdrop.NONE;
    }

    default boolean keyPressed(GuiFrame frame, KeyInput key) {
        return false;
    }

    // a fixed-scale thumbnail for the style picker, drawn with an inert frame
    default void drawStylePreview(GuiFrame inert, Rect area) {
    }
}
