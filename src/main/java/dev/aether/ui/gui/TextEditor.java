package dev.aether.ui.gui;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// a text field's editing state apart from drawing; layout(), called while the focused field draws, records
// real caret positions so pointer and up/down mapping work between frames without nanovg
public final class TextEditor {
    public enum Result { IGNORED, HANDLED, SUBMIT }

    private static final float DRAG_THRESHOLD = 3f;

    private final StringBuilder text;
    private boolean multiline;
    private boolean numeric;
    private int maxLength = Integer.MAX_VALUE;
    private int caret;
    private int anchor;
    private int revision;
    private float preferredX = Float.NaN;
    private Map<String, float[]> caretsByLine = Map.of();
    private float averageAdvance = 6f;
    private float lineHeight = 12f;
    private float viewWidth;
    private float viewHeight;
    private float scrollX;
    private float scrollY;
    private float pressX;
    private float pressY;
    private boolean pressing;
    private boolean selecting;

    public TextEditor(String initial) {
        text = new StringBuilder(initial == null ? "" : initial);
        caret = text.length();
        anchor = caret;
    }

    public TextEditor multiline() {
        multiline = true;
        return this;
    }

    // digits, a minus sign and either decimal separator
    public TextEditor numeric() {
        numeric = true;
        return this;
    }

    public TextEditor maxLength(int max) {
        maxLength = Math.max(0, max);
        return this;
    }

    // -- state -----------------------------------------------------------------

    public String text() {
        return text.toString();
    }

    public int caret() {
        return caret;
    }

    public boolean hasSelection() {
        return caret != anchor;
    }

    public int selectionStart() {
        return Math.min(caret, anchor);
    }

    public int selectionEnd() {
        return Math.max(caret, anchor);
    }

    public String selectedText() {
        return text.substring(selectionStart(), selectionEnd());
    }

    // bumps on every edit, so a field can apply live changes once per edit
    public int revision() {
        return revision;
    }

    public void setText(String value) {
        text.setLength(0);
        text.append(value == null ? "" : value);
        caret = text.length();
        anchor = caret;
        edited();
    }

    public void selectAll() {
        anchor = 0;
        caret = text.length();
    }

    public void setCaret(int index, boolean extend) {
        caret = clampIndex(index);
        if (!extend) {
            anchor = caret;
        }
        preferredX = Float.NaN;
    }

    // -- layout ----------------------------------------------------------------

    // call while drawing the focused field, with the font it draws in and the visible text area size
    public void layout(TextMetrics metrics, String font, float size, float width, float height) {
        viewWidth = width;
        viewHeight = height;
        lineHeight = metrics.lineHeight(font, size);
        Map<String, float[]> carets = new HashMap<>();
        float advance = 0f;
        int chars = 0;
        for (int line = 0; line < lineCount(); line++) {
            String content = lineText(line);
            float[] x = metrics.caretX(font, size, content);
            carets.put(content, x);
            advance += x[x.length - 1];
            chars += content.length();
        }
        caretsByLine = carets;
        averageAdvance = chars > 0 ? advance / chars : size * 0.5f;
        revealCaret();
    }

    public float scrollX() {
        return scrollX;
    }

    public float scrollY() {
        return scrollY;
    }

    public float lineHeight() {
        return lineHeight;
    }

