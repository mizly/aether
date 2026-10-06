package dev.aether.ui.gui.control;

import dev.aether.ui.gui.Cursor;
import dev.aether.ui.gui.GuiCanvas;
import dev.aether.ui.gui.HitHandler;
import dev.aether.ui.gui.PointerEvent;
import dev.aether.ui.gui.Rect;
import dev.aether.ui.gui.TextEditor;
import dev.aether.ui.gui.skin.FontRole;
import dev.aether.ui.gui.skin.GuiSkin;
import dev.aether.ui.gui.skin.SkinContext;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

// a single or multi-line text box. clicking focuses a TextEditor through EditorFocus; the editor draws its
// caret and selection and records real glyph positions while it draws, so clicks and drags map exactly
public final class TextField {
    public static final float PAD_X = 9f;
    public static final float PAD_Y = 7f;
    private static final long BLINK_MS = 530L;

    private TextField() {
    }

    public interface Model {
        // the stored value
        String text();

        void commit(String text);

        default String placeholder() {
            return "";
        }

        default boolean multiline() {
            return false;
        }

        default boolean numeric() {
            return false;
        }

        default int maxLength() {
            return Integer.MAX_VALUE;
        }

        default EditorFocus.Escape escape() {
            return EditorFocus.Escape.COMMIT;
        }

        default void edited(String text) {
        }

        // shown while not editing, e.g. a slider value with its suffix
        default String display() {
            return text();
        }

        // what the editor starts with
        default String editText() {
            return text();
        }

        default FontRole font() {
            return FontRole.BODY;
        }

        default boolean centered() {
            return false;
        }

        default boolean invalid() {
            return false;
        }

        // extra room before the text, for a prefix the caller draws (an axis letter)
        default float insetLeft() {
            return 0f;
        }
    }

    public static Model of(Supplier<String> text, Consumer<String> commit, String placeholder) {
        return new Model() {
            @Override
            public String text() {
                String value = text.get();
                return value == null ? "" : value;
            }

            @Override
            public void commit(String value) {
                commit.accept(value);
            }

            @Override
            public String placeholder() {
                return placeholder == null ? "" : placeholder;
            }
        };
    }

    public static float singleLineHeight(UiContext ui) {
        return ui.metrics().controlHeight();
    }

    public static float multilineHeight(UiContext ui, Model m, int visibleLines) {
        float lh = ui.skin().lineHeight(ui.sc(), m.font());
        return PAD_Y * 2f + lh * Math.max(1, visibleLines);
    }

    public static void focus(UiContext ui, Object id, Model m, boolean selectAll) {
        if (ui.editors().focused(id)) {
            return;
        }
        TextEditor editor = new TextEditor(m.editText());
        if (m.multiline()) {
            editor.multiline();
        }
        if (m.numeric()) {
            editor.numeric();
        }
        if (m.maxLength() != Integer.MAX_VALUE) {
            editor.maxLength(m.maxLength());
        }
        if (selectAll) {
            editor.selectAll();
        }
        ui.editors().focus(id, new EditorFocus.Session() {
            @Override
            public TextEditor editor() {
                return editor;
            }

            @Override
            public void commit(String text) {
                m.commit(text);
            }

            @Override
            public EditorFocus.Escape escape() {
                return m.escape();
            }

            @Override
            public void edited(String text) {
                m.edited(text);
            }
        }, ui.layer());
    }

    public static void render(UiContext ui, Object id, Rect r, Model m, boolean enabled) {
        GuiSkin skin = ui.skin();
        SkinContext sc = ui.sc();
        GuiCanvas canvas = ui.canvas();
        boolean focused = enabled && ui.editors().focused(id);
        float hoverT = enabled ? ControlSupport.hover(ui, id) : 0f;
        canvas.save();
        if (!enabled) {
            canvas.alpha(0.5f);
        }
        skin.field(sc, r, focused, hoverT, m.invalid());
        String font = skin.font(m.font());
        float size = skin.fontSize(sc, m.font());
        float lh = canvas.lineHeight(font, size);
        float left = PAD_X + m.insetLeft();
        Rect area = m.multiline()
                ? r.inset(left, PAD_Y, PAD_X, PAD_Y)
                : new Rect(r.x() + left, r.centerY() - lh / 2f, Math.max(0f, r.w() - left - PAD_X), lh);
        canvas.save();
        canvas.clip(r.inset(2f));
        if (focused) {
            drawEditing(ui, id, area, font, size, lh);
            ui.editors().drawn(id, ui.rootRect(r));
        } else {
            drawResting(ui, m, area, font, size, lh);
        }
        canvas.restore();
        canvas.restore();
        if (!enabled) {
            return;
        }
        float offsetX = area.x() - r.x();
        float offsetY = area.y() - r.y();
        ControlSupport.region(ui, id, r, new Handler(ui, id, m, offsetX, offsetY), Cursor.IBEAM);
        ControlSupport.focusable(ui, id, r, () -> focus(ui, id, m, true));
        ControlSupport.ring(ui, id, r, ui.metrics().fieldRadius());
    }

