package dev.aether.ui.gui.overlay;

// the reference overlays; the command palette and the plot chest belong to the styles and the plot
// picker, so those open nothing here
public class DefaultOverlays implements OverlayFactory {
    @Override
    public Overlay dropdown(DropdownRequest request) {
        return new DropdownOverlay(request);
    }

    @Override
    public Overlay colorPicker(ColorRequest request) {
        return new ColorPickerOverlay(request);
    }

    @Override
    public Overlay confirm(ConfirmRequest request) {
        return new ConfirmOverlay(request);
    }

    @Override
    public Overlay contextMenu(MenuRequest request) {
        return new MenuOverlay(request);
    }

    @Override
    public Overlay search(SearchRequest request) {
        return null;
    }

    @Override
    public Overlay plotPicker(PlotRequest request) {
        return null;
    }
}