    public int lineCount() {
        if (!multiline) {
            return 1;
        }
        int count = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    public String lineText(int line) {
        return text.substring(lineStart(line), lineEnd(line));
    }

    // top of a line in the text area, scroll applied
    public float lineTop(int line) {
        return line * lineHeight - scrollY;
    }

    public Rect caretRect() {
        int line = lineOf(caret);
        return new Rect(xOf(caret) - scrollX, lineTop(line), 1f, lineHeight);
    }

    public List<Rect> selectionRects() {
        List<Rect> rects = new ArrayList<>();
        if (!hasSelection()) {
            return rects;
        }
        int start = selectionStart();
        int end = selectionEnd();
        for (int line = lineOf(start); line <= lineOf(end); line++) {
            int from = Math.max(start, lineStart(line));
            int to = Math.min(end, lineEnd(line));
            float left = xOf(from) - scrollX;
            float right = xOf(to) - scrollX;
            // a selected line break shows as a sliver so empty selected lines stay visible
            if (to < end) {
                right += averageAdvance * 0.5f;
            }
            rects.add(new Rect(left, lineTop(line), Math.max(0f, right - left), lineHeight));
        }
        return rects;
    }

    // char index for a point in the text area, from the caret positions the last draw recorded
    public int indexAt(float localX, float localY) {
        int line = multiline ? Math.max(0, Math.min(lineCount() - 1, (int) Math.floor((localY + scrollY) / lineHeight))) : 0;
        return indexInLine(line, localX + scrollX);
    }

    // -- pointer ---------------------------------------------------------------

    public void pointerPress(float localX, float localY, boolean extend) {
        setCaret(indexAt(localX, localY), extend);
        pressX = localX;
        pressY = localY;
        pressing = true;
        selecting = extend;
    }

    // selecting only starts after the pointer moves 3 units, so a shaky click still just places the caret
    public void pointerDrag(float localX, float localY) {
        if (!pressing) {
            return;
        }
        if (!selecting) {
            float dx = localX - pressX;
            float dy = localY - pressY;
            if (dx * dx + dy * dy < DRAG_THRESHOLD * DRAG_THRESHOLD) {
                return;
            }
            selecting = true;
        }
        caret = indexAt(localX, localY);
        preferredX = Float.NaN;
        revealCaret();
    }

    public void pointerRelease() {
        pressing = false;
        selecting = false;
    }

    // -- keys and chars --------------------------------------------------------

    // escape never comes here: the owning field decides whether it commits, cancels or clears
    public Result key(KeyInput k, Clipboard clipboard) {
        boolean extend = k.shift();
        boolean word = k.shortcut();
        switch (k.key()) {
            case GLFW.GLFW_KEY_LEFT -> {
                if (hasSelection() && !extend) {
                    setCaret(selectionStart(), false);
                } else {
                    setCaret(word ? wordLeft(caret) : previousPoint(caret), extend);
                }
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                if (hasSelection() && !extend) {
                    setCaret(selectionEnd(), false);
                } else {
                    setCaret(word ? wordRight(caret) : nextPoint(caret), extend);
                }
            }
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN -> {
                if (!multiline) {
                    return Result.IGNORED;
                }
                moveVertically(k.is(GLFW.GLFW_KEY_UP) ? -1 : 1, extend);
            }
            case GLFW.GLFW_KEY_HOME -> setCaret(word ? 0 : lineStart(lineOf(caret)), extend);
            case GLFW.GLFW_KEY_END -> setCaret(word ? text.length() : lineEnd(lineOf(caret)), extend);
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (hasSelection()) {
                    replaceSelection("");
                } else if (caret > 0) {
                    anchor = word ? wordLeft(caret) : previousPoint(caret);
                    replaceSelection("");
                }
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (hasSelection()) {
                    replaceSelection("");
                } else if (caret < text.length()) {
                    anchor = word ? wordRight(caret) : nextPoint(caret);
                    replaceSelection("");
                }
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (!multiline) {
                    return Result.SUBMIT;
                }
                insert("\n");
            }
            case GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_C, GLFW.GLFW_KEY_X, GLFW.GLFW_KEY_V -> {
                return word ? shortcut(k.key(), clipboard) : Result.IGNORED;
            }
            default -> {
                return Result.IGNORED;
            }
        }
        revealCaret();
        return Result.HANDLED;
    }

    // typed text; always consumed while the editor has focus, even when the filter drops all of it
    public boolean chars(String typed) {
        insert(typed);
        return true;
    }

    public void insert(String raw) {
        String accepted = filter(raw == null ? "" : raw);
        int room = maxLength - (text.length() - (selectionEnd() - selectionStart()));
        if (accepted.length() > Math.max(0, room)) {
            accepted = accepted.substring(0, safeCut(accepted, Math.max(0, room)));
        }
        if (accepted.isEmpty() && !hasSelection()) {
            return;
        }
        replaceSelection(accepted);
        revealCaret();
    }

    // -- internals -------------------------------------------------------------

    private Result shortcut(int key, Clipboard clipboard) {
        switch (key) {
            case GLFW.GLFW_KEY_A -> selectAll();
            case GLFW.GLFW_KEY_C -> clipboard.write(hasSelection() ? selectedText() : text());
            case GLFW.GLFW_KEY_X -> {
                if (hasSelection()) {
                    clipboard.write(selectedText());
                    replaceSelection("");
                }
            }
            default -> insert(clipboard.read());
        }
        revealCaret();
        return Result.HANDLED;
    }

    private void replaceSelection(String replacement) {
        int start = selectionStart();
        text.replace(start, selectionEnd(), replacement);
        caret = start + replacement.length();
        anchor = caret;
        edited();
    }

    private void edited() {
        revision++;
        preferredX = Float.NaN;
    }

