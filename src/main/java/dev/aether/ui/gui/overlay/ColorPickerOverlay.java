package dev.aether.ui.gui.overlay;

import dev.aether.ui.gui.Rect;
import dev.aether.ui.gui.control.UiContext;

final class ColorPickerOverlay implements Overlay {
    private final ColorRequest request;

    ColorPickerOverlay(ColorRequest request) {
        this.request = request;
    }

    @Override
    public Rect bounds() {
        return Rect.EMPTY;
    }

    @Override
    public boolean alive(UiContext ui) {
        return false;
    }

    @Override
    public void render(UiContext ui) {
    }
}
