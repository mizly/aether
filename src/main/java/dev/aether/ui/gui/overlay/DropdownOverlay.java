package dev.aether.ui.gui.overlay;

import dev.aether.ui.gui.Cursor;
import dev.aether.ui.gui.HitHandler;
import dev.aether.ui.gui.KeyInput;
import dev.aether.ui.gui.PointerEvent;
import dev.aether.ui.gui.Rect;
import dev.aether.ui.gui.ScrollState;
import dev.aether.ui.gui.control.EditorFocus;
import dev.aether.ui.gui.control.Part;
import dev.aether.ui.gui.control.TextField;
import dev.aether.ui.gui.control.UiContext;
import dev.aether.ui.gui.skin.FontRole;
import dev.aether.ui.gui.skin.GuiSkin;
import dev.aether.ui.gui.skin.SkinContext;
import dev.aether.util.AetherLang;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// the reference dropdown menu: anchored under its field (above when there is more room there), at least as
// wide as its longest option, scrolling past twelve rows, with a filter field when there are more than twelve
// options. a pick runs the request's callback once; arrows move, enter picks, escape closes
final class DropdownOverlay extends Popup {
    static final int FILTER_THRESHOLD = 12;
    private static final int MAX_ROWS = 12;
    private static final float PAD = 5f;

    private final DropdownRequest request;
    private final ScrollState scroll = new ScrollState();
    private final boolean filterable;
    private String filter = "";
    private int keyboardIndex = -1;
    private boolean scrolledOnce;
    private UiContext last;
    private List<Integer> visible = List.of();

    DropdownOverlay(DropdownRequest request) {
        this.request = request;
        this.filterable = request.options().size() > FILTER_THRESHOLD;
    }

    @Override
    public boolean alive(UiContext ui) {
        return ui.overlays().anchor(request.anchorId()) != null;
    }

