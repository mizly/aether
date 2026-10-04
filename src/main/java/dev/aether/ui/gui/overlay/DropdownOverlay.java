package dev.aether.ui.gui.overlay;

import dev.aether.ui.gui.Rect;
import dev.aether.ui.gui.control.UiContext;

final class DropdownOverlay implements Overlay {
    private final DropdownRequest request;

    DropdownOverlay(DropdownRequest request) {
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
