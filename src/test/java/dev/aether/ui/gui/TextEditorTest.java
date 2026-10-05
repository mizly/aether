package dev.aether.ui.gui;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;

import static dev.aether.ui.gui.Inputs.key;
import static dev.aether.ui.gui.Inputs.shift;
import static dev.aether.ui.gui.Inputs.shortcut;
import static dev.aether.ui.gui.Inputs.shortcutShift;
import static org.junit.jupiter.api.Assertions.*;

class TextEditorTest {
    private static final String FONT = "test";
    // monospace at size 10: 5 units per char, 12.5 per line
    private static final float SIZE = 10f;

    private final MonospaceTextMetrics metrics = new MonospaceTextMetrics();
    private final MemoryClipboard clipboard = new MemoryClipboard();

    @Test
    void typingInsertsAtTheCaretAndReplacesTheSelection() {
        TextEditor editor = new TextEditor("helo");
        editor.setCaret(3, false);
        editor.chars("l");
        assertEquals("hello", editor.text());
        assertEquals(4, editor.caret());
        editor.setCaret(0, false);
        editor.setCaret(5, true);
        editor.chars("bye");
        assertEquals("bye", editor.text());
        assertFalse(editor.hasSelection());
    }

    @Test
    void arrowsMoveByCodePointAndCollapseASelection() {
        TextEditor editor = new TextEditor("a😀b");
        editor.setCaret(1, false);
        editor.key(key(GLFW.GLFW_KEY_RIGHT), clipboard);
        assertEquals(3, editor.caret());
        editor.key(key(GLFW.GLFW_KEY_LEFT), clipboard);
        assertEquals(1, editor.caret());
        editor.setCaret(2, false);
        assertEquals(1, editor.caret());

        editor.setCaret(0, false);
        editor.key(shift(GLFW.GLFW_KEY_RIGHT), clipboard);
        editor.key(shift(GLFW.GLFW_KEY_RIGHT), clipboard);
        assertEquals("a😀", editor.selectedText());
        editor.key(key(GLFW.GLFW_KEY_LEFT), clipboard);
        assertEquals(0, editor.caret());
        assertFalse(editor.hasSelection());
    }

    @Test
    void wordMovesSkipRunsAndWhitespace() {
        TextEditor editor = new TextEditor("hello, big world");
        int[] lefts = {11, 7, 5, 0};
        for (int expected : lefts) {
            editor.key(shortcut(GLFW.GLFW_KEY_LEFT), clipboard);
            assertEquals(expected, editor.caret());
        }
        int[] rights = {5, 7, 11, 16};
        for (int expected : rights) {
            editor.key(shortcut(GLFW.GLFW_KEY_RIGHT), clipboard);
            assertEquals(expected, editor.caret());
        }
        editor.key(shortcutShift(GLFW.GLFW_KEY_LEFT), clipboard);
        assertEquals("world", editor.selectedText());
    }

    @Test
    void wordDeletesRemoveTheRunNextToTheCaret() {
        TextEditor editor = new TextEditor("hello, big world");
        editor.key(shortcut(GLFW.GLFW_KEY_BACKSPACE), clipboard);
        assertEquals("hello, big ", editor.text());
        editor.setCaret(0, false);
        editor.key(shortcut(GLFW.GLFW_KEY_DELETE), clipboard);
        assertEquals(", big ", editor.text());
        editor.key(key(GLFW.GLFW_KEY_DELETE), clipboard);
        assertEquals(" big ", editor.text());
        editor.key(key(GLFW.GLFW_KEY_BACKSPACE), clipboard);
        assertEquals(" big ", editor.text());
    }

    @Test
    void clipboardShortcutsWorkOnTheSelectionOrTheWholeText() {
        TextEditor editor = new TextEditor("copy me");
        editor.key(shortcut(GLFW.GLFW_KEY_C), clipboard);
        assertEquals("copy me", clipboard.text);
        editor.key(shortcut(GLFW.GLFW_KEY_A), clipboard);
        assertEquals("copy me", editor.selectedText());
        editor.setCaret(5, false);
        editor.key(shortcutShift(GLFW.GLFW_KEY_END), clipboard);
        editor.key(shortcut(GLFW.GLFW_KEY_X), clipboard);
        assertEquals("me", clipboard.text);
        assertEquals("copy ", editor.text());
        clipboard.text = "line one\nline two";
        editor.key(shortcut(GLFW.GLFW_KEY_V), clipboard);
        assertEquals("copy line one line two", editor.text());
        assertEquals(TextEditor.Result.IGNORED, editor.key(key(GLFW.GLFW_KEY_V), clipboard));
    }

