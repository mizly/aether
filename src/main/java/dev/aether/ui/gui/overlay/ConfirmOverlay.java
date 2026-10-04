package dev.aether.ui.gui.overlay;

import dev.aether.ui.gui.Rect;
import dev.aether.ui.gui.control.UiContext;

final class ConfirmOverlay implements Overlay {
    private final ConfirmRequest request;

    ConfirmOverlay(ConfirmRequest request) {
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