    private static void drawEditing(UiContext ui, Object id, Rect area, String font, float size, float lh) {
        TextEditor editor = ui.editors().editor();
        GuiCanvas canvas = ui.canvas();
        editor.layout(canvas.metrics(), font, size, area.w(), area.h());
        float[] pending = ui.memory().peek(Part.of(id, "press"));
        if (pending != null) {
            ui.memory().remove(Part.of(id, "press"));
            editor.pointerPress(pending[0], pending[1], false);
            editor.layout(canvas.metrics(), font, size, area.w(), area.h());
        }
        for (Rect selection : editor.selectionRects()) {
            ui.skin().textSelection(ui.sc(), selection.offset(area.x(), area.y()));
        }
        int color = ui.palette().text();
        for (int line = 0; line < editor.lineCount(); line++) {
            float y = area.y() + editor.lineTop(line);
            if (y + lh < area.y() - lh || y > area.bottom() + lh) {
                continue;
            }
            canvas.text(font, size, editor.lineText(line), area.x() - editor.scrollX(), y, color);
        }
        long now = ui.nowMs();
        long[] blink = ui.memory().get(Part.of(id, "blink"), () -> new long[]{-1L, now});
        if (blink[0] != editor.revision() + 31L * editor.caret()) {
            blink[0] = editor.revision() + 31L * editor.caret();
            blink[1] = now;
        }
        if (((now - blink[1]) / BLINK_MS) % 2L == 0L) {
            Rect caret = editor.caretRect().offset(area.x(), area.y());
            ui.skin().caret(ui.sc(), new Rect(caret.x(), caret.y() + 1f, 1f, Math.max(1f, caret.h() - 2f)));
        }
    }

    private static void drawResting(UiContext ui, Model m, Rect area, String font, float size, float lh) {
        GuiCanvas canvas = ui.canvas();
        String value = m.display();
        boolean empty = value == null || value.isEmpty();
        String shown = empty ? m.placeholder() : value;
        int color = empty ? ui.palette().textDim() : ui.palette().textValue();
        if (!m.multiline()) {
            String line = canvas.ellipsize(font, size, shown.replace('\n', ' '), area.w());
            float x = m.centered() ? area.centerX() - canvas.textWidth(font, size, line) / 2f : area.x();
            canvas.text(font, size, line, x, area.y(), color);
            return;
        }
        float y = area.y();
        for (String line : List.of(shown.split("\n", -1))) {
            if (y > area.bottom()) {
                break;
            }
            canvas.text(font, size, canvas.ellipsize(font, size, line, area.w()), area.x(), y, color);
            y += lh;
        }
    }

    private static final class Handler implements HitHandler {
        private final UiContext ui;
        private final Object id;
        private final Model model;
        private final float offsetX;
        private final float offsetY;

        private Handler(UiContext ui, Object id, Model model, float offsetX, float offsetY) {
            this.ui = ui;
            this.id = id;
            this.model = model;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
        }

        @Override
        public boolean press(PointerEvent e) {
            if (e.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                return false;
            }
            float x = e.localX() - e.pressRect().x() - offsetX;
            float y = e.localY() - e.pressRect().y() - offsetY;
            if (!ui.editors().focused(id)) {
                focus(ui, id, model, false);
                // positions are recorded on the next draw, so the caret lands there
                ui.memory().put(Part.of(id, "press"), new float[]{x, y});
                return true;
            }
            TextEditor editor = ui.editors().editor();
            if (e.clicks() >= 2) {
                editor.selectAll();
            } else {
                editor.pointerPress(x, y, (e.mods() & GLFW.GLFW_MOD_SHIFT) != 0);
            }
            return true;
        }

        @Override
        public void drag(PointerEvent e) {
            if (ui.editors().focused(id) && ui.memory().peek(Part.of(id, "press")) == null) {
                ui.editors().editor().pointerDrag(e.localX() - e.pressRect().x() - offsetX,
                        e.localY() - e.pressRect().y() - offsetY);
            }
        }

        @Override
        public void release(PointerEvent e) {
            if (ui.editors().focused(id)) {
                ui.editors().editor().pointerRelease();
            }
        }
    }
}
