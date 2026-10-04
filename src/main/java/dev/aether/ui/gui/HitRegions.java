package dev.aether.ui.gui;

import java.util.ArrayList;
import java.util.List;

// regions registered while drawing, mapped through the canvas transform and clip; input routes against the
// last finished frame, topmost first. register stable layout rects under ids built from stable keys
public final class HitRegions {
    private static final Object BLOCKER = new Object();

    private final GuiCanvas canvas;
    private final boolean inert;
    private List<Region> building = new ArrayList<>();
    private List<Region> routing = List.of();
    private Region hovered;
    private Region captured;
    private int capturedButton;
    private HitRegions inertView;

    public HitRegions(GuiCanvas canvas) {
        this(canvas, false);
    }

    private HitRegions(GuiCanvas canvas, boolean inert) {
        this.canvas = canvas;
        this.inert = inert;
    }

    // registers nothing and reports no hover, for thumbnails and other previews
    public HitRegions inert() {
        if (inert) {
            return this;
        }
        if (inertView == null) {
            inertView = new HitRegions(canvas, true);
        }
        return inertView;
    }

    // hover comes from the previous frame's regions against the pointer now; a drag keeps its region hovered
    public void begin(float mouseX, float mouseY) {
        hovered = captured != null ? captured : topmostAt(mouseX, mouseY);
        building = new ArrayList<>(routing.size());
    }

    public void end() {
        routing = building;
        building = new ArrayList<>();
    }

    public void add(Object id, Rect local, HitHandler handler) {
        add(id, local, handler, Cursor.DEFAULT);
    }

    public void add(Object id, Rect local, HitHandler handler, Cursor cursor) {
        if (!inert) {
            register(id, local, handler, cursor);
        }
    }

    // swallows press, scroll and hover beneath it, for opaque panels and modal backdrops
    public void block(Rect local) {
        if (!inert) {
            register(BLOCKER, local, null, Cursor.DEFAULT);
        }
    }

    public boolean hovered(Object id) {
        return hovered != null && hovered.handler != null && hovered.id.equals(id);
    }

    public boolean active(Object id) {
        return captured != null && captured.id.equals(id);
    }

    public boolean captured() {
        return captured != null;
    }

    public Cursor cursor() {
        return hovered == null || hovered.handler == null ? Cursor.DEFAULT : hovered.cursor;
    }

    public HitHandler hoveredHandler() {
        return hovered == null ? null : hovered.handler;
    }

    // the region a press at this point would reach first, or null for a blocker or empty space
    public Object idAt(float x, float y) {
        Region top = topmostAt(x, y);
        return top == null || top.handler == null ? null : top.id;
    }

    public boolean press(PointerInput in) {
        if (inert) {
            return false;
        }
        if (captured != null) {
            return true;
        }
        for (int i = routing.size() - 1; i >= 0; i--) {
            Region region = routing.get(i);
            if (!region.root.contains(in.x(), in.y())) {
                continue;
            }
            if (region.handler == null) {
                return true;
            }
            if (region.handler.press(event(region, in.x(), in.y(), in.button(), in.mods(), in.clicks()))) {
                captured = region;
                capturedButton = in.button();
                return true;
            }
        }
        return false;
    }

    public boolean drag(PointerInput in) {
        if (captured == null) {
            return false;
        }
        captured.handler.drag(event(captured, in.x(), in.y(), in.button(), in.mods(), in.clicks()));
        return true;
    }

    public boolean release(PointerInput in) {
        if (captured == null || in.button() != capturedButton) {
            return false;
        }
        Region region = captured;
        captured = null;
        region.handler.release(event(region, in.x(), in.y(), in.button(), in.mods(), in.clicks()));
        return true;
    }

    public boolean scroll(float x, float y, double dy) {
        if (inert) {
            return false;
        }
        for (int i = routing.size() - 1; i >= 0; i--) {
            Region region = routing.get(i);
            if (!region.root.contains(x, y)) {
                continue;
            }
            if (region.handler == null || region.handler.scroll(event(region, x, y, -1, 0, 0), dy)) {
                return true;
            }
        }
        return false;
    }

    // drops a drag without a release, e.g. when the registry rebuilds the objects it was editing
    public void cancelCapture() {
        captured = null;
    }

    private void register(Object id, Rect local, HitHandler handler, Cursor cursor) {
        Rect mapped = canvas.toRoot(local);
        Rect root = mapped.intersect(canvas.rootClip());
        if (!root.isEmpty()) {
            building.add(new Region(id, root, mapped, local, canvas.scaleX(), canvas.scaleY(), handler, cursor));
        }
    }

    private Region topmostAt(float x, float y) {
        for (int i = routing.size() - 1; i >= 0; i--) {
            if (routing.get(i).root.contains(x, y)) {
                return routing.get(i);
            }
        }
        return null;
    }

    private static PointerEvent event(Region region, float x, float y, int button, int mods, int clicks) {
        float localX = region.local.x() + (x - region.mapped.x()) / region.sx;
        float localY = region.local.y() + (y - region.mapped.y()) / region.sy;
        return new PointerEvent(x, y, localX, localY, button, mods, clicks, region.local);
    }

    // root is clipped and used for hit tests; mapped is the unclipped rect, kept to recover local coordinates
    private record Region(Object id, Rect root, Rect mapped, Rect local, float sx, float sy,
                          HitHandler handler, Cursor cursor) {
    }
}
