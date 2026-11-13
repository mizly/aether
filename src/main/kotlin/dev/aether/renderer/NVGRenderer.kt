package dev.aether.renderer

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import com.mojang.renderpearl.util.UncheckedAutoCloseable
import dev.aether.renderer.canvas.Canvas
import dev.aether.renderer.canvas.Canvas.Paint
import dev.aether.util.AetherLang
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.font.TextRenderable
import java.nio.ByteBuffer

class NVGRenderer internal constructor(private val canvas: Canvas) {

    private val minecraftText: Font.GlyphVisitor
    private val images = HashMap<Int, Image>()
    private var nextImage = 1

    private data class Image(val texture: GpuTexture, val view: GpuTextureView)

    private var scissorStack: ScissorRegion? = null

    private val textWidthCache = object : LinkedHashMap<String, Float>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Float>): Boolean {
            return size > MAX_TEXT_WIDTH_CACHE
        }
    }
    private var lastTextKey: String? = null

    private var textScale = 1f

    init {
        minecraftText = object : Font.GlyphVisitor {
            override fun acceptGlyph(glyph: TextRenderable.Styled) { canvas.minecraftGlyph(glyph) }
            override fun acceptEffect(effect: TextRenderable) { canvas.minecraftGlyph(effect) }
        }
    }

    fun canvas(): Canvas = canvas

    internal fun beginFrame() {
        scissorStack = null
    }

    fun minecraftText(text: Font.PreparedText) {
        text.visit(minecraftText)
    }

    fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) {
        canvas.fill(canvas.path().rect(x, y, w, h), Paint.solid(color))
    }

    fun roundedRect(x: Float, y: Float, w: Float, h: Float, radius: Float, color: Int) {
        canvas.fill(canvas.path().roundedRect(x, y, w, h, radius), Paint.solid(color))
    }

    fun rectOutline(x: Float, y: Float, w: Float, h: Float, radius: Float, thickness: Float, color: Int) {
        val half = thickness / 2f
        val r = Math.min(radius, Math.min(w, h) / 2f)

        if (r < 0.5f) {
            rect(x - half, y - half, w + thickness, thickness, color)
            rect(x - half, y + h - half, w + thickness, thickness, color)
            rect(x - half, y + half, thickness, h - thickness, color)
            rect(x + w - half, y + half, thickness, h - thickness, color)
            return
        }

        canvas.stroke(fullOutline(x, y, w, h, r), Paint.solid(color), thickness)
    }

    private fun fullOutline(x: Float, y: Float, w: Float, h: Float, r: Float): Canvas.Path {
        return canvas.path()
            .moveTo(x + w / 2f, y)
            .lineTo(x + w - r, y)
            .arc(x + w - r, y + r, r, HALF_PI * 3, HALF_PI * 4, true)
            .lineTo(x + w, y + h - r)
            .arc(x + w - r, y + h - r, r, 0f, HALF_PI, true)
            .lineTo(x + r, y + h)
            .arc(x + r, y + h - r, r, HALF_PI, HALF_PI * 2, true)
            .lineTo(x, y + r)
            .arc(x + r, y + r, r, HALF_PI * 2, HALF_PI * 3, true)
            .lineTo(x + w / 2f, y)
    }

    private fun sideOutline(x: Float, y: Float, w: Float, h: Float, r: Float): Canvas.Path {
        return canvas.path()
            .moveTo(x + r, y)
            .arc(x + r, y + r, r, HALF_PI * 3, HALF_PI * 2, false)
            .lineTo(x, y + h - r)
            .arc(x + r, y + h - r, r, HALF_PI * 2, HALF_PI, false)
            .moveTo(x + w - r, y)
            .arc(x + w - r, y + r, r, HALF_PI * 3, HALF_PI * 4, true)
            .lineTo(x + w, y + h - r)
            .arc(x + w - r, y + h - r, r, 0f, HALF_PI, true)
    }

    fun rectOutlineSolid(x: Float, y: Float, w: Float, h: Float, radius: Float, thickness: Float, color: Int) {
        rectOutline(x, y, w, h, radius, thickness, opaqueFromAlpha(color))
    }

    fun rectOutlineVerticalSides(x: Float, y: Float, w: Float, h: Float, radius: Float, thickness: Float, color: Int, ratio: Float) {
        if (ratio <= 0f) return
        if (ratio >= 1f) {
            rectOutlineSolid(x, y, w, h, radius, thickness, color)
            return
        }
        val r = Math.min(radius, Math.min(w, h) / 2f)
        val half = thickness / 2f
        val c = opaqueFromAlpha(color)

        if (ratio <= 0.4f) {
            val pad = h / 2f - (ratio / 0.4f) * (h / 2f - half)
            canvas.save()
            canvas.intersectScissor(x - half, y + pad, w + thickness, h - pad * 2f)
            rectOutlineSolid(x, y, w, h, radius, thickness, color)
            canvas.restore()
        } else {
            canvas.stroke(sideOutline(x, y, w, h, r), Paint.solid(c), thickness)

            val segLen = ((ratio - 0.4f) / 0.6f) * (w / 2f - r)
            rect(x + r,              y - half, segLen, thickness, c)
            rect(x + w - r - segLen, y - half, segLen, thickness, c)
            rect(x + r,              y + h - half, segLen, thickness, c)
            rect(x + w - r - segLen, y + h - half, segLen, thickness, c)
        }
    }

    fun rectOutlineVerticalSidesGradient(x: Float, y: Float, w: Float, h: Float, radius: Float,
                                         thickness: Float, colorTL: Int, colorBR: Int, ratio: Float) {
        if (ratio <= 0f) return
        val paint = Paint.linear(x, y, x + w, y + h, opaqueFromAlpha(colorTL), opaqueFromAlpha(colorBR))
        val r = Math.min(radius, Math.min(w, h) / 2f)
        val half = thickness / 2f

        if (ratio >= 1f) {
            canvas.stroke(fullOutline(x, y, w, h, r), paint, thickness)
        } else if (ratio <= 0.4f) {
            val pad = h / 2f - (ratio / 0.4f) * (h / 2f - half)
            canvas.save()
            canvas.intersectScissor(x - half, y + pad, w + thickness, h - pad * 2f)
            canvas.stroke(fullOutline(x, y, w, h, r), paint, thickness)
            canvas.restore()
        } else {
            canvas.stroke(sideOutline(x, y, w, h, r), paint, thickness)
            val segLen = ((ratio - 0.4f) / 0.6f) * (w / 2f - r)
            canvas.fill(canvas.path()
                .rect(x + r,              y - half,     segLen, thickness)
                .rect(x + w - r - segLen, y - half,     segLen, thickness)
                .rect(x + r,              y + h - half, segLen, thickness)
                .rect(x + w - r - segLen, y + h - half, segLen, thickness), paint)
        }
    }

    fun circle(cx: Float, cy: Float, radius: Float, color: Int) {
        canvas.fill(canvas.path().circle(cx, cy, radius), Paint.solid(color))
    }

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, thickness: Float, color: Int) {
        canvas.stroke(canvas.path().moveTo(x1, y1).lineTo(x2, y2), Paint.solid(color), thickness)
    }

    fun polyline(points: FloatArray, count: Int, thickness: Float, color: Int) {
        if (count < 2) return
        val path = canvas.path().moveTo(points[0], points[1])
        for (i in 1 until count) path.lineTo(points[i * 2], points[i * 2 + 1])
        canvas.stroke(path, Paint.solid(color), thickness)
    }

    fun linearGradient(x: Float, y: Float, w: Float, h: Float, radius: Float,
                       colorTop: Int, colorBottom: Int) {
        canvas.fill(canvas.path().roundedRect(x, y, w, h, radius), Paint.linear(x, y, x, y + h, colorTop, colorBottom))
    }

    fun horizontalGradient(x: Float, y: Float, w: Float, h: Float, radius: Float,
                           colorLeft: Int, colorRight: Int) {
        canvas.fill(canvas.path().roundedRect(x, y, w, h, radius), Paint.linear(x, y, x + w, y, colorLeft, colorRight))
    }

    fun shadow(x: Float, y: Float, w: Float, h: Float, radius: Float, blur: Float, color: Int) {
        canvas.fillBoxGradient(canvas.path().rect(x - blur, y - blur * 0.5f, w + blur * 2, h + blur * 2),
            x, y + blur * 0.5f, w, h, radius + blur * 0.5f, blur, color, color and 0x00FFFFFF)
    }

    fun glow(x: Float, y: Float, w: Float, h: Float, radius: Float, color: Int, intensity: Float) {
        val intensityClamped = Math.max(0.1f, Math.min(1f, intensity))
        val layers = (intensityClamped * 4).toInt() + 1
        val baseAlpha = (color shr 24) and 0xFF

        for (i in 0 until layers) {
            val spread = (i + 1) * 6f * intensityClamped
            val feather = spread * 1.5f
            val layerAlpha = (baseAlpha * (1f - i.toFloat() / layers) * intensityClamped).toInt()
            if (layerAlpha < 2) continue

            canvas.fillBoxGradient(canvas.path().rect(x - spread, y - spread, w + spread * 2, h + spread * 2),
                x, y, w, h, radius, feather, (layerAlpha shl 24) or (color and 0x00FFFFFF), color and 0x00FFFFFF)
        }
    }

    fun glowCircle(cx: Float, cy: Float, radius: Float, color: Int, intensity: Float) {
        val intensityClamped = Math.max(0.1f, Math.min(1f, intensity))
        val layers = (intensityClamped * 3).toInt() + 1
        val baseAlpha = (color shr 24) and 0xFF

        for (i in 0 until layers) {
            val spread = (i + 1) * 8f * intensityClamped
            val layerAlpha = (baseAlpha * (1f - i.toFloat() / layers) * intensityClamped).toInt()
            if (layerAlpha < 2) continue

            canvas.fillRadialGradient(cx, cy, radius * 0.5f, radius + spread,
                (layerAlpha shl 24) or (color and 0x00FFFFFF), color and 0x00FFFFFF)
        }

        circle(cx, cy, radius, color)
    }

    fun blur(x: Float, y: Float, w: Float, h: Float, blurRadius: Float) {
        val clampedBlur = Math.min(blurRadius, 30f)
        rect(x, y, w, h, 0x40000000)
        shadow(x, y, w, h, 0f, clampedBlur, 0x28000000)
        rect(x, y, w, h, 0x0CFFFFFF)
    }

    fun blur(x: Float, y: Float, w: Float, h: Float, radius: Float, blurRadius: Float) {
        val clampedBlur = Math.min(blurRadius, 30f)
        roundedRect(x, y, w, h, radius, 0x40000000)
        canvas.fillBoxGradient(canvas.path().roundedRect(x, y, w, h, radius),
            x, y + clampedBlur * 0.5f, w, h, radius + clampedBlur * 0.5f, clampedBlur, 0x28000000, 0x00000000)
        roundedRect(x, y, w, h, radius, 0x0CFFFFFF)
    }

    fun blurCircle(cx: Float, cy: Float, radius: Float, blurRadius: Float) {
        val b = Math.min(Math.max(blurRadius, 0f), 30f)

        circle(cx, cy, radius, 0x40000000)

        val STEPS = 5
        val MAX_A = 0x28
        for (i in 1..STEPS) {
            val t = i / STEPS.toFloat()
            val rr = radius + t * b
            val a = Math.round(MAX_A * (1f - t))
            circle(cx, cy, rr, (a and 0xFF) shl 24)
        }

        circle(cx, cy, radius, 0x0CFFFFFF)
    }

    fun blurRingApprox(cx: Float, cy: Float, radius: Float, blurRadius: Float, alpha: Float) {
        val b = Math.min(Math.max(blurRadius, 0f), 30f)
        val STEPS = 5
        val maxA = Math.round(Math.max(0f, Math.min(alpha, 1f)) * 0x28)

        for (i in 1..STEPS) {
            val t = i / STEPS.toFloat()
            val rr = radius + t * b
            val a = Math.round(maxA * (1f - t))
            circle(cx, cy, rr, (a and 0xFF) shl 24)
        }
    }

    fun circleOutline(cx: Float, cy: Float, radius: Float, strokeWidth: Float, color: Int) {
        canvas.stroke(canvas.path().circle(cx, cy, Math.max(0.5f, radius)), Paint.solid(color), Math.max(0.5f, strokeWidth))
    }

    fun renderSVG(resourcePath: String, x: Float, y: Float, width: Float, height: Float, color: Int) {
        SVGRenderer.render(canvas, resourcePath, x, y, width, height, color)
    }

    fun createImageRGBA(width: Int, height: Int, pixels: ByteBuffer): Int {
        if (width <= 0 || height <= 0) return -1
        val device = RenderSystem.getDevice()
        val texture = device.createTexture({ "Aether image" }, GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_COPY_DST,
            GpuFormat.RGBA8_UNORM, width, height, 1, 1)
        device.createCommandEncoder().writeToTexture(texture, pixels, 0, 0, 0, 0, width, height)
        val handle = nextImage++
        images[handle] = Image(texture, device.createTextureView(texture))
        return handle
    }

    fun deleteImage(handle: Int) {
        val image = images.remove(handle)
        if (image != null) {
            UncheckedAutoCloseable.safeClose(image.view)
            UncheckedAutoCloseable.safeClose(image.texture)
        }
    }

    internal fun deleteAllImages() {
        for (image in images.values) {
            UncheckedAutoCloseable.safeClose(image.view)
            UncheckedAutoCloseable.safeClose(image.texture)
        }
        images.clear()
    }

    fun image(handle: Int, x: Float, y: Float, width: Float, height: Float, radius: Float, alpha: Float) {
        val image = images[handle]
        if (image == null) return
        val a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f)
        canvas.image(image.view, x, y, width, height, radius, (a shl 24) or 0x00FFFFFF)
    }

    fun text(fontName: String, text: String, x: Float, y: Float, size: Float, color: Int) {
        textLiteral(fontName, AetherLang.localize(text), x, y, size * textScale, color)
    }

    fun textLiteral(fontName: String, text: String, x: Float, y: Float, size: Float, color: Int) {
        val fontId = canvas.fontId(fontName)
        if (fontId == -1) return
        canvas.text(fontId, text, x, y + 0.5f, size, color)
    }

    fun textCentered(fontName: String, text: String, x: Float, y: Float, w: Float, h: Float,
                     size: Float, color: Int) {
        val tw = textWidth(fontName, text, size)
        val tx = x + (w - tw) / 2f
        val renderedSize = size * textScale
        val ty = y + (h - renderedSize) / 2f
        text(fontName, text, tx, ty, size, color)
    }

    fun textRight(fontName: String, text: String, x: Float, y: Float, w: Float,
                  size: Float, color: Int) {
        val tw = textWidth(fontName, text, size)
        text(fontName, text, x + w - tw, y, size, color)
    }

    fun textWidth(fontName: String, text: String, size: Float): Float {
        return textWidthLiteral(fontName, AetherLang.localize(text), size * textScale)
    }

    fun textWidthLiteral(fontName: String, text: String, size: Float): Float {
        val key = "$fontName|$text|$size"
        if (key == lastTextKey) {
            return textWidthCache[key] ?: 0f
        }
        val fontId = canvas.fontId(fontName)
        if (fontId == -1) return 0f
        val w = canvas.textWidth(fontId, text, size)
        textWidthCache[key] = w
        lastTextKey = key
        return w
    }

    fun wrapTextToWidth(fontName: String, text: String, size: Float, maxWidth: Float): List<String> {
        val localizedText = AetherLang.localize(text)
        val lines = ArrayList<String>()
        if (localizedText == null || localizedText.isBlank()) {
            lines.add("")
            return lines
        }
        if (maxWidth <= 0f) {
            lines.add(localizedText)
            return lines
        }

        for (paragraph in localizedText.split("\\R".toRegex(), -1)) {
            if (paragraph.isBlank()) {
                lines.add("")
                continue
            }

            var current = ""
            for (word in paragraph.trim().split("\\s+".toRegex())) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (current.isEmpty() || textWidth(fontName, candidate, size) <= maxWidth) {
                    current = candidate
                    continue
                }

                lines.add(current)
                current = word
                while (textWidth(fontName, current, size) > maxWidth && current.length > 1) {
                    val split = fittingPrefixLength(fontName, current, size, maxWidth)
                    lines.add(current.substring(0, split))
                    current = current.substring(split)
                }
            }

            if (current.isNotEmpty()) {
                lines.add(current)
            }
        }

        return if (lines.isEmpty()) listOf(localizedText) else lines
    }

    private fun fittingPrefixLength(fontName: String, text: String, size: Float, maxWidth: Float): Int {
        var low = 1
        var high = text.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (textWidth(fontName, text.substring(0, mid), size) <= maxWidth) {
                low = mid
            } else {
                high = mid - 1
            }
        }
        return Math.max(1, low)
    }

    fun pushScissor(x: Float, y: Float, w: Float, h: Float) {
        scissorStack = ScissorRegion(scissorStack, x, y, x + w, y + h)
        scissorStack!!.apply(canvas)
    }

    fun popScissor() {
        canvas.resetScissor()
        scissorStack = if (scissorStack != null) scissorStack!!.parent else null
        if (scissorStack != null) scissorStack!!.apply(canvas)
    }

    fun scissor(x: Float, y: Float, w: Float, h: Float) {
        canvas.scissor(x, y, w, h)
    }

    fun resetScissor() {
        canvas.resetScissor()
    }

    fun save() { canvas.save() }

    fun restore() { canvas.restore() }

    fun translate(dx: Float, dy: Float) { canvas.translate(dx, dy) }

    fun scale(sx: Float, sy: Float) { canvas.scale(sx, sy) }

    fun skewX(radians: Float) { canvas.skewX(radians) }

    fun globalAlpha(alpha: Float) { canvas.globalAlpha(Math.max(0f, Math.min(1f, alpha))) }

    fun setTextScale(s: Float) {
        if (this.textScale != s) {
            this.textScale = s
            textWidthCache.clear()
            lastTextKey = null
        }
    }

    private class ScissorRegion(val parent: ScissorRegion?, x1: Float, y1: Float, x2: Float, y2: Float) {
        val x1: Float
        val y1: Float
        val x2: Float
        val y2: Float

        init {
            if (parent != null) {
                this.x1 = Math.max(x1, parent.x1)
                this.y1 = Math.max(y1, parent.y1)
                this.x2 = Math.min(x2, parent.x2)
                this.y2 = Math.min(y2, parent.y2)
            } else {
                this.x1 = x1
                this.y1 = y1
                this.x2 = x2
                this.y2 = y2
            }
        }

        fun apply(canvas: Canvas) {
            val w = Math.max(0f, x2 - x1)
            val h = Math.max(0f, y2 - y1)
            canvas.scissor(x1, y1, w, h)
        }
    }

    companion object {
        private const val HALF_PI = (Math.PI / 2).toFloat()
        private const val MAX_TEXT_WIDTH_CACHE = 512

        private fun opaqueFromAlpha(argb: Int): Int {
            val a = (argb shr 24) and 0xFF
            if (a == 0xFF) return argb
            val r = ((argb shr 16) and 0xFF) * a / 255
            val g = ((argb shr 8) and 0xFF) * a / 255
            val b = (argb and 0xFF) * a / 255
            return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
        }
    }
}