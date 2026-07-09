package dev.aether.ui.components;

import dev.aether.ui.util.Colors;
import dev.aether.ui.util.Fonts;
import dev.aether.renderer.NVGRenderer;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

/**
 * A single-line text input field with cursor blink and basic editing.
 *
 * <p>Supported keys: printable characters, Backspace, Delete, Home, End,
 * Left/Right arrows, Ctrl+A (select-all -> clear).</p>
 *
 * <pre>{@code
 * TextInput field = new TextInput("Search...");
 * field.setBounds(x, y, 200, 28);
 * field.setOnChange(text -> filter(text));
 * }</pre>
 */
public class TextInput extends Component {

    // -- Layout ----------------------------------------------------------------

    private static final float PAD_X     = 10f;
    private static final float FONT_SIZE = 13f;
    private static final float RADIUS    =  6f;

    // -- Cursor blink ----------------------------------------------------------

    private static final long BLINK_MS = 530L;

    // -- State -----------------------------------------------------------------

    private StringBuilder text    = new StringBuilder();
    private int           cursor  = 0;
    private boolean       focused = false;
    private long          blinkStart = System.currentTimeMillis();

    private String       placeholder;
    private Consumer<String> onChange;

    // -- Construction ----------------------------------------------------------

    public TextInput(String placeholder) {
        this.placeholder = placeholder;
        this.height = 28f;
    }

    // -- Rendering -------------------------------------------------------------

    @Override
    public void render(NVGRenderer nvg) {
        if (!visible) return;

        // Background
        int borderColor = focused ? Colors.ACCENT : Colors.BORDER;
        nvg.roundedRect(x, y, width, height, RADIUS, Colors.FIELD);
        nvg.rectOutline(x, y, width, height, RADIUS, 1f, borderColor);

        float textY = y + (height - FONT_SIZE) / 2f;

        if (text.length() == 0 && !focused) {
            // Placeholder
            nvg.text(Fonts.REGULAR, placeholder, x + PAD_X, textY, FONT_SIZE, Colors.TEXT_DIM);
        } else {
            // Clip content to the field
            nvg.pushScissor(x + 1, y + 1, width - 2, height - 2);

            String display = text.toString();
            nvg.text(Fonts.REGULAR, display, x + PAD_X, textY, FONT_SIZE, Colors.TEXT);

            // Cursor
            if (focused) {
                long elapsed = (System.currentTimeMillis() - blinkStart) % (BLINK_MS * 2);
                if (elapsed < BLINK_MS) {
                    String beforeCursor = display.substring(0, Math.min(cursor, display.length()));
                    float cursorX = x + PAD_X + nvg.textWidth(Fonts.REGULAR, beforeCursor, FONT_SIZE);
                    nvg.line(cursorX, textY + 1f, cursorX, textY + FONT_SIZE - 1f, 1.5f, Colors.TEXT);
                }
            }

            nvg.popScissor();
        }
    }

    // -- Input -----------------------------------------------------------------

    @Override
    public boolean mousePressed(double mouseX, double mouseY, int button) {
        if (button == 0) {
            boolean wasHere = contains(mouseX, mouseY);
            setFocused(wasHere);
            return wasHere;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!focused) return false;

        boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;

        switch (keyCode) {
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (ctrl) {
                    // Delete word back
                    while (cursor > 0 && text.charAt(cursor - 1) == ' ') cursor--;
                    while (cursor > 0 && text.charAt(cursor - 1) != ' ') {
                        text.deleteCharAt(--cursor);
                    }
                } else if (cursor > 0) {
                    text.deleteCharAt(--cursor);
                }
                notifyChange();
                return true;
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (cursor < text.length()) {
                    text.deleteCharAt(cursor);
                    notifyChange();
                }
                return true;
            }
            case GLFW.GLFW_KEY_LEFT -> {
                if (cursor > 0) cursor--;
                resetBlink();
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                if (cursor < text.length()) cursor++;
                resetBlink();
                return true;
            }
            case GLFW.GLFW_KEY_HOME -> { cursor = 0;               resetBlink(); return true; }
            case GLFW.GLFW_KEY_END  -> { cursor = text.length();   resetBlink(); return true; }
            case GLFW.GLFW_KEY_A    -> {
                if (ctrl) { text.setLength(0); cursor = 0; notifyChange(); return true; }
            }
            case GLFW.GLFW_KEY_ESCAPE -> { setFocused(false); return true; }
        }
        return false;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (!focused) return false;
        text.insert(cursor++, codePoint);
        notifyChange();
        return true;
    }

    // -- API -------------------------------------------------------------------

    public String getText() { return text.toString(); }

    public TextInput setText(String s) {
        text.setLength(0);
        text.append(s);
        cursor = text.length();
        return this;
    }

    public TextInput setOnChange(Consumer<String> handler) {
        this.onChange = handler;
        return this;
    }

    public TextInput setPlaceholder(String ph) { this.placeholder = ph; return this; }

    public boolean isFocused() { return focused; }

    public void setFocused(boolean focused) {
        this.focused = focused;
        if (focused) resetBlink();
    }

    private void notifyChange() {
        resetBlink();
        if (onChange != null) onChange.accept(text.toString());
    }

    private void resetBlink() {
        blinkStart = System.currentTimeMillis();
    }
}
