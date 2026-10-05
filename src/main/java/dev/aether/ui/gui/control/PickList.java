package dev.aether.ui.gui.control;

import dev.aether.ui.gui.Icon;
import dev.aether.ui.gui.NumberText;
import dev.aether.ui.gui.Rect;
import dev.aether.ui.gui.skin.FontRole;
import dev.aether.ui.gui.skin.Glyph;
import dev.aether.ui.gui.skin.Surface;
import dev.aether.ui.settings.DropdownListSetting;
import dev.aether.util.AetherLang;

import java.util.ArrayList;
import java.util.List;

// an ordered list picked from fixed options: numbered rows with up, down and remove, and an add field that
// opens the dropdown menu with the options not in the list yet, so duplicates cannot happen
public final class PickList {
    private static final float GAP = 6f;

    private PickList() {
    }

    public interface Model {
        List<String> values();

        // every option, raw; the menu offers the ones not in values()
        List<String> options();

        void add(String value);

        void remove(int index);

        void moveUp(int index);

        void moveDown(int index);

        default Icon icon(String value) {
            return null;
        }

        default String label(String value) {
            return AetherLang.localize(value);
        }
    }

    public static Model of(DropdownListSetting s) {
        return new Model() {
            @Override
            public List<String> values() {
                return s.getValues();
            }

            @Override
            public List<String> options() {
                return s.getAllOptions();
            }

            @Override
            public void add(String value) {
                s.addValue(value);
            }

            @Override
            public void remove(int index) {
                s.removeValue(index);
            }

            @Override
            public void moveUp(int index) {
                s.moveUp(index);
            }

            @Override
            public void moveDown(int index) {
                s.moveDown(index);
            }

            @Override
            public Icon icon(String value) {
                return s.optionIcon(s.getAllOptions().indexOf(value));
            }
        };
    }

    public static List<String> remaining(Model m) {
        List<String> left = new ArrayList<>(m.options());
        left.removeAll(m.values());
        return left;
    }

    public static float height(UiContext ui, Model m) {
        int rows = m.values().size() + 1;
        return rows * ui.metrics().controlHeight() + (rows - 1) * GAP;
    }

    public static void render(UiContext ui, Object id, Rect r, Model m, boolean enabled) {
        List<String> values = m.values();
        float h = ui.metrics().controlHeight();
        float button = h;
        for (int i = 0; i < values.size(); i++) {
            float y = r.y() + i * (h + GAP);
            Rect item = new Rect(r.x(), y, r.w() - (button + GAP) * 3f, h);
            ui.skin().surface(ui.sc(), item, Surface.FIELD, 0f);
            String number = NumberText.format(i + 1, 0) + ".";
            float numberW = ui.skin().textWidth(ui.sc(), FontRole.VALUE, "00.");
            ui.skin().textRight(ui.sc(), FontRole.VALUE, number, new Rect(item.x() + 6f, y, numberW, h),
                    ui.palette().textMuted());
            float x = item.x() + 12f + numberW;
            Icon icon = m.icon(values.get(i));
            if (icon != null) {
                float size = ui.metrics().iconSize();
                ui.canvas().icon(icon, x, item.centerY() - size / 2f, size,
                        icon instanceof Icon.Item ? 0xFFFFFFFF : ui.palette().textSecondary());
                x += size + 7f;
            }
            ui.skin().textLeft(ui.sc(), FontRole.BODY, m.label(values.get(i)),
                    Rect.ofEdges(x, y, item.right() - 8f, item.bottom()), ui.palette().textValue());
            int index = i;
            float bx = item.right() + GAP;
            Button.glyph(ui, Part.of(id, "up", i), new Rect(bx, y, button, h), Glyph.ARROW_UP, enabled && i > 0,
                    () -> m.moveUp(index));
            Button.glyph(ui, Part.of(id, "down", i), new Rect(bx + button + GAP, y, button, h), Glyph.ARROW_DOWN,
                    enabled && i < values.size() - 1, () -> m.moveDown(index));
            Button.glyph(ui, Part.of(id, "remove", i), new Rect(bx + (button + GAP) * 2f, y, button, h), Glyph.MINUS,
                    enabled, () -> m.remove(index));
        }
        float y = r.y() + values.size() * (h + GAP);
        List<String> left = remaining(m);
        Rect add = new Rect(r.x(), y, r.w() - (button + GAP) * 3f, h);
        DropdownField.render(ui, Part.of(id, "add"), add, new DropdownField.Model() {
            @Override
            public List<String> options() {
                return remaining(m).stream().map(m::label).toList();
            }

            @Override
            public int selected() {
                return -1;
            }

            @Override
            public void pick(int index) {
                List<String> now = remaining(m);
                if (index >= 0 && index < now.size()) {
                    m.add(now.get(index));
                }
            }

            @Override
            public Icon icon(int index) {
                List<String> now = remaining(m);
                return index >= 0 && index < now.size() ? m.icon(now.get(index)) : null;
            }

            @Override
            public String placeholder() {
                return left.isEmpty() ? AetherLang.localize("All added") : AetherLang.localize("Add…");
            }
        }, enabled && !left.isEmpty());
    }
}
