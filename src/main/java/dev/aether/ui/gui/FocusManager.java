package dev.aether.ui.gui;

import java.util.ArrayList;
import java.util.List;

// keyboard focus, registered while drawing like hit regions, so focus order is visual order. keys between
// frames move through the last finished frame. register every focusable, including rows culled from drawing,
// or keyboard navigation skips them
public final class FocusManager {
    private final GuiCanvas canvas;
    private final boolean inert;
    private List<Entry> building = new ArrayList<>();
    private List<Entry> order = List.of();
    private Object focusedId;
    private Object revealId;
    private boolean keyboard;
    private FocusManager inertView;

    public FocusManager(GuiCanvas canvas) {
        this(canvas, false);
    }

    private FocusManager(GuiCanvas canvas, boolean inert) {
        this.canvas = canvas;
        this.inert = inert;
    }

    public FocusManager inert() {
        if (inert) {
            return this;
        }
        if (inertView == null) {
            inertView = new FocusManager(canvas, true);
        }
        return inertView;
    }

    public void begin() {
        building = new ArrayList<>(order.size());
    }

    public void end() {
        order = building;
        building = new ArrayList<>();
    }

    // true once, right after this entry gains focus, so its scroll container can bring it into view
    public boolean add(Object id, Rect local, FocusHandler handler) {
        if (inert) {
            return false;
        }
        building.add(new Entry(id, canvas.toRoot(local), handler));
        if (id.equals(revealId)) {
            revealId = null;
            return true;
        }
        return false;
    }

    public boolean focused(Object id) {
        return focusedId != null && focusedId.equals(id);
    }

    // the focus ring only shows while the keyboard is driving
    public boolean ringVisible(Object id) {
        return keyboard && focused(id);
    }

    public Object focusedId() {
        return focusedId;
    }

    public FocusHandler focusedHandler() {
        Entry entry = find(focusedId);
        return entry == null ? null : entry.handler;
    }

    // root-space rect of the focused element in the last frame, to anchor keyboard-driven help and menus
    public Rect focusedRect() {
        Entry entry = find(focusedId);
        return entry == null ? null : entry.root;
    }

    public boolean next() {
        return step(1);
    }

    public boolean prev() {
        return step(-1);
    }

    public void moveTo(Object id) {
        if (!inert) {
            focusedId = id;
            revealId = id;
        }
    }

    public void clear() {
        focusedId = null;
        revealId = null;
    }

    public void pointerUsed() {
        keyboard = false;
    }

    private boolean step(int direction) {
        if (inert || order.isEmpty()) {
            return false;
        }
        int index = -1;
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).id.equals(focusedId)) {
                index = i;
                break;
            }
        }
        int next = index < 0
                ? (direction > 0 ? 0 : order.size() - 1)
                : Math.floorMod(index + direction, order.size());
        focusedId = order.get(next).id;
        revealId = focusedId;
        keyboard = true;
        return true;
    }

    private Entry find(Object id) {
        if (id == null) {
            return null;
        }
        for (Entry entry : order) {
            if (entry.id.equals(id)) {
                return entry;
            }
        }
        return null;
    }

    private record Entry(Object id, Rect root, FocusHandler handler) {
    }
}
