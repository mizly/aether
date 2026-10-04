package dev.aether.ui.gui.overlay;

import dev.aether.ui.gui.HitHandler;
import dev.aether.ui.gui.KeyInput;
import dev.aether.ui.gui.PointerEvent;
import dev.aether.ui.gui.Rect;
import dev.aether.ui.gui.control.UiContext;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// the open overlays, bottom to top. each layer gets a full-screen catcher under it, so a press outside the
// topmost layer closes it and is swallowed, except for layers that pass outside presses through (the
// colour picker commits and lets the press land). anchors are published by controls while the page draws
public final class OverlayStack {
    private final List<Overlay> layers = new ArrayList<>();
    private Map<Object, Rect> anchors = new HashMap<>();
    private Map<Object, Rect> lastAnchors = new HashMap<>();
    private UiContext lastContext;

    public void open(Overlay overlay) {
        if (overlay != null && !layers.contains(overlay)) {
            layers.add(overlay);
        }
    }

    public void close(Overlay overlay) {
        if (layers.remove(overlay)) {
            if (lastContext != null) {
                lastContext.editors().commitOwnedBy(overlay);
            }
            overlay.onClose();
        }
    }

    public void closeTop() {
        if (!layers.isEmpty()) {
            close(layers.getLast());
        }
    }

    public void closeAll() {
        while (!layers.isEmpty()) {
            closeTop();
        }
    }

    public boolean isEmpty() {
        return layers.isEmpty();
    }

    public Overlay top() {
        return layers.isEmpty() ? null : layers.getLast();
    }

    public List<Overlay> layers() {
        return List.copyOf(layers);
    }

    public boolean isOpen(Overlay overlay) {
        return layers.contains(overlay);
    }

    public void publishAnchor(Object id, Rect root) {
        anchors.put(id, root);
    }

    // where the anchor was drawn this frame, or last frame before the page has drawn; null once it is gone
    public Rect anchor(Object id) {
        Rect now = anchors.get(id);
        return now != null ? now : lastAnchors.get(id);
    }

    public boolean anchorDrawn(Object id) {
        return anchors.containsKey(id);
    }

    public void render(UiContext ui) {
        lastContext = ui;
        for (Overlay overlay : List.copyOf(layers)) {
            if (layers.contains(overlay) && !overlay.alive(ui)) {
                close(overlay);
            }
        }
        Rect screen = ui.canvas().bounds();
        int index = 0;
        for (Overlay overlay : List.copyOf(layers)) {
            if (!layers.contains(overlay)) {
                continue;
            }
            ui.hits().add(new Catcher(index++), screen, catcher(overlay));
            UiContext layer = ui.inLayer(overlay);
            ui.canvas().save();
            try {
                overlay.render(layer);
            } finally {
                ui.canvas().restore();
            }
        }
        lastAnchors = anchors;
        anchors = new HashMap<>();
    }

    // escape closes the topmost layer unless it handles escape itself; modal layers swallow every key
    public boolean key(KeyInput k) {
        Overlay top = top();
        if (top == null) {
            return false;
        }
        if (top.key(k)) {
            return true;
        }
        if (k.is(GLFW.GLFW_KEY_ESCAPE)) {
            close(top);
            return true;
        }
        return top.modal();
    }

    public boolean chars(String chars) {
        Overlay top = top();
        if (top == null) {
            return false;
        }
        return top.chars(chars) || top.modal();
    }

    private HitHandler catcher(Overlay overlay) {
        return new HitHandler() {
            @Override
            public boolean press(PointerEvent e) {
                close(overlay);
                return !overlay.passesOutsidePress();
            }

            @Override
            public boolean scroll(PointerEvent e, double dy) {
                return !overlay.passesOutsidePress();
            }
        };
    }

    private record Catcher(int index) {
    }
}