    static List<Integer> matching(List<DropdownRequest.Option> options, String filter) {
        String needle = filter.trim().toLowerCase(Locale.ROOT);
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            if (needle.isEmpty() || options.get(i).label().toLowerCase(Locale.ROOT).contains(needle)) {
                indices.add(i);
            }
        }
        return indices;
    }

    @Override
    public void render(UiContext ui) {
        last = ui;
        GuiSkin skin = ui.skin();
        SkinContext sc = ui.sc();
        Rect anchor = ui.overlays().anchor(request.anchorId());
        if (anchor == null) {
            anchor = request.anchor();
        }
        visible = matching(request.options(), filter);
        float rowH = ui.metrics().menuRowHeight();
        float filterH = filterable ? ui.metrics().controlHeight() + PAD : 0f;
        float width = Math.max(anchor.w(), contentWidth(ui));
        int rows = Math.max(1, Math.min(MAX_ROWS, visible.size()));
        float wanted = PAD * 2f + filterH + rows * rowH;
        Rect panel = place(ui.canvas().bounds(), anchor, width, wanted, 4f, false);
        bounds = panel;
        float t = openProgress(ui);
        float slide = (1f - t) * (flippedUp(panel, anchor) ? 4f : -4f);
        ui.canvas().save();
        ui.canvas().alpha(t);
        ui.canvas().translate(0f, slide);
        skin.popover(sc, panel, t);
        ui.hits().block(panel);
        if (filterable) {
            Rect field = new Rect(panel.x() + PAD, panel.y() + PAD, panel.w() - PAD * 2f, ui.metrics().controlHeight());
            Object fieldId = Part.of(this, "filter");
            TextField.Model model = filterModel();
            if (!ui.editors().focused(fieldId) && ui.editors().owner() != this) {
                TextField.focus(ui, fieldId, model, false);
            }
            TextField.render(ui, fieldId, field, model, true);
        }
        Rect list = Rect.ofEdges(panel.x() + PAD, panel.y() + PAD + filterH, panel.right() - PAD, panel.bottom() - PAD);
        scroll.setExtent(visible.size() * rowH, list.h());
        scroll.tick(ui.clock().nanos(), 250f, false);
        if (keyboardIndex >= 0) {
            float top = keyboardIndex * rowH;
            scroll.ensureVisible(top, top + rowH, 0f);
        } else if (!scrolledOnce && request.selected() >= 0) {
            int at = visible.indexOf(request.selected());
            if (at >= 0) {
                scroll.jumpTo(at * rowH - list.h() / 2f + rowH / 2f);
            }
        }
        scrolledOnce = true;
        ui.hits().add(Part.of(this, "list"), list, new HitHandler() {
            @Override
            public boolean scroll(PointerEvent e, double dy) {
                scroll.scrollBy((float) (-dy * rowH));
                return true;
            }
        });
        ui.canvas().save();
        ui.canvas().clip(list);
        if (visible.isEmpty()) {
            skin.textCentered(sc, FontRole.BODY, AetherLang.localize("No matches"), new Rect(list.x(), list.y(), list.w(), rowH),
                    ui.palette().textMuted());
        }
        for (int i = 0; i < visible.size(); i++) {
            float y = list.y() + i * rowH - scroll.offset();
            if (y + rowH < list.y() || y > list.bottom()) {
                continue;
            }
            int option = visible.get(i);
            DropdownRequest.Option entry = request.options().get(option);
            Rect row = new Rect(list.x(), y, list.w(), rowH);
            Object rowId = Part.of(this, "row", option);
            boolean hovered = ui.hits().hovered(rowId) || i == keyboardIndex;
            float hoverT = ui.anim().hover(Part.of(rowId, "hover"), hovered);
            skin.menuRow(sc, row, entry.label(), entry.icon(), option == request.selected(), hoverT, entry.enabled(),
                    false);
            if (entry.enabled()) {
                ui.hits().add(rowId, row, HitHandler.click(() -> pick(option)), Cursor.HAND);
            }
        }
        ui.canvas().restore();
        if (scroll.max() > 0f) {
            Rect track = new Rect(panel.right() - 7f, list.y() + 2f, 5f, list.h() - 4f);
            skin.scrollbar(sc, track, scroll.thumb(track, 18f), 0f, false);
        }
        ui.canvas().restore();
    }

    private float contentWidth(UiContext ui) {
        float widest = 0f;
        boolean icons = false;
        for (DropdownRequest.Option option : request.options()) {
            widest = Math.max(widest, ui.skin().textWidth(ui.sc(), FontRole.BODY, option.label()));
            icons |= option.icon() != null;
        }
        return widest + PAD * 2f + 20f + 26f + (icons ? ui.metrics().iconSize() + 8f : 0f);
    }

    private TextField.Model filterModel() {
        return new TextField.Model() {
            @Override
            public String text() {
                return filter;
            }

            @Override
            public void commit(String text) {
                filter = text;
            }

            @Override
            public void edited(String text) {
                filter = text;
                keyboardIndex = text.isEmpty() ? -1 : 0;
                scroll.jumpTo(0f);
            }

            @Override
            public String placeholder() {
                return AetherLang.localize("Filter…");
            }

            @Override
            public EditorFocus.Escape escape() {
                return EditorFocus.Escape.CANCEL;
            }
        };
    }

    private void pick(int option) {
        if (last != null) {
            last.overlays().close(this);
        }
        if (request.onPick() != null) {
            request.onPick().accept(option);
        }
    }

    @Override
    public boolean key(KeyInput k) {
        int size = visible.size();
        switch (k.key()) {
            case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_UP -> {
                if (size > 0) {
                    int start = keyboardIndex >= 0 ? keyboardIndex : Math.max(-1, visible.indexOf(request.selected()));
                    int step = k.is(GLFW.GLFW_KEY_DOWN) ? 1 : -1;
                    keyboardIndex = start < 0 ? (step > 0 ? 0 : size - 1) : Math.floorMod(start + step, size);
                }
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                int index = keyboardIndex >= 0 ? keyboardIndex : size == 1 ? 0 : -1;
                if (index >= 0 && index < size && request.options().get(visible.get(index)).enabled()) {
                    pick(visible.get(index));
                }
                return true;
            }
            case GLFW.GLFW_KEY_ESCAPE -> {
                if (last != null) {
                    last.overlays().close(this);
                }
                return true;
            }
            default -> {
                return filterable && last != null && last.editors().key(k, last.host().clipboard());
            }
        }
    }

    @Override
    public boolean chars(String chars) {
        return filterable && last != null && last.editors().chars(chars);
    }

    @Override
    public void onClose() {
        if (last != null) {
            last.editors().commitOwnedBy(this);
        }
    }
}