    private String filter(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (numeric) {
                if (Character.isDigit(c) || c == '-' || c == '.' || c == ',') {
                    out.append(c);
                }
            } else if (c == '\n' || c == '\t') {
                out.append(multiline && c == '\n' ? '\n' : ' ');
            } else if (c >= ' ' && c != 0x7F) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private void moveVertically(int direction, boolean extend) {
        float x = Float.isNaN(preferredX) ? xOf(caret) : preferredX;
        int target = lineOf(caret) + direction;
        if (target < 0) {
            setCaret(0, extend);
        } else if (target >= lineCount()) {
            setCaret(text.length(), extend);
        } else {
            setCaret(indexInLine(target, x), extend);
        }
        preferredX = x;
    }

    // scrolls only once a draw has told the editor how big its text area is
    private void revealCaret() {
        if (viewWidth <= 0f) {
            return;
        }
        float x = xOf(caret);
        if (x - scrollX < 0f) {
            scrollX = x;
        } else if (x - scrollX > viewWidth - 1f) {
            scrollX = x - viewWidth + 1f;
        }
        scrollX = Math.max(0f, scrollX);
        if (multiline && viewHeight > 0f) {
            float top = lineOf(caret) * lineHeight;
            if (top < scrollY) {
                scrollY = top;
            } else if (top + lineHeight > scrollY + viewHeight) {
                scrollY = top + lineHeight - viewHeight;
            }
            scrollY = Math.max(0f, Math.min(scrollY, Math.max(0f, lineCount() * lineHeight - viewHeight)));
        }
    }

    private int indexInLine(int line, float x) {
        int start = lineStart(line);
        float[] carets = caretsOf(lineText(line));
        int best = 0;
        for (int i = 1; i < carets.length; i++) {
            if (Math.abs(carets[i] - x) < Math.abs(carets[best] - x)) {
                best = i;
            }
        }
        int index = start + best;
        return index > start && index < text.length() && Character.isLowSurrogate(text.charAt(index)) ? index - 1 : index;
    }

    private float xOf(int index) {
        int line = lineOf(index);
        float[] carets = caretsOf(lineText(line));
        return carets[Math.max(0, Math.min(carets.length - 1, index - lineStart(line)))];
    }

    // edits between two frames have no recorded positions yet, so those lines use the average advance
    private float[] caretsOf(String line) {
        float[] recorded = caretsByLine.get(line);
        if (recorded != null) {
            return recorded;
        }
        float[] estimated = new float[line.length() + 1];
        for (int i = 0; i < estimated.length; i++) {
            estimated[i] = i * averageAdvance;
        }
        return estimated;
    }

    private int lineOf(int index) {
        if (!multiline) {
            return 0;
        }
        int line = 0;
        for (int i = 0; i < Math.min(index, text.length()); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private int lineStart(int line) {
        if (!multiline || line <= 0) {
            return 0;
        }
        int seen = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n' && ++seen == line) {
                return i + 1;
            }
        }
        return text.length();
    }

    private int lineEnd(int line) {
        if (!multiline) {
            return text.length();
        }
        int end = text.indexOf("\n", lineStart(line));
        return end < 0 ? text.length() : end;
    }

    private int previousPoint(int index) {
        return index <= 0 ? 0 : text.offsetByCodePoints(index, -1);
    }

    private int nextPoint(int index) {
        return index >= text.length() ? text.length() : text.offsetByCodePoints(index, 1);
    }

    // skips the whitespace before the caret, then the word or punctuation run before that
    private int wordLeft(int index) {
        int i = index;
        while (i > 0 && Character.isWhitespace(text.charAt(i - 1))) {
            i--;
        }
        if (i > 0) {
            boolean word = isWordChar(text.charAt(i - 1));
            while (i > 0 && !Character.isWhitespace(text.charAt(i - 1)) && isWordChar(text.charAt(i - 1)) == word) {
                i--;
            }
        }
        return i;
    }

    // skips the word or punctuation run at the caret, then the whitespace after it
    private int wordRight(int index) {
        int i = index;
        int length = text.length();
        if (i < length && !Character.isWhitespace(text.charAt(i))) {
            boolean word = isWordChar(text.charAt(i));
            while (i < length && !Character.isWhitespace(text.charAt(i)) && isWordChar(text.charAt(i)) == word) {
                i++;
            }
        }
        while (i < length && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private int clampIndex(int index) {
        int clamped = Math.max(0, Math.min(text.length(), index));
        return clamped > 0 && clamped < text.length() && Character.isLowSurrogate(text.charAt(clamped)) ? clamped - 1 : clamped;
    }

    private static int safeCut(String s, int length) {
        return length > 0 && length < s.length() && Character.isLowSurrogate(s.charAt(length)) ? length - 1 : length;
    }
}
