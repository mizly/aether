package dev.aether.ui.util;

import dev.aether.renderer.NVGRenderer;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Lightweight NVG-rendered text input field.
 * No vanilla widgets - handles its own keyboard input and rendering.
 */
public class TextInput {

    private String  value     = "";
    private int     cursor    = 0;
    private int     maxLength = 256;
    private boolean focused   = false;

    // -- Config ----------------------------------------------------------------

    public void setValue(String v) {
        value  = v == null ? "" : v;
        cursor = value.length();
    }

    public String getValue() { return value; }

    public void setMaxLength(int max) { maxLength = max; }

    public boolean isFocused() { return focused; }

    public void setFocused(boolean f) { focused = f; }

    // -- Input -----------------------------------------------------------------

    public boolean charTyped(char ch) {
        if (!focused) return false;
        // Ignore control characters
        if (ch < 32 || ch == 127) return true;
        if (value.length() >= maxLength) return true;
        value  = value.substring(0, cursor) + ch + value.substring(cursor);
        cursor++;
        return true;
    }

    public boolean keyPressed(int key, int mods) {
        if (!focused) return false;
        boolean ctrl = (mods & GLFW.GLFW_MOD_CONTROL) != 0;

        switch (key) {
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (cursor > 0) {
                    if (ctrl) {
                        // Delete word
                        int start = wordStart(cursor - 1);
                        value  = value.substring(0, start) + value.substring(cursor);
                        cursor = start;
                    } else {
                        value  = value.substring(0, cursor - 1) + value.substring(cursor);
                        cursor--;
                    }
                }
                return true;
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (cursor < value.length()) {
                    if (ctrl) {
                        int end = wordEnd(cursor);
                        value = value.substring(0, cursor) + value.substring(end);
                    } else {
                        value = value.substring(0, cursor) + value.substring(cursor + 1);
                    }
                }
                return true;
            }
            case GLFW.GLFW_KEY_LEFT -> {
                if (cursor > 0) cursor = ctrl ? wordStart(cursor - 1) : cursor - 1;
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                if (cursor < value.length()) cursor = ctrl ? wordEnd(cursor) : cursor + 1;
                return true;
            }
            case GLFW.GLFW_KEY_HOME -> { cursor = 0;             return true; }
            case GLFW.GLFW_KEY_END  -> { cursor = value.length(); return true; }
            case GLFW.GLFW_KEY_A -> {
                if (ctrl) { cursor = value.length(); return true; }
            }
            case GLFW.GLFW_KEY_C -> {
                if (ctrl) { Minecraft.getInstance().keyboardHandler.setClipboard(value); return true; }
            }
            case GLFW.GLFW_KEY_X -> {
                if (ctrl) {
                    Minecraft.getInstance().keyboardHandler.setClipboard(value);
                    value = ""; cursor = 0;
                    return true;
                }
            }
            case GLFW.GLFW_KEY_V -> {
                if (ctrl) {
                    String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
                    if (clip != null && !clip.isEmpty()) {
                        // strip newlines, trim to max
                        clip = clip.replace("\n", "").replace("\r", "");
                        int room = maxLength - value.length();
                        if (room > 0) {
                            clip   = clip.substring(0, Math.min(clip.length(), room));
                            value  = value.substring(0, cursor) + clip + value.substring(cursor);
                            cursor += clip.length();
                        }
                    }
                    return true;
                }
            }
        }
        return false;
    }

    // -- Render ----------------------------------------------------------------

    /**
     * Draws the text and blinking cursor inside the given bounds.
     * Caller is responsible for drawing the field background behind this.
     */
    public void render(NVGRenderer nvg, float x, float y, float w, float h,
                       float fontSize, int textColor) {
        float padX   = 8f;
        float textX  = x + padX;
        float textY  = y + (h - fontSize) / 2f;
        float clipW  = w - padX * 2f;

        nvg.save();
        nvg.scissor(x, y, w, h);

        // Measure cursor offset to determine scroll
        String beforeCursor = value.substring(0, cursor);
        float  cursorPx     = nvg.textWidth(Fonts.REGULAR, beforeCursor, fontSize);
        float  totalPx      = nvg.textWidth(Fonts.REGULAR, value, fontSize);

        // Scroll so cursor stays visible
        float scroll = 0f;
        if (cursorPx > clipW) scroll = cursorPx - clipW + 4f;

        nvg.text(Fonts.REGULAR, value, textX - scroll, textY, fontSize, textColor);

        // Blinking cursor when focused
        if (focused && (System.currentTimeMillis() / 530) % 2 == 0) {
            float cx = textX - scroll + cursorPx;
            nvg.rect(cx, textY + 1f, 1f, fontSize - 2f, textColor);
        }

        nvg.resetScissor();
        nvg.restore();
    }

    // -- Word navigation helpers -----------------------------------------------

    private int wordStart(int pos) {
        while (pos > 0 && value.charAt(pos - 1) != ' ') pos--;
        return pos;
    }

    private int wordEnd(int pos) {
        while (pos < value.length() && value.charAt(pos) != ' ') pos++;
        return pos;
    }
}
