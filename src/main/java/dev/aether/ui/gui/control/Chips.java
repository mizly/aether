package dev.aether.ui.gui.control;

import dev.aether.ui.gui.Cursor;
import dev.aether.ui.gui.HitHandler;
import dev.aether.ui.gui.Icon;
import dev.aether.ui.gui.Rect;
import dev.aether.ui.settings.MultiDropdownSetting;

import java.util.ArrayList;
import java.util.List;

// multi-select chips flowing over as many lines as they need; chip widths are measured, so the drawn chip
// and its hit box always agree
public final class Chips {
    private static final float GAP = 6f;

    private Chips() {
    }

    public interface Model {
        List<String> options();

        boolean selected(int index);

        void toggle(int index);

        default Icon icon(int index) {
            return null;
        }
    }

    public static Model of(MultiDropdownSetting s) {
        return new Model() {
            @Override
            public List<String> options() {
                return s.getOptions();
            }

            @Override
            public boolean selected(int index) {
                return s.isSelected(index);
            }

            @Override
            public void toggle(int index) {
                s.toggleOption(index);
            }

            @Override
            public Icon icon(int index) {
                return s.optionIcon(index);
            }
        };
    }

    // bit n of a mask stands for option n, like MultiDropdownSetting stores it
    public static int toggled(int mask, int index) {
        return mask ^ (1 << index);
    }

    public static boolean isSet(int mask, int index) {
        return (mask & (1 << index)) != 0;
    }

    // chip rects relative to (0, 0), wrapped at width
    public static List<Rect> layout(UiContext ui, Model m, float width) {
        List<Rect> rects = new ArrayList<>();
        float h = ui.metrics().chipHeight();
        float x = 0f;
        float y = 0f;
        for (int i = 0; i < m.options().size(); i++) {
            float w = Math.min(width, ui.skin().chipWidth(ui.sc(), m.options().get(i), m.icon(i)));
            if (x > 0f && x + w > width) {
                x = 0f;
                y += h + GAP;
            }
            rects.add(new Rect(x, y, w, h));
            x += w + GAP;
        }
        return rects;
    }

    public static float height(UiContext ui, Model m, float width) {
        List<Rect> rects = layout(ui, m, width);
        return rects.isEmpty() ? 0f : rects.getLast().bottom();
    }

    public static void render(UiContext ui, Object id, Rect r, Model m, boolean enabled) {
        List<Rect> rects = layout(ui, m, r.w());
        for (int i = 0; i < rects.size(); i++) {
            Rect chip = rects.get(i).offset(r.x(), r.y());
            Object chipId = Part.of(id, "chip", i);
            float hoverT = enabled ? ControlSupport.hover(ui, chipId) : 0f;
            ui.skin().chip(ui.sc(), chip, m.options().get(i), m.icon(i), m.selected(i), hoverT, enabled);
            if (!enabled) {
                continue;
            }
            int index = i;
            Runnable flip = () -> m.toggle(index);
            ControlSupport.region(ui, chipId, chip, HitHandler.click(flip), Cursor.HAND);
            ControlSupport.focusable(ui, chipId, chip, flip::run);
            ControlSupport.ring(ui, chipId, chip, chip.h() / 2f);
        }
    }
}
