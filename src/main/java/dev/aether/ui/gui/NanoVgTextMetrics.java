package dev.aether.ui.gui;

import dev.aether.renderer.NanoVGManager;
import org.lwjgl.nanovg.NVGGlyphPosition;
import org.lwjgl.nanovg.NanoVG;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Arrays;

// measures with the live nanovg context, so the canvas only calls it inside a frame
final class NanoVgTextMetrics implements TextMetrics {
    private final long vg;
    private final float[] bounds = new float[4];
    private final float[] ascender = new float[1];
    private final float[] descender = new float[1];
    private final float[] lineHeight = new float[1];

    NanoVgTextMetrics(long vg) {
        this.vg = vg;
    }

    @Override
    public float width(String font, float size, String text) {
        if (text.isEmpty() || !select(font, size)) {
            return 0f;
        }
        return NanoVG.nvgTextBounds(vg, 0f, 0f, text, bounds);
    }

    @Override
    public float lineHeight(String font, float size) {
        if (!select(font, size)) {
            return size;
        }
        NanoVG.nvgTextMetrics(vg, ascender, descender, lineHeight);
        return lineHeight[0];
    }

    @Override
    public float[] caretX(String font, float size, String text) {
        float[] carets = new float[text.length() + 1];
        if (text.isEmpty() || !select(font, size)) {
            return carets;
        }
        ByteBuffer utf8 = MemoryUtil.memUTF8(text, false);
        NVGGlyphPosition.Buffer glyphs = NVGGlyphPosition.malloc(text.codePointCount(0, text.length()));
        try {
            int count = NanoVG.nvgTextGlyphPositions(vg, 0f, 0f, utf8, glyphs);
            int[] charAt = utf16IndexByUtf8Offset(text, utf8.remaining());
            long base = MemoryUtil.memAddress(utf8);
            Arrays.fill(carets, Float.NaN);
            carets[0] = 0f;
            for (int i = 0; i < count; i++) {
                long offset = glyphs.get(i).str() - base;
                if (offset >= 0 && offset < charAt.length) {
                    carets[charAt[(int) offset]] = glyphs.get(i).x();
                }
            }
            carets[text.length()] = NanoVG.nvgTextBounds(vg, 0f, 0f, text, bounds);
            // the low half of a surrogate pair has no glyph of its own and shares its caret with the high half
            for (int i = 1; i < text.length(); i++) {
                if (Float.isNaN(carets[i])) {
                    carets[i] = carets[i - 1];
                }
            }
            return carets;
        } finally {
            glyphs.free();
            MemoryUtil.memFree(utf8);
        }
    }

    // nanovg reports glyphs by utf-8 byte pointer; this maps each byte offset back to its java char index
    static int[] utf16IndexByUtf8Offset(String text, int byteLength) {
        int[] charAt = new int[byteLength + 1];
        int offset = 0;
        for (int i = 0; i < text.length() && offset < byteLength; ) {
            int codePoint = text.codePointAt(i);
            int width = codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
            for (int b = 0; b < width && offset + b < byteLength; b++) {
                charAt[offset + b] = i;
            }
            offset += width;
            i += Character.charCount(codePoint);
        }
        charAt[byteLength] = text.length();
        return charAt;
    }

    private boolean select(String font, float size) {
        int id = NanoVGManager.getFontId(font);
        if (id < 0) {
            return false;
        }
        NanoVG.nvgFontFaceId(vg, id);
        NanoVG.nvgFontSize(vg, size);
        return true;
    }
}
