package dev.aether.renderer.canvas

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import org.lwjgl.stb.STBTTFontinfo
import org.lwjgl.stb.STBTruetype
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.util.ArrayList
import java.util.HashMap

// stb_truetype mirrors of fontstash's maths, so text measures and lands exactly where nanovg put it
internal class CanvasFonts(private val atlas: UiAtlas) {
    companion object {
        // fontstash pads glyph bitmaps by 2 and samples 1px of it, keeping linear filtering off the edges
        private const val PAD = 1
        private const val MAX_FALLBACKS = 20
    }

    private val faces: MutableList<Face> = ArrayList()
    private val ids: MutableMap<String, Int> = HashMap()
    private val glyphs = Long2ObjectOpenHashMap<Glyph>()
    private var glyphGeneration: Int = -1

    internal class Face private constructor(val id: Int, val data: ByteBuffer, val info: STBTTFontinfo) {
        val ascender: Float
        val fallbacks: MutableList<Face> = ArrayList()

        init {
            val ascent = IntArray(1)
            val descent = IntArray(1)
            val lineGap = IntArray(1)
            STBTruetype.stbtt_GetFontVMetrics(info, ascent, descent, lineGap)
            val height = ascent[0] - descent[0]
            ascender = if (height == 0) 0f else ascent[0].toFloat() / height
        }

        companion object {
            fun create(id: Int, data: ByteBuffer, info: STBTTFontinfo) = Face(id, data, info)
        }
    }

    internal class Glyph(
        val face: Face,
        val index: Int,
        // advance in physical pixels, rounded the same way fontstash rounds it
        val advance: Int,
        val x0: Int,
        val y0: Int,
        val width: Int,
        val height: Int,
        val scale: Float
    ) {
        var region: UiAtlas.Region? = null
    }

    fun interface GlyphSink {
        fun glyph(region: UiAtlas.Region, x0: Float, y0: Float, x1: Float, y1: Float)
    }

    // data must be a direct buffer that stays alive as long as the font does
    fun load(name: String, data: ByteBuffer): Int {
        val existing = ids[name]
        if (existing != null) return existing
        val info = STBTTFontinfo.malloc()
        val offset = STBTruetype.stbtt_GetFontOffsetForIndex(data, 0)
        if (offset < 0 || !STBTruetype.stbtt_InitFont(info, data, offset)) {
            info.free()
            return -1
        }
        val face = Face.create(faces.size, data, info)
        faces.add(face)
        ids[name] = face.id
        return face.id
    }

    fun id(name: String): Int = ids[name] ?: -1

    fun addFallback(baseId: Int, fallbackId: Int) {
        if (baseId < 0 || fallbackId < 0 || baseId >= faces.size || fallbackId >= faces.size) return
        val base = faces[baseId]
        if (base.fallbacks.size < MAX_FALLBACKS) base.fallbacks.add(faces[fallbackId])
    }

    fun close() {
        for (face in faces) {
            face.info.free()
            MemoryUtil.memFree(face.data)
        }
        faces.clear()
        ids.clear()
        glyphs.clear()
    }

