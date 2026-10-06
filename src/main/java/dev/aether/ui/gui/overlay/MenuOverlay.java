package dev.aether.ui.gui.overlay;

import dev.aether.ui.gui.Cursor;
import dev.aether.ui.gui.HitHandler;
import dev.aether.ui.gui.KeyInput;
import dev.aether.ui.gui.Rect;
import dev.aether.ui.gui.control.Part;
import dev.aether.ui.gui.control.UiContext;
import dev.aether.ui.gui.skin.FontRole;
import dev.aether.ui.gui.skin.SkinContext;
import org.lwjgl.glfw.GLFW;

// a small action menu under its anchor, right-aligned to it (row menus open from a button at the right edge)
final class MenuOverlay extends Popup {
    private static final float PAD = 5f;
    private static final float SEPARATOR = 9f;

    private final MenuRequest request;
    private UiContext last;
    private int keyboardIndex = -1;

    MenuOverlay(MenuRequest request) {
        this.request = request;
    }

    @Override
    public boolean alive(UiContext ui) {
        return request.anchorId() == null || ui.overlays().anchor(request.anchorId()) != null;
    }

    @Override
    public void render(UiContext ui) {
        last = ui;
        SkinContext sc = ui.sc();
        float rowH = ui.metrics().menuRowHeight();
        float widest = 0f;
        float height = PAD * 2f;
        boolean icons = false;
        for (MenuRequest.Item item : request.items()) {
            float hint = item.hint() == null ? 0f : ui.skin().textWidth(sc, FontRole.CAPTION, item.hint()) + 16f;
            widest = Math.max(widest, ui.skin().textWidth(sc, FontRole.BODY, item.label()) + hint);
            icons |= item.icon() != null;
            height += rowH + (item.separator() ? SEPARATOR : 0f);
        }
        float width = Math.max(160f, widest + 28f + (icons ? ui.metrics().iconSize() + 8f : 0f));
        Rect anchor = request.anchorId() == null ? request.anchor() : ui.overlays().anchor(request.anchorId());
        if (anchor == null) {
            anchor = request.anchor();
        }
        Rect panel = place(ui.canvas().bounds(), anchor, width, height, 4f, true);
        bounds = panel;
        float t = openProgress(ui);
        ui.canvas().save();
        ui.canvas().alpha(t);
        ui.canvas().translate(0f, (1f - t) * (flippedUp(panel, anchor) ? 4f : -4f));
        ui.skin().popover(sc, panel, t);
        ui.hits().block(panel);
        float y = panel.y() + PAD;
        for (int i = 0; i < request.items().size(); i++) {
            MenuRequest.Item item = request.items().get(i);
            if (item.separator()) {
                ui.skin().menuSeparator(sc, new Rect(panel.x(), y, panel.w(), SEPARATOR));
                y += SEPARATOR;
            }
            Rect row = new Rect(panel.x() + PAD, y, panel.w() - PAD * 2f, rowH);
            Object rowId = Part.of(this, "item", i);
            float hoverT = ui.anim().hover(Part.of(rowId, "hover"), ui.hits().hovered(rowId) || i == keyboardIndex);
            ui.skin().menuRow(sc, row, item.label(), item.icon(), false, hoverT, item.enabled(), item.destructive());
            if (item.hint() != null) {
                ui.skin().textRight(sc, FontRole.CAPTION, item.hint(), row.inset(0f, 0f, 10f, 0f), ui.palette().textMuted());
            }
            if (item.enabled()) {
                int index = i;
                ui.hits().add(rowId, row, HitHandler.click(() -> run(index)), Cursor.HAND);
            }
            y += rowH;
        }
        ui.canvas().restore();
    }

    private void run(int index) {
        MenuRequest.Item item = request.items().get(index);
        if (last != null) {
            last.overlays().close(this);
        }
        if (item.enabled() && item.action() != null) {
            item.action().run();
        }
    }

    @Override
    public boolean key(KeyInput k) {
        int size = request.items().size();
        if (size == 0) {
            return false;
        }
        switch (k.key()) {
            case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_UP -> {
                int step = k.is(GLFW.GLFW_KEY_DOWN) ? 1 : -1;
                int next = keyboardIndex;
                for (int tries = 0; tries < size; tries++) {
                    next = next < 0 ? (step > 0 ? 0 : size - 1) : Math.floorMod(next + step, size);
                    if (request.items().get(next).enabled()) {
                        break;
                    }
                }
                keyboardIndex = next;
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
                if (keyboardIndex >= 0) {
                    run(keyboardIndex);
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }
}
