package dev.aether.renderer.canvas;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTruetype;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// stb_truetype mirrors of fontstash's maths, so text measures and lands exactly where nanovg put it
public final class CanvasFonts {
    // fontstash pads glyph bitmaps by 2 and samples 1px of it, keeping linear filtering off the edges
    private static final int PAD = 1;
    private static final int MAX_FALLBACKS = 20;

    private final UiAtlas atlas;
    private final List<Face> faces = new ArrayList<>();
    private final Map<String, Integer> ids = new HashMap<>();
    private final Long2ObjectOpenHashMap<Glyph> glyphs = new Long2ObjectOpenHashMap<>();
    private int glyphGeneration = -1;

    CanvasFonts(UiAtlas atlas) {
        this.atlas = atlas;
    }

    static final class Face {
        final int id;
        final ByteBuffer data;
        final STBTTFontinfo info;
        final float ascender;
        final List<Face> fallbacks = new ArrayList<>();

        private Face(int id, ByteBuffer data, STBTTFontinfo info) {
            this.id = id;
            this.data = data;
            this.info = info;
            int[] ascent = new int[1];
            int[] descent = new int[1];
            int[] lineGap = new int[1];
            STBTruetype.stbtt_GetFontVMetrics(info, ascent, descent, lineGap);
            float height = ascent[0] - descent[0];
            this.ascender = height == 0 ? 0f : ascent[0] / height;
        }
    }

    static final class Glyph {
        final Face face;
        final int index;
        // advance in physical pixels, rounded the same way fontstash rounds it
        final int advance;
        final int x0;
        final int y0;
        final int width;
        final int height;
        final float scale;
        UiAtlas.Region region;

        private Glyph(Face face, int index, int advance, int x0, int y0, int width, int height, float scale) {
            this.face = face;
            this.index = index;
            this.advance = advance;
            this.x0 = x0;
            this.y0 = y0;
            this.width = width;
            this.height = height;
            this.scale = scale;
        }
    }

    interface GlyphSink {
        void glyph(UiAtlas.Region region, float x0, float y0, float x1, float y1);
    }

    // data must be a direct buffer that stays alive as long as the font does
    int load(String name, ByteBuffer data) {
        Integer existing = ids.get(name);
        if (existing != null) return existing;
        STBTTFontinfo info = STBTTFontinfo.malloc();
        int offset = STBTruetype.stbtt_GetFontOffsetForIndex(data, 0);
        if (offset < 0 || !STBTruetype.stbtt_InitFont(info, data, offset)) {
            info.free();
            return -1;
        }
        Face face = new Face(faces.size(), data, info);
        faces.add(face);
        ids.put(name, face.id);
        return face.id;
    }

    int id(String name) {
        return ids.getOrDefault(name, -1);
    }

    void addFallback(int baseId, int fallbackId) {
        if (baseId < 0 || fallbackId < 0 || baseId >= faces.size() || fallbackId >= faces.size()) return;
        Face base = faces.get(baseId);
        if (base.fallbacks.size() < MAX_FALLBACKS) base.fallbacks.add(faces.get(fallbackId));
    }

    void close() {
        for (Face face : faces) {
            face.info.free();
            MemoryUtil.memFree(face.data);
        }
        faces.clear();
        ids.clear();
        glyphs.clear();
    }

