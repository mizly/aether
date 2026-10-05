package dev.aether.ui.orbit.panel;

import dev.aether.ui.gui.Argb;
import dev.aether.ui.gui.Cursor;
import dev.aether.ui.gui.GuiCanvas;
import dev.aether.ui.gui.HitHandler;
import dev.aether.ui.gui.Icon;
import dev.aether.ui.gui.KeyInput;
import dev.aether.ui.gui.Palette;
import dev.aether.ui.gui.PointerEvent;
import dev.aether.ui.gui.Rect;
import dev.aether.ui.gui.ScrollState;
import dev.aether.ui.settings.ColorSetting;
import dev.aether.ui.settings.DropdownListSetting;
import dev.aether.ui.settings.DropdownSetting;
import dev.aether.ui.settings.MultiDropdownSetting;
import dev.aether.util.AetherLang;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;

import static dev.aether.ui.orbit.panel.PanelPaint.MEDIUM;
import static dev.aether.ui.orbit.panel.PanelPaint.MONO;
import static dev.aether.ui.orbit.panel.PanelPaint.REGULAR;
import static dev.aether.ui.orbit.panel.PanelPaint.SEMIBOLD;

// anchored glass menus (dropdowns, multi-selects, pickers, the profile list) and the colour picker
final class PanelOverlays {
    private static final float ROW_H = 32f;
    private static final int MAX_ROWS = 9;
    private static final int FILTER_AFTER = 12;

    private record Menu(Object key, Rect anchor, List<String> options, IntPredicate selected, IntFunction<Icon> icons,
                        IntConsumer pick, boolean closeOnPick, String empty) {
    }

    private record ColorEdit(Object key, Rect anchor, ColorSetting setting) {
    }

    private final PanelStyle style;
    private final ScrollState menuScroll = new ScrollState();
    private Menu menu;
    private ColorEdit color;
    private String filter = "";
    private int highlighted = -1;
    private int serial;
    private float hue;
    private float saturation;
    private float value;
    private float alpha;

    PanelOverlays(PanelStyle style) {
        this.style = style;
    }

    boolean isOpen() {
        return menu != null || color != null;
    }

    boolean openFor(Object key) {
        return (menu != null && menu.key().equals(key)) || (color != null && color.key().equals(key));
    }

    void close() {
        menu = null;
        color = null;
        filter = "";
        highlighted = -1;
    }

    // -- openers --------------------------------------------------------------------

    void openDropdown(Object key, DropdownSetting setting, Rect anchor) {
        List<String> options = setting.getOptions();
        open(new Menu(key, fieldAnchor(anchor), options, i -> i == setting.getSelectedIndex(), setting::optionIcon,
                setting::setSelectedIndex, true, AetherLang.localize("No options")));
        highlighted = setting.getSelectedIndex();
    }

    void openMulti(Object key, MultiDropdownSetting setting, Rect anchor) {
        open(new Menu(key, anchor, setting.getOptions(), setting::isSelected, setting::optionIcon,
                setting::toggleOption, false, AetherLang.localize("No options")));
    }

    void openAddPicker(Object key, DropdownListSetting setting, Rect anchor) {
        List<String> all = setting.getAllOptions();
        List<String> shown = all.stream().map(AetherLang::localize).toList();
        open(new Menu(key, anchor, shown, i -> setting.getValues().contains(all.get(i)), setting::optionIcon, i -> {
            List<String> values = setting.getValues();
            int existing = values.indexOf(all.get(i));
            if (existing >= 0) {
                setting.removeValue(existing);
            } else {
                setting.addValue(all.get(i));
            }
        }, false, AetherLang.localize("No options")));
    }

    void openList(Object key, Rect anchor, List<String> options, int selected, String empty, IntConsumer pick,
                  boolean closeOnPick) {
        open(new Menu(key, anchor, options, i -> i == selected, i -> null, pick, closeOnPick, empty));
        highlighted = selected;
    }

    void openColor(Object key, ColorSetting setting, Rect anchor) {
        close();
        color = new ColorEdit(key, fieldAnchor(anchor), setting);
        float[] hsv = toHsv(setting.getValue());
        hue = hsv[0];
        saturation = hsv[1];
        value = hsv[2];
        alpha = Argb.alpha(setting.getValue()) / 255f;
        serial++;
    }

    private void open(Menu next) {
        close();
        menu = next;
        menuScroll.jumpTo(0f);
        serial++;
    }

    // rows hand over their whole rect; menus hang under the control at the row's right edge
    private static Rect fieldAnchor(Rect row) {
        float w = Math.min(220f, row.w() * 0.45f);
        return new Rect(row.right() - 18f - w, row.y() + 6f, w, row.h() - 12f);
    }

