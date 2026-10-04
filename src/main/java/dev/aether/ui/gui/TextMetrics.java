package dev.aether.ui.gui;

// text measurement in local units, literal text only (callers localise first)
public interface TextMetrics {
    float width(String font, float size, String text);

    float lineHeight(String font, float size);

    // one entry per caret position 0..text.length(): x[i] is the left edge of char i, the last entry the advance
    float[] caretX(String font, float size, String text);
}