    @Test
    void enterSubmitsASingleLineAndBreaksAMultiline() {
        assertEquals(TextEditor.Result.SUBMIT, new TextEditor("x").key(key(GLFW.GLFW_KEY_ENTER), clipboard));
        TextEditor multi = new TextEditor("ab").multiline();
        multi.setCaret(1, false);
        assertEquals(TextEditor.Result.HANDLED, multi.key(key(GLFW.GLFW_KEY_KP_ENTER), clipboard));
        assertEquals("a\nb", multi.text());
        assertEquals(2, multi.lineCount());
        assertEquals("b", multi.lineText(1));
    }

    @Test
    void numericFieldsAndMaxLengthFilterInput() {
        TextEditor number = new TextEditor("").numeric();
        number.chars("-1,5 kg\n");
        assertEquals("-1,5", number.text());
        TextEditor limited = new TextEditor("abc").maxLength(5);
        limited.chars("defg");
        assertEquals("abcde", limited.text());
        limited.key(shortcut(GLFW.GLFW_KEY_A), clipboard);
        limited.chars("0123456789");
        assertEquals("01234", limited.text());
        TextEditor single = new TextEditor("");
        single.chars("a\tb\u0007c");
        assertEquals("a bc", single.text());
    }

    @Test
    void upAndDownKeepTheirColumnAcrossShortLines() {
        TextEditor editor = new TextEditor("abcdefgh\nab\nabcdefgh").multiline();
        editor.layout(metrics, FONT, SIZE, 400f, 200f);
        editor.setCaret(6, false);
        editor.key(key(GLFW.GLFW_KEY_DOWN), clipboard);
        assertEquals(11, editor.caret());
        editor.key(key(GLFW.GLFW_KEY_DOWN), clipboard);
        assertEquals(18, editor.caret());
        editor.key(key(GLFW.GLFW_KEY_UP), clipboard);
        editor.key(key(GLFW.GLFW_KEY_UP), clipboard);
        assertEquals(6, editor.caret());
        editor.key(key(GLFW.GLFW_KEY_UP), clipboard);
        assertEquals(0, editor.caret());
        assertEquals(TextEditor.Result.IGNORED, new TextEditor("x").key(key(GLFW.GLFW_KEY_UP), clipboard));
    }

    @Test
    void homeAndEndWorkPerLineAndWithShortcutOnTheWholeText() {
        TextEditor editor = new TextEditor("first\nsecond").multiline();
        editor.setCaret(9, false);
        editor.key(key(GLFW.GLFW_KEY_HOME), clipboard);
        assertEquals(6, editor.caret());
        editor.key(key(GLFW.GLFW_KEY_END), clipboard);
        assertEquals(12, editor.caret());
        editor.key(shortcut(GLFW.GLFW_KEY_HOME), clipboard);
        assertEquals(0, editor.caret());
        editor.key(shortcutShift(GLFW.GLFW_KEY_END), clipboard);
        assertEquals("first\nsecond", editor.selectedText());
    }

    @Test
    void pointerMappingUsesThePositionsRecordedByTheLastDraw() {
        TextMetrics wideFirst = new TextMetrics() {
            @Override
            public float width(String font, float size, String text) {
                return caretX(font, size, text)[text.length()];
            }

            @Override
            public float lineHeight(String font, float size) {
                return 10f;
            }

            @Override
            public float[] caretX(String font, float size, String text) {
                float[] x = new float[text.length() + 1];
                for (int i = 1; i < x.length; i++) {
                    x[i] = x[i - 1] + (i == 1 ? 40f : 5f);
                }
                return x;
            }
        };
        TextEditor editor = new TextEditor("wide\nmore").multiline();
        editor.layout(wideFirst, FONT, SIZE, 400f, 100f);
        editor.pointerPress(42f, 2f, false);
        assertEquals(1, editor.caret());
        editor.pointerPress(42f, 12f, false);
        assertEquals(6, editor.caret());
        // an edit before the next draw has no recorded positions, so that line falls back to the average advance
        editor.setCaret(10, false);
        editor.chars("!");
        float average = (55f + 55f) / 8f;
        editor.pointerPress(average * 2f + 1f, 12f, false);
        assertEquals(7, editor.caret());
        editor.pointerPress(42f, 2f, false);
        assertEquals(1, editor.caret());
    }