    // returns the advance in local units; sink may be null to only measure
    float layout(int fontId, String text, float x, float y, float size, float scale, GlyphSink sink) {
        if (fontId < 0 || fontId >= faces.size() || text == null || text.isEmpty()) return 0f;
        if (glyphGeneration != atlas.generation()) {
            glyphs.clear();
            glyphGeneration = atlas.generation();
        }
        Face font = faces.get(fontId);
        // fontstash quantises sizes to tenths of a pixel
        int isize = (int) (size * scale * 10f);
        if (isize < 2) return 0f;
        float pixelSize = isize / 10f;
        float fontScale = STBTruetype.stbtt_ScaleForPixelHeight(font.info, pixelSize);
        float invscale = 1f / scale;

        float startX = x * scale;
        float penX = startX;
        float baseline = y * scale + font.ascender * pixelSize;
        int prevIndex = -1;
        Face prevFace = null;

        for (int i = 0; i < text.length(); ) {
            int codepoint = text.codePointAt(i);
            i += Character.charCount(codepoint);
            Glyph glyph = glyph(font, codepoint, isize, pixelSize);
            if (glyph == null) {
                prevIndex = -1;
                continue;
            }
            if (prevIndex != -1 && prevFace == glyph.face) {
                float kern = STBTruetype.stbtt_GetGlyphKernAdvance(glyph.face.info, prevIndex, glyph.index) * glyph.scale;
                penX += (int) (kern + 0.5f);
            }
            if (sink != null && glyph.width > 0 && glyph.height > 0) {
                if (glyph.region == null) glyph.region = rasterize(glyph);
                if (glyph.region != null) {
                    float rx = (float) Math.floor(penX + glyph.x0 - PAD);
                    float ry = (float) Math.floor(baseline + glyph.y0 - PAD);
                    sink.glyph(glyph.region, rx * invscale, ry * invscale,
                            (rx + glyph.region.width()) * invscale, (ry + glyph.region.height()) * invscale);
                }
            }
            penX += glyph.advance;
            prevIndex = glyph.index;
            prevFace = glyph.face;
        }
        return (penX - startX) * invscale;
    }

    private Glyph glyph(Face font, int codepoint, int isize, float pixelSize) {
        Face face = font;
        int index = STBTruetype.stbtt_FindGlyphIndex(font.info, codepoint);
        if (index == 0) {
            for (Face fallback : font.fallbacks) {
                int fallbackIndex = STBTruetype.stbtt_FindGlyphIndex(fallback.info, codepoint);
                if (fallbackIndex != 0) {
                    face = fallback;
                    index = fallbackIndex;
                    break;
                }
            }
        }
        long key = ((long) face.id << 48) | ((long) (isize & 0xFFFF) << 32) | (index & 0xFFFFFFFFL);
        Glyph glyph = glyphs.get(key);
        if (glyph != null) return glyph;

        float scale = STBTruetype.stbtt_ScaleForPixelHeight(face.info, pixelSize);
        int[] advance = new int[1];
        int[] lsb = new int[1];
        STBTruetype.stbtt_GetGlyphHMetrics(face.info, index, advance, lsb);
        int[] x0 = new int[1];
        int[] y0 = new int[1];
        int[] x1 = new int[1];
        int[] y1 = new int[1];
        STBTruetype.stbtt_GetGlyphBitmapBox(face.info, index, scale, scale, x0, y0, x1, y1);
        // fontstash stores the advance in tenths and rounds when it steps the pen
        int xadv10 = (int) (scale * advance[0] * 10f);
        int stepped = (int) (xadv10 / 10f + 0.5f);
        glyph = new Glyph(face, index, stepped, x0[0], y0[0], x1[0] - x0[0], y1[0] - y0[0], scale);
        glyphs.put(key, glyph);
        return glyph;
    }

    private UiAtlas.Region rasterize(Glyph glyph) {
        int w = glyph.width + PAD * 2;
        int h = glyph.height + PAD * 2;
        ByteBuffer coverage = MemoryUtil.memCalloc(w * h);
        ByteBuffer rgba = MemoryUtil.memAlloc(w * h * 4);
        try {
            ByteBuffer inner = MemoryUtil.memSlice(coverage, PAD * w + PAD, coverage.capacity() - (PAD * w + PAD));
            STBTruetype.stbtt_MakeGlyphBitmap(glyph.face.info, inner, glyph.width, glyph.height, w, glyph.scale, glyph.scale, glyph.index);
            for (int i = 0; i < w * h; i++) {
                rgba.put(i * 4, (byte) 0xFF);
                rgba.put(i * 4 + 1, (byte) 0xFF);
                rgba.put(i * 4 + 2, (byte) 0xFF);
                rgba.put(i * 4 + 3, coverage.get(i));
            }
            return atlas.upload(rgba, w, h);
        } finally {
            MemoryUtil.memFree(coverage);
            MemoryUtil.memFree(rgba);
        }
    }
}