    // -- render ---------------------------------------------------------------------

    void render(PanelFrame f) {
        if (menu != null) {
            renderMenu(f, menu);
        } else if (color != null) {
            renderColor(f, color);
        }
    }

    private void renderMenu(PanelFrame f, Menu m) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        List<Integer> visible = filtered(m);
        boolean filtering = m.options().size() > FILTER_AFTER;
        float widest = 0f;
        for (String option : m.options()) {
            widest = Math.max(widest, c.textWidth(MEDIUM, 12.5f, option));
        }
        float w = Math.max(m.anchor().w(), Math.min(360f, widest + 64f));
        float listH = Math.max(1, Math.min(MAX_ROWS, visible.size())) * ROW_H;
        float h = listH + 12f + (filtering ? 40f : 0f);
        Rect view = f.viewport();
        float x = Math.min(view.right() - w - 8f, Math.max(8f, m.anchor().right() - w));
        boolean below = m.anchor().bottom() + 6f + h <= view.bottom() - 8f || m.anchor().y() - 6f - h < 8f;
        float y = below ? m.anchor().bottom() + 6f : m.anchor().y() - 6f - h;
        Rect panel = new Rect(x, y, w, h);
        float t = f.anim().stagger("aurora.menu.in." + serial, 0);

        f.hits().add("aurora.menu.backdrop", view, new HitHandler() {
            @Override
            public boolean press(PointerEvent e) {
                close();
                return true;
            }

            @Override
            public boolean scroll(PointerEvent e, double dy) {
                return true;
            }
        });
        c.save();
        c.alpha(t);
        c.translate(0f, (1f - t) * (below ? -4f : 4f));
        glass(c, p, panel, 12f);
        f.hits().block(panel);
        float listTop = panel.y() + 6f;
        if (filtering) {
            Rect field = new Rect(panel.x() + 6f, panel.y() + 6f, panel.w() - 12f, 32f);
            c.roundedRect(field, 8f, Argb.withAlpha(p.text(), 0.06f));
            PanelPaint.icon(c, PanelSidebar.SEARCH, field.x() + 15f, field.centerY(), 13f, p.textMuted());
            String shown = filter.isEmpty() ? AetherLang.localize("Type to filter") : filter;
            PanelPaint.fitText(c, REGULAR, 12.5f, shown, field.x() + 30f, field.centerY(), field.w() - 40f,
                    filter.isEmpty() ? p.textMuted() : p.text());
            if (!filter.isEmpty() && (f.nanos() / 530_000_000L) % 2L == 0L) {
                float cx = field.x() + 30f + c.textWidth(REGULAR, 12.5f, filter) + 1f;
                c.rect(new Rect(cx, field.centerY() - 7f, 1.2f, 14f), p.accent());
            }
            listTop = field.bottom() + 4f;
        }
        Rect list = new Rect(panel.x() + 6f, listTop, panel.w() - 12f, listH);
        menuScroll.tick(f.nanos(), 200f, f.frozen());
        menuScroll.setExtent(visible.size() * ROW_H, list.h());
        f.hits().add("aurora.menu.list", list, new HitHandler() {
            @Override
            public boolean scroll(PointerEvent e, double dy) {
                menuScroll.scrollBy((float) (-dy * ROW_H));
                return true;
            }
        });
        c.save();
        c.clip(list);
        if (visible.isEmpty()) {
            PanelPaint.text(c, REGULAR, 12.5f, filter.isEmpty() ? m.empty() : AetherLang.localize("No matches"),
                    list.x() + 10f, list.y() + ROW_H / 2f, p.textMuted());
        }
        for (int row = 0; row < visible.size(); row++) {
            int option = visible.get(row);
            Rect r = new Rect(list.x(), list.y() + row * ROW_H - menuScroll.offset(), list.w(), ROW_H);
            if (!c.isVisible(r)) {
                continue;
            }
            String id = "aurora.menu.opt." + option;
            boolean hover = f.hits().hovered(id) || option == highlighted;
            boolean selected = m.selected().test(option);
            if (hover) {
                c.roundedRect(r, 8f, Argb.withAlpha(p.text(), 0.08f));
            }
            float tx = r.x() + 10f;
            Icon icon = m.icons().apply(option);
            if (icon != null) {
                PanelPaint.icon(c, icon, tx + 9f, r.centerY(), 18f, p.text());
                tx += 26f;
            }
            PanelPaint.fitText(c, selected ? SEMIBOLD : MEDIUM, 12.5f, m.options().get(option), tx, r.centerY(),
                    r.right() - tx - 28f, selected ? p.text() : p.textSecondary());
            if (selected) {
                PanelPaint.check(c, r.right() - 14f, r.centerY(), 10f, 1.8f, p.accent());
            }
            f.hits().add(id, r, HitHandler.click(() -> {
                m.pick().accept(option);
                if (m.closeOnPick()) {
                    close();
                }
            }), Cursor.HAND);
        }
        c.restore();
        c.restore();
    }

    private List<Integer> filtered(Menu m) {
        List<Integer> out = new ArrayList<>();
        String needle = filter.toLowerCase(Locale.ROOT);
        for (int i = 0; i < m.options().size(); i++) {
            if (needle.isEmpty() || m.options().get(i).toLowerCase(Locale.ROOT).contains(needle)) {
                out.add(i);
            }
        }
        return out;
    }

    private void renderColor(PanelFrame f, ColorEdit edit) {
        GuiCanvas c = f.canvas();
        Palette p = f.palette();
        float w = 256f;
        float h = 268f;
        Rect view = f.viewport();
        float x = Math.min(view.right() - w - 8f, Math.max(8f, edit.anchor().right() - w));
        boolean below = edit.anchor().bottom() + 6f + h <= view.bottom() - 8f;
        float y = below ? edit.anchor().bottom() + 6f : Math.max(8f, edit.anchor().y() - 6f - h);
        Rect panel = new Rect(x, y, w, h);
        float t = f.anim().stagger("aurora.color.in." + serial, 0);
        f.hits().add("aurora.color.backdrop", view, new HitHandler() {
            @Override
            public boolean press(PointerEvent e) {
                close();
                return false;
            }
        });
        c.save();
        c.alpha(t);
        c.translate(0f, (1f - t) * (below ? -4f : 4f));
        glass(c, p, panel, 12f);
        f.hits().block(panel);

        Rect sv = new Rect(panel.x() + 14f, panel.y() + 14f, w - 28f, 140f);
        int pure = fromHsv(hue, 1f, 1f, 1f);
        c.roundedRect(sv, 8f, pure);
        c.horizontalGradient(sv, 8f, 0xFFFFFFFF, 0x00FFFFFF);
        c.verticalGradient(sv, 8f, 0x00000000, 0xFF000000);
        float kx = sv.x() + sv.w() * saturation;
        float ky = sv.y() + sv.h() * (1f - value);
        c.strokeCircle(kx, ky, 6f, 2f, 0xFFFFFFFF);
        c.strokeCircle(kx, ky, 7.5f, 1f, 0x66000000);
        f.hits().add("aurora.color.sv", sv, drag((e, r) -> {
            saturation = clamp((e.localX() - r.x()) / r.w());
            value = 1f - clamp((e.localY() - r.y()) / r.h());
            apply(edit);
        }), Cursor.CROSSHAIR);

        Rect hueBar = new Rect(sv.x(), sv.bottom() + 12f, sv.w(), 12f);
        float segment = hueBar.w() / 6f;
        for (int i = 0; i < 6; i++) {
            c.horizontalGradient(new Rect(hueBar.x() + segment * i, hueBar.y(), segment + 0.5f, hueBar.h()), 0f,
                    fromHsv(i / 6f, 1f, 1f, 1f), fromHsv((i + 1) / 6f, 1f, 1f, 1f));
        }
        c.strokeRect(hueBar, 3f, 1f, Argb.withAlpha(p.text(), 0.15f));
        sliderKnob(c, hueBar.x() + hueBar.w() * hue, hueBar.centerY());
        f.hits().add("aurora.color.hue", hueBar.inset(0f, -4f, 0f, -4f), drag((e, r) -> {
            hue = clamp((e.localX() - r.x()) / r.w());
            apply(edit);
        }), Cursor.HAND);

        Rect alphaBar = new Rect(sv.x(), hueBar.bottom() + 10f, sv.w(), 12f);
        int opaque = fromHsv(hue, saturation, value, 1f);
        c.horizontalGradient(alphaBar, 3f, Argb.withAlpha(opaque, 0f), opaque);
        c.strokeRect(alphaBar, 3f, 1f, Argb.withAlpha(p.text(), 0.15f));
        sliderKnob(c, alphaBar.x() + alphaBar.w() * alpha, alphaBar.centerY());
        f.hits().add("aurora.color.alpha", alphaBar.inset(0f, -4f, 0f, -4f), drag((e, r) -> {
            alpha = clamp((e.localX() - r.x()) / r.w());
            apply(edit);
        }), Cursor.HAND);

        Rect preview = new Rect(sv.x(), alphaBar.bottom() + 14f, 36f, 30f);
        c.roundedRect(preview, 8f, 0xFFFFFFFF);
        c.roundedRect(preview, 8f, edit.setting().getValue());
        c.strokeRect(preview, 8f, 1f, Argb.withAlpha(p.text(), 0.18f));
        Rect hex = new Rect(preview.right() + 10f, preview.y(), sv.right() - preview.right() - 10f, 30f);
        c.roundedRect(hex, 8f, Argb.withAlpha(p.text(), 0.06f));
        PanelPaint.text(c, MONO, 12.5f, String.format(Locale.ROOT, "#%08X", edit.setting().getValue()), hex.x() + 10f,
                hex.centerY(), p.text());
        c.restore();
    }

    private interface DragApply {
        void apply(PointerEvent e, Rect r);
    }

    private static HitHandler drag(DragApply apply) {
        return new HitHandler() {
            @Override
            public boolean press(PointerEvent e) {
                if (e.button() != 0) {
                    return false;
                }
                apply.apply(e, e.pressRect());
                return true;
            }

            @Override
            public void drag(PointerEvent e) {
                apply.apply(e, e.pressRect());
            }
        };
    }

    private void apply(ColorEdit edit) {
        edit.setting().setValue(fromHsv(hue, saturation, value, alpha));
    }

    private static void sliderKnob(GuiCanvas c, float cx, float cy) {
        c.circle(cx, cy, 7f, 0x55000000);
        c.circle(cx, cy, 6f, 0xFFFFFFFF);
    }

    static void glass(GuiCanvas c, Palette p, Rect panel, float radius) {
        c.shadow(panel.offset(0f, 6f), radius, 28f, PanelPaint.shadow(p, 0.9f));
        c.roundedRect(panel, radius, Argb.withAlpha(Argb.mix(p.panel(), p.card(), 0.35f), 0.97f));
        c.strokeRect(panel, radius, 1f, Argb.withAlpha(p.border(), p.light() ? 0.40f : 0.35f));
        c.line(panel.x() + radius, panel.y() + 0.5f, panel.right() - radius, panel.y() + 0.5f, 1f,
                PanelPaint.highlight(p));
    }

    // -- keys --------------------------------------------------------------------------------

    boolean key(KeyInput k) {
        if (k.is(GLFW.GLFW_KEY_ESCAPE)) {
            close();
            return true;
        }
        if (menu == null) {
            return true;
        }
        List<Integer> visible = filtered(menu);
        int at = visible.indexOf(highlighted);
        switch (k.key()) {
            case GLFW.GLFW_KEY_DOWN -> highlighted = visible.isEmpty() ? -1 : visible.get(Math.min(visible.size() - 1, at + 1));
            case GLFW.GLFW_KEY_UP -> highlighted = visible.isEmpty() ? -1 : visible.get(Math.max(0, at < 0 ? 0 : at - 1));
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
                if (highlighted >= 0 && visible.contains(highlighted)) {
                    Menu m = menu;
                    m.pick().accept(highlighted);
                    if (m.closeOnPick()) {
                        close();
                    }
                }
            }
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (!filter.isEmpty()) {
                    filter = filter.substring(0, filter.length() - 1);
                }
            }
            default -> {
            }
        }
        int row = visible.indexOf(highlighted);
        if (row >= 0) {
            menuScroll.ensureVisible(row * ROW_H, row * ROW_H + ROW_H, 0f);
        }
        return true;
    }

    boolean chars(String typed) {
        if (menu != null && menu.options().size() > FILTER_AFTER) {
            filter += typed;
            List<Integer> visible = filtered(menu);
            highlighted = visible.isEmpty() ? -1 : visible.getFirst();
            menuScroll.jumpTo(0f);
        }
        return true;
    }

    // -- colour maths ----------------------------------------------------------------------------

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    static float[] toHsv(int argb) {
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float d = max - min;
        float h = 0f;
        if (d > 0f) {
            if (max == r) {
                h = ((g - b) / d) % 6f;
            } else if (max == g) {
                h = (b - r) / d + 2f;
            } else {
                h = (r - g) / d + 4f;
            }
            h /= 6f;
            if (h < 0f) {
                h += 1f;
            }
        }
        return new float[]{h, max == 0f ? 0f : d / max, max};
    }

    static int fromHsv(float h, float s, float v, float a) {
        float hh = (h % 1f + 1f) % 1f * 6f;
        int sector = (int) Math.floor(hh);
        float fract = hh - sector;
        float p = v * (1f - s);
        float q = v * (1f - s * fract);
        float t = v * (1f - s * (1f - fract));
        float r;
        float g;
        float b;
        switch (sector % 6) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return (Math.round(a * 255f) << 24) | (Math.round(r * 255f) << 16) | (Math.round(g * 255f) << 8)
                | Math.round(b * 255f);
    }
}
