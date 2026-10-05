package dev.aether.ui.gui.control;

import dev.aether.ui.gui.Cursor;
import dev.aether.ui.gui.FocusHandler;
import dev.aether.ui.gui.HitHandler;
import dev.aether.ui.gui.Icon;
import dev.aether.ui.gui.Rect;
import dev.aether.ui.gui.overlay.DropdownRequest;
import dev.aether.ui.gui.overlay.Overlay;
import dev.aether.ui.settings.DropdownSetting;
import dev.aether.util.AetherLang;

import java.util.ArrayList;
import java.util.List;

// a closed dropdown that opens the style's dropdown overlay. the value changes only on a pick from the menu;
// keyboard cycling commits for ordinary dropdowns, but confirmChange dropdowns (destructive setters) only
// ever open the menu, so no intermediate option is written
public final class DropdownField {
    private DropdownField() {
    }

    public interface Model {
        List<String> options();

        // -1 or anything out of range means the stored value matches no option
        int selected();

        void pick(int index);

        default Icon icon(int index) {
            return null;
        }

        default String title() {
            return "";
        }

        default boolean confirmChange() {
            return false;
        }

        // shown muted while nothing is picked, for fields that start empty (an "Add…" picker); null shows
        // the stored value as unknown instead
        default String placeholder() {
            return null;
        }

        // what the field shows when selected() is out of range
        default String unknownLabel() {
            return AetherLang.localize("unknown") + ": " + selected();
        }
    }

    public static Model of(DropdownSetting s) {
        return new Model() {
            @Override
            public List<String> options() {
                return s.getOptions();
            }

            @Override
            public int selected() {
                return s.getSelectedIndex();
            }

            @Override
            public void pick(int index) {
                s.setSelectedIndex(index);
            }

            @Override
            public Icon icon(int index) {
                return s.optionIcon(index);
            }

            @Override
            public String title() {
                return s.getName();
            }

            @Override
            public boolean confirmChange() {
                return s.confirmsChange();
            }
        };
    }

    public static boolean known(Model m) {
        int index = m.selected();
        return index >= 0 && index < m.options().size();
    }

    public static String valueText(Model m) {
        if (known(m)) {
            return m.options().get(m.selected());
        }
        return m.placeholder() != null ? m.placeholder() : m.unknownLabel();
    }

    // the narrowest field that shows every option without ellipsis, capped by max
    public static float preferredWidth(UiContext ui, Model m, float max) {
        float widest = 0f;
        boolean icons = false;
        for (int i = 0; i < m.options().size(); i++) {
            widest = Math.max(widest, ui.skin().textWidth(ui.sc(), dev.aether.ui.gui.skin.FontRole.BODY, m.options().get(i)));
            icons |= m.icon(i) != null;
        }
        float w = widest + 44f + (icons ? ui.metrics().iconSize() + 7f : 0f);
        return Math.max(Math.min(ui.metrics().dropdownWidth(), max), Math.min(w, max));
    }

    public static boolean isOpen(UiContext ui, Object id) {
        Overlay open = ui.memory().peek(Part.of(id, "menu"));
        return open != null && ui.overlays().isOpen(open);
    }

    public static void open(UiContext ui, Object id, Model m) {
        if (isOpen(ui, id)) {
            return;
        }
        Rect anchor = ui.overlays().anchor(id);
        if (anchor == null) {
            return;
        }
        List<DropdownRequest.Option> options = new ArrayList<>();
        for (int i = 0; i < m.options().size(); i++) {
            options.add(DropdownRequest.Option.of(m.options().get(i), m.icon(i)));
        }
        int selected = known(m) ? m.selected() : -1;
        Overlay overlay = ui.overlayFactory().dropdown(DropdownRequest.of(id, anchor, m.title(), options, selected, m::pick));
        if (overlay != null) {
            ui.memory().put(Part.of(id, "menu"), overlay);
            ui.open(overlay);
        }
    }

    // keyboard cycling: ordinary dropdowns step and commit, confirmChange dropdowns open the menu instead
    public static void cycle(UiContext ui, Object id, Model m, int direction) {
        int size = m.options().size();
        if (size == 0) {
            return;
        }
        if (m.confirmChange()) {
            open(ui, id, m);
            return;
        }
        int from = known(m) ? m.selected() : (direction > 0 ? -1 : size);
        m.pick(Math.floorMod(from + Integer.signum(direction), size));
    }

    public static void render(UiContext ui, Object id, Rect r, Model m, boolean enabled) {
        ui.anchor(id, r);
        boolean open = isOpen(ui, id);
        float hoverT = enabled ? ControlSupport.hover(ui, id) : 0f;
        Icon icon = known(m) ? m.icon(m.selected()) : null;
        boolean placeholder = !known(m) && m.placeholder() != null;
        ui.skin().dropdownField(ui.sc(), r, valueText(m), icon, open, hoverT, enabled, !known(m) && !placeholder,
                placeholder);
        if (!enabled) {
            return;
        }
        ControlSupport.region(ui, id, r, HitHandler.click(() -> open(ui, id, m)), Cursor.HAND);
        ControlSupport.focusable(ui, id, r, new FocusHandler() {
            @Override
            public void activate() {
                open(ui, id, m);
            }

            @Override
            public void adjust(int direction, boolean large) {
                cycle(ui, id, m, direction);
            }
        });
        ControlSupport.ring(ui, id, r, ui.metrics().fieldRadius());
    }

    // icon actions (folder, refresh) drawn right to left ending at x = right; returns the x where they start
    public static float renderIconActions(UiContext ui, Object id, DropdownSetting s, float right, float centerY,
                                          boolean enabled) {
        List<DropdownSetting.IconAction> actions = s.getIconActions();
        float size = ui.metrics().controlHeight();
        float x = right;
        for (int i = actions.size() - 1; i >= 0; i--) {
            DropdownSetting.IconAction action = actions.get(i);
            x -= size;
            Button.icon(ui, Part.of(id, "action", i), new Rect(x, centerY - size / 2f, size, size),
                    Icon.svg(action.iconPath()), enabled, action::execute);
            x -= 6f;
        }
        return x;
    }

    public static float iconActionsWidth(UiContext ui, DropdownSetting s) {
        int n = s.getIconActions().size();
        return n == 0 ? 0f : n * (ui.metrics().controlHeight() + 6f);
    }
}