    @Test
    void draggingOnlySelectsAfterThreeUnits() {
        TextEditor editor = new TextEditor("drag select");
        editor.layout(metrics, FONT, SIZE, 400f, 20f);
        editor.pointerPress(10f, 5f, false);
        editor.pointerDrag(12f, 6f);
        assertFalse(editor.hasSelection());
        editor.pointerDrag(30f, 6f);
        assertEquals("ag s", editor.selectedText());
        editor.pointerRelease();
        editor.pointerDrag(50f, 6f);
        assertEquals("ag s", editor.selectedText());
        editor.pointerPress(50f, 5f, true);
        assertEquals("ag selec", editor.selectedText());
    }

    @Test
    void horizontalScrollKeepsTheCaretInView() {
        TextEditor editor = new TextEditor("");
        editor.layout(metrics, FONT, SIZE, 20f, 12.5f);
        editor.chars("0123456789");
        editor.layout(metrics, FONT, SIZE, 20f, 12.5f);
        assertEquals(31f, editor.scrollX());
        assertEquals(19f, editor.caretRect().x());
        editor.key(key(GLFW.GLFW_KEY_HOME), clipboard);
        assertEquals(0f, editor.scrollX());
        assertEquals(0f, editor.caretRect().x());
    }

    @Test
    void multilineScrollsVerticallyToTheCaretLine() {
        TextEditor editor = new TextEditor("1\n2\n3\n4\n5").multiline();
        editor.layout(metrics, FONT, SIZE, 100f, 25f);
        assertEquals(37.5f, editor.scrollY());
        assertEquals(12.5f, editor.caretRect().y());
        editor.key(shortcut(GLFW.GLFW_KEY_HOME), clipboard);
        assertEquals(0f, editor.scrollY());
        assertEquals(12.5f, editor.lineTop(1));
    }

    @Test
    void selectionRectsCoverEachSelectedLine() {
        TextEditor editor = new TextEditor("abcd\nef\nghij").multiline();
        editor.layout(metrics, FONT, SIZE, 400f, 100f);
        editor.setCaret(2, false);
        editor.setCaret(10, true);
        List<Rect> rects = editor.selectionRects();
        assertEquals(3, rects.size());
        assertEquals(new Rect(10f, 0f, 12.5f, 12.5f), rects.get(0));
        assertEquals(new Rect(0f, 12.5f, 12.5f, 12.5f), rects.get(1));
        assertEquals(new Rect(0f, 25f, 10f, 12.5f), rects.get(2));
    }

    @Test
    void revisionCountsEditsOnly() {
        TextEditor editor = new TextEditor("abc");
        int start = editor.revision();
        editor.key(key(GLFW.GLFW_KEY_LEFT), clipboard);
        editor.key(shortcut(GLFW.GLFW_KEY_A), clipboard);
        assertEquals(start, editor.revision());
        editor.chars("x");
        assertEquals(start + 1, editor.revision());
        editor.chars("");
        assertEquals(start + 1, editor.revision());
    }

    @Test
    void numbersAreWrittenWithADotAndReadWithEitherSeparator() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertEquals("1.50", NumberText.format(1.5, 2));
            assertEquals("3", NumberText.format(2.6, 0));
        } finally {
            Locale.setDefault(previous);
        }
        assertEquals(OptionalDouble.of(1.5), NumberText.parse("1,5"));
        assertEquals(OptionalDouble.of(-2.25), NumberText.parse(" -2.25 "));
        assertTrue(NumberText.parse("").isEmpty());
        assertTrue(NumberText.parse("abc").isEmpty());
        assertTrue(NumberText.parse("NaN").isEmpty());
        assertTrue(NumberText.parse("1,2,3").isEmpty());
        assertTrue(NumberText.parse(null).isEmpty());
    }

    private static final class MemoryClipboard implements Clipboard {
        private String text = "";

        @Override
        public String read() {
            return text;
        }

        @Override
        public void write(String value) {
            text = value;
        }
    }
}