    // returns the advance in local units; sink may be null to only measure
    fun layout(fontId: Int, text: String?, x: Float, y: Float, size: Float, scale: Float, sink: GlyphSink?): Float {
        if (fontId < 0 || fontId >= faces.size || text == null || text.isEmpty()) return 0f
        if (glyphGeneration != atlas.generation()) {
            glyphs.clear()
            glyphGeneration = atlas.generation()
        }
        val font = faces[fontId]
        // fontstash quantises sizes to tenths of a pixel
        val isize = (size * scale * 10f).toInt()
        if (isize < 2) return 0f
        val pixelSize = isize / 10f
        val fontScale = STBTruetype.stbtt_ScaleForPixelHeight(font.info, pixelSize)
        val invscale = 1f / scale
        val startX = x * scale
        var penX = startX
        val baseline = y * scale + font.ascender * pixelSize
        var prevIndex = -1
        var prevFace: Face? = null

        var i = 0
        while (i < text.length) {
            val codepoint = text.codePointAt(i)
            i += Character.charCount(codepoint)
            val glyph = glyph(font, codepoint, isize, pixelSize)
            if (glyph == null) {
                prevIndex = -1
                continue
            }
            if (prevIndex != -1 && prevFace === glyph.face) {
                val kern = STBTruetype.stbtt_GetGlyphKernAdvance(glyph.face.info, prevIndex, glyph.index) * glyph.scale
                penX += (kern + 0.5f).toInt()
            }
            if (sink != null && glyph.width > 0 && glyph.height > 0) {
                if (glyph.region == null) glyph.region = rasterize(glyph)
                val region = glyph.region
                if (region != null) {
                    val rx = Math.floor((penX + glyph.x0 - PAD).toDouble()).toFloat()
                    val ry = Math.floor((baseline + glyph.y0 - PAD).toDouble()).toFloat()
                    sink.glyph(region, rx * invscale, ry * invscale,
                        (rx + region.width()) * invscale, (ry + region.height()) * invscale)
                }
            }
            penX += glyph.advance
            prevIndex = glyph.index
            prevFace = glyph.face
        }
        return (penX - startX) * invscale
    }

    private fun glyph(font: Face, codepoint: Int, isize: Int, pixelSize: Float): Glyph? {
        var face = font
        var index = STBTruetype.stbtt_FindGlyphIndex(font.info, codepoint)
        if (index == 0) {
            for (fallback in font.fallbacks) {
                val fallbackIndex = STBTruetype.stbtt_FindGlyphIndex(fallback.info, codepoint)
                if (fallbackIndex != 0) {
                    face = fallback
                    index = fallbackIndex
                    break
                }
            }
        }
        val key = (face.id.toLong() shl 48) or ((isize and 0xFFFF).toLong() shl 32) or (index.toLong() and 0xFFFFFFFFL)
        val cached = glyphs.get(key)
        if (cached != null) return cached

        val scale = STBTruetype.stbtt_ScaleForPixelHeight(face.info, pixelSize)
        val advance = IntArray(1)
        val lsb = IntArray(1)
        STBTruetype.stbtt_GetGlyphHMetrics(face.info, index, advance, lsb)
        val x0 = IntArray(1)
        val y0 = IntArray(1)
        val x1 = IntArray(1)
        val y1 = IntArray(1)
        STBTruetype.stbtt_GetGlyphBitmapBox(face.info, index, scale, scale, x0, y0, x1, y1)
        // fontstash stores the advance in tenths and rounds when it steps the pen
        val xadv10 = (scale * advance[0] * 10f).toInt()
        val stepped = (xadv10 / 10f + 0.5f).toInt()
        val result = Glyph(face, index, stepped, x0[0], y0[0], x1[0] - x0[0], y1[0] - y0[0], scale)
        glyphs.put(key, result)
        return result
    }

    private fun rasterize(glyph: Glyph): UiAtlas.Region? {
        val w = glyph.width + PAD * 2
        val h = glyph.height + PAD * 2
        val coverage = MemoryUtil.memCalloc(w * h)
        val rgba = MemoryUtil.memAlloc(w * h * 4)
        try {
            val inner = MemoryUtil.memSlice(coverage, PAD * w + PAD, coverage.capacity() - (PAD * w + PAD))
            STBTruetype.stbtt_MakeGlyphBitmap(glyph.face.info, inner, glyph.width, glyph.height, w, glyph.scale, glyph.scale, glyph.index)
            for (i in 0 until w * h) {
                rgba.put(i * 4, 0xFF.toByte())
                rgba.put(i * 4 + 1, 0xFF.toByte())
                rgba.put(i * 4 + 2, 0xFF.toByte())
                rgba.put(i * 4 + 3, coverage.get(i))
            }
            return atlas.upload(rgba, w, h)
        } finally {
            MemoryUtil.memFree(coverage)
            MemoryUtil.memFree(rgba)
        }
    }
}
