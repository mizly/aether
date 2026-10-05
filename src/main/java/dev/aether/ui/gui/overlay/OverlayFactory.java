package dev.aether.ui.gui.overlay;

// builds the overlays a style shows (GuiStyle.overlays()). every method defaults to the shared reference
// overlays, so a style overrides only what it restyles and methods added later break nothing. a method
// may return null, which opens nothing
public interface OverlayFactory {
    OverlayFactory DEFAULT = new DefaultOverlays();

    default Overlay dropdown(DropdownRequest request) {
        return DEFAULT.dropdown(request);
    }

    default Overlay colorPicker(ColorRequest request) {
        return DEFAULT.colorPicker(request);
    }

    default Overlay confirm(ConfirmRequest request) {
        return DEFAULT.confirm(request);
    }

    default Overlay contextMenu(MenuRequest request) {
        return DEFAULT.contextMenu(request);
    }

    default Overlay search(SearchRequest request) {
        return DEFAULT.search(request);
    }

    default Overlay plotPicker(PlotRequest request) {
        return DEFAULT.plotPicker(request);
    }
}
