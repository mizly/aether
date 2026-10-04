package dev.aether.ui.gui;

import java.util.ArrayList;
import java.util.List;

// overlays above the page, each with its own editor slot; closing a layer commits its editor first
public final class OverlayStack {
    // what a press does before it reaches hit regions
    public enum PressRoute { PASS, INSIDE, SWALLOW }

    private final List<Layer> layers = new ArrayList<>();

    public void push(Overlay overlay) {
        layers.add(new Layer(overlay, new EditorSlot()));
    }

    public boolean isEmpty() {
        return layers.isEmpty();
    }

    public int size() {
        return layers.size();
    }

    public Overlay top() {
        return layers.isEmpty() ? null : layers.getLast().overlay;
    }

    public EditorSlot topEditors() {
        return layers.isEmpty() ? null : layers.getLast().editors;
    }

    public void pop() {
        if (!layers.isEmpty()) {
            close(layers.getLast());
        }
    }

    public void remove(Overlay overlay) {
        for (Layer layer : List.copyOf(layers)) {
            if (layer.overlay == overlay) {
                close(layer);
            }
        }
    }

    public void closeAll() {
        while (!layers.isEmpty()) {
            pop();
        }
    }

    // drops overlays that are no longer alive, then draws bottom to top, each with its own editor slot
    public void render(GuiFrame frame) {
        for (Layer layer : List.copyOf(layers)) {
            if (!layer.overlay.alive(frame)) {
                close(layer);
            }
        }
        for (Layer layer : List.copyOf(layers)) {
            if (layer.overlay.modal()) {
                frame.hits().block(layer.overlay.bounds());
            }
            layer.overlay.render(frame.withEditors(layer.editors));
        }
    }

    // a press outside the top modal overlay closes it and is swallowed, unless that overlay lets it through
    public PressRoute routePress(float x, float y) {
        Overlay top = top();
        if (top == null) {
            return PressRoute.PASS;
        }
        if (top.bounds().contains(x, y)) {
            return PressRoute.INSIDE;
        }
        if (!top.modal()) {
            return PressRoute.PASS;
        }
        boolean through = top.outsidePressPassesThrough();
        pop();
        return through ? PressRoute.PASS : PressRoute.SWALLOW;
    }

    // topmost first: the layer's editor, then the overlay; a modal layer keeps keys from everything below
    public boolean key(KeyInput key) {
        for (int i = layers.size() - 1; i >= 0; i--) {
            Layer layer = layers.get(i);
            FocusedEditor editor = layer.editors.focused();
            if (editor != null) {
                FocusedEditor.Result result = editor.key(key);
                if (result == FocusedEditor.Result.DONE) {
                    layer.editors.release();
                }
                if (result != FocusedEditor.Result.IGNORED) {
                    return true;
                }
            }
            if (layer.overlay.key(key) || layer.overlay.modal()) {
                return true;
            }
        }
        return false;
    }

    public boolean chars(String chars) {
        for (int i = layers.size() - 1; i >= 0; i--) {
            Layer layer = layers.get(i);
            FocusedEditor editor = layer.editors.focused();
            if (editor != null && editor.chars(chars)) {
                return true;
            }
            if (layer.overlay.chars(chars) || layer.overlay.modal()) {
                return true;
            }
        }
        return false;
    }

    private void close(Layer layer) {
        layers.remove(layer);
        layer.editors.commit();
        layer.overlay.onClose();
    }

    private record Layer(Overlay overlay, EditorSlot editors) {
    }
}
