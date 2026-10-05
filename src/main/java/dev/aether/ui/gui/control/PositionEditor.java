package dev.aether.ui.gui.control;

import dev.aether.ui.gui.Cursor;
import dev.aether.ui.gui.HitHandler;
import dev.aether.ui.gui.NumberText;
import dev.aether.ui.gui.Rect;
import dev.aether.ui.gui.skin.ButtonKind;
import dev.aether.ui.gui.skin.FontRole;
import dev.aether.ui.settings.PositionSetting;
import dev.aether.util.AetherLang;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

// x / y / z number fields, Capture, the setting's extra buttons and the Highlight switch, flowing onto a
// second line when the row is narrow
public final class PositionEditor {
    private static final float GAP = 6f;
    private static final float FIELD_W = 78f;

    private PositionEditor() {
    }

    private enum Kind { X, Y, Z, CAPTURE, EXTRA, HIGHLIGHT }

    private record Item(Kind kind, int index, float width) {
    }

    private record Placed(Item item, Rect rect) {
    }

    public static float height(UiContext ui, PositionSetting s, float width) {
        List<Placed> placed = layout(ui, s, new Rect(0f, 0f, width, 0f));
        float bottom = 0f;
        for (Placed p : placed) {
            bottom = Math.max(bottom, p.rect.bottom());
        }
        return bottom;
    }

    public static void render(UiContext ui, Object id, Rect r, PositionSetting s, boolean enabled) {
        for (Placed placed : layout(ui, s, r)) {
            Rect rect = placed.rect;
            switch (placed.item.kind) {
                case X -> axis(ui, Part.of(id, "x"), rect, "X", s::getX, s::setX, enabled);
                case Y -> axis(ui, Part.of(id, "y"), rect, "Y", s::getY, s::setY, enabled);
                case Z -> axis(ui, Part.of(id, "z"), rect, "Z", s::getZ, s::setZ, enabled);
                case CAPTURE -> Button.render(ui, Part.of(id, "capture"), rect, AetherLang.localize("Capture"),
                        ButtonKind.SECONDARY, null, enabled, s::capture);
                case EXTRA -> {
                    PositionSetting.ActionButton action = s.getActionButtons().get(placed.item.index);
                    Button.render(ui, Part.of(id, "extra", placed.item.index), rect, AetherLang.localize(action.label()),
                            ButtonKind.SECONDARY, null, enabled && action.isEnabled(), action::execute);
                }
                case HIGHLIGHT -> highlight(ui, Part.of(id, "highlight"), rect, s, enabled);
            }
        }
    }

    private static void axis(UiContext ui, Object id, Rect r, String axis, DoubleSupplier get, DoubleConsumer set,
                             boolean enabled) {
        float labelW = ui.skin().textWidth(ui.sc(), FontRole.CAPTION, axis) + 8f;
        TextField.render(ui, id, r, new TextField.Model() {
            @Override
            public String text() {
                return NumberText.format(get.getAsDouble(), 1);
            }

            @Override
            public void commit(String text) {
                NumberText.parse(text).ifPresent(set::accept);
            }

            @Override
            public boolean numeric() {
                return true;
            }

            @Override
            public FontRole font() {
                return FontRole.VALUE;
            }

            @Override
            public boolean centered() {
                return true;
            }

            @Override
            public float insetLeft() {
                return labelW;
            }
        }, enabled);
        if (!ui.editors().focused(id)) {
            ui.skin().text(ui.sc(), FontRole.CAPTION, axis, r.x() + 8f,
                    ui.skin().textTop(ui.sc(), FontRole.CAPTION, r), ui.palette().textMuted());
        }
    }

    private static void highlight(UiContext ui, Object id, Rect r, PositionSetting s, boolean enabled) {
        float box = ui.metrics().checkboxSize();
        Rect check = new Rect(r.x(), r.centerY() - box / 2f, box, box);
        boolean on = s.isHighlighted();
        Runnable flip = () -> s.setHighlighted(!s.isHighlighted());
        float hoverT = enabled ? ControlSupport.hover(ui, id) : 0f;
        float onT = ui.anim().ease(Part.of(id, "on"), on ? 1f : 0f, 120f);
        ui.skin().checkbox(ui.sc(), check, onT, hoverT, enabled);
        ui.skin().textLeft(ui.sc(), FontRole.BODY, AetherLang.localize("Highlight"),
                Rect.ofEdges(check.right() + 7f, r.y(), r.right(), r.bottom()), ui.palette().textSecondary());
        if (enabled) {
            ControlSupport.region(ui, id, r, HitHandler.click(flip), Cursor.HAND);
            ControlSupport.focusable(ui, id, r, flip::run);
            ControlSupport.ring(ui, id, check, 5f);
        }
    }

    private static List<Placed> layout(UiContext ui, PositionSetting s, Rect r) {
        List<Item> items = new ArrayList<>();
        items.add(new Item(Kind.X, 0, FIELD_W));
        items.add(new Item(Kind.Y, 0, FIELD_W));
        items.add(new Item(Kind.Z, 0, FIELD_W));
        items.add(new Item(Kind.CAPTURE, 0, Button.width(ui, AetherLang.localize("Capture"), null)));
        List<PositionSetting.ActionButton> extras = s.getActionButtons();
        for (int i = 0; i < extras.size(); i++) {
            items.add(new Item(Kind.EXTRA, i, Math.max(ui.metrics().controlHeight(),
                    Button.width(ui, AetherLang.localize(extras.get(i).label()), null))));
        }
        float highlightW = ui.metrics().checkboxSize() + 7f
                + ui.skin().textWidth(ui.sc(), FontRole.BODY, AetherLang.localize("Highlight")) + 4f;
        items.add(new Item(Kind.HIGHLIGHT, 0, highlightW));
        float h = ui.metrics().controlHeight();
        List<Placed> placed = new ArrayList<>();
        float x = 0f;
        float y = 0f;
        for (Item item : items) {
            float gap = item.kind == Kind.CAPTURE || item.kind == Kind.HIGHLIGHT ? GAP * 2f : GAP;
            if (x > 0f && x + gap + item.width > r.w()) {
                x = 0f;
                y += h + GAP;
            } else if (x > 0f) {
                x += gap;
            }
            placed.add(new Placed(item, new Rect(r.x() + x, r.y() + y, Math.min(item.width, r.w()), h)));
            x += item.width;
        }
        return placed;
    }
}
