package dev.aether.ui.gui.control;

import dev.aether.ui.gui.Rect;
import dev.aether.ui.gui.skin.Glyph;
import dev.aether.ui.settings.ListSetting;

import java.util.ArrayList;
import java.util.List;

// an editable list of strings: one field per item with + (insert below and focus) and - (remove). an item
// committed blank is removed, and an empty list shows one placeholder row that adds the first item
public final class ListEditor {
    private static final float GAP = 6f;

    private ListEditor() {
    }

    public interface Model {
        List<String> values();

        void set(List<String> values);

        default String placeholder() {
            return "";
        }
    }

    public static Model of(ListSetting s) {
        return new Model() {
            @Override
            public List<String> values() {
                return s.getValues();
            }

            @Override
            public void set(List<String> values) {
                s.setValues(values);
            }

            @Override
            public String placeholder() {
                return s.getPlaceholder();
            }
        };
    }

    // -- list maths ------------------------------------------------------------

    public static List<String> insertAfter(List<String> values, int index) {
        List<String> next = new ArrayList<>(values);
        next.add(Math.max(0, Math.min(next.size(), index + 1)), "");
        return next;
    }

    public static List<String> remove(List<String> values, int index) {
        List<String> next = new ArrayList<>(values);
        if (index >= 0 && index < next.size()) {
            next.remove(index);
        }
        return next;
    }

    // writes an edited item back. the list may have shifted since the edit began (a blank item above was
    // removed), so the item is found by its text at focus time when its index no longer holds it
    public static List<String> commit(List<String> values, int index, String original, String text) {
        List<String> next = new ArrayList<>(values);
        int at = index >= 0 && index < next.size() && next.get(index).equals(original) ? index : next.indexOf(original);
        if (at < 0) {
            return next;
        }
        if (text.isBlank()) {
            next.remove(at);
        } else {
            next.set(at, text);
        }
        return next;
    }

    // -- control ---------------------------------------------------------------

    public static float height(UiContext ui, Model m) {
        int rows = Math.max(1, m.values().size());
        return rows * ui.metrics().controlHeight() + (rows - 1) * GAP;
    }

    public static void render(UiContext ui, Object id, Rect r, Model m, boolean enabled) {
        List<String> values = m.values();
        float h = ui.metrics().controlHeight();
        float button = h;
        Integer pendingFocus = ui.memory().peek(Part.of(id, "focus"));
        if (values.isEmpty()) {
            Rect field = new Rect(r.x(), r.y(), r.w() - (button + GAP) * 2f, h);
            TextField.render(ui, Part.of(id, "item", 0), field, new TextField.Model() {
                @Override
                public String text() {
                    return "";
                }

                @Override
                public void commit(String text) {
                    if (!text.isBlank()) {
                        m.set(List.of(text));
                    }
                }

                @Override
                public String placeholder() {
                    return m.placeholder();
                }
            }, enabled);
            float x = field.right() + GAP;
            Button.glyph(ui, Part.of(id, "add", 0), new Rect(x, r.y(), button, h), Glyph.PLUS, enabled, () -> {
                m.set(List.of(""));
                ui.memory().put(Part.of(id, "focus"), 0);
            });
            Button.glyph(ui, Part.of(id, "remove", 0), new Rect(x + button + GAP, r.y(), button, h), Glyph.MINUS,
                    false, () -> {
                    });
            return;
        }
        for (int i = 0; i < values.size(); i++) {
            float y = r.y() + i * (h + GAP);
            Rect field = new Rect(r.x(), y, r.w() - (button + GAP) * 2f, h);
            int index = i;
            String original = values.get(i);
            Object fieldId = Part.of(id, "item", i);
            TextField.Model item = new TextField.Model() {
                @Override
                public String text() {
                    List<String> now = m.values();
                    return index < now.size() ? now.get(index) : "";
                }

                @Override
                public void commit(String text) {
                    m.set(ListEditor.commit(m.values(), index, original, text));
                }

                @Override
                public String placeholder() {
                    return m.placeholder();
                }
            };
            if (pendingFocus != null && pendingFocus == i && enabled) {
                ui.memory().remove(Part.of(id, "focus"));
                TextField.focus(ui, fieldId, item, false);
            }
            TextField.render(ui, fieldId, field, item, enabled);
            float x = field.right() + GAP;
            Button.glyph(ui, Part.of(id, "add", i), new Rect(x, y, button, h), Glyph.PLUS, enabled, () -> {
                ui.editors().commit();
                m.set(insertAfter(m.values(), index));
                ui.memory().put(Part.of(id, "focus"), index + 1);
            });
            Button.glyph(ui, Part.of(id, "remove", i), new Rect(x + button + GAP, y, button, h), Glyph.MINUS,
                    enabled, () -> {
                        ui.editors().commit();
                        m.set(remove(m.values(), index));
                    });
        }
    }
}
