package dev.aether.renderer

import dev.aether.renderer.canvas.Canvas
import dev.aether.util.AetherResources
import org.lwjgl.nanovg.NSVGImage
import org.lwjgl.nanovg.NanoSVG
import org.lwjgl.system.MemoryUtil
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.HashMap
import java.util.regex.Pattern
import javax.xml.parsers.DocumentBuilderFactory

internal class SVGRenderer private constructor() {
    companion object {
        private val SVG_LENGTH_PATTERN = Pattern.compile("^\\s*([+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)(?:[eE][+-]?\\d+)?)\\s*([a-zA-Z%]*)\\s*$")
        private val imageCache: MutableMap<String, Canvas.AtlasImage> = HashMap()

        @JvmStatic internal fun render(canvas: Canvas, resourcePath: String, x: Float, y: Float, width: Float, height: Float, argbColor: Int) {
            val px = NanoVGManager.getPxRatio()
            val physW = Math.max(1, Math.ceil((width * px).toDouble()).toInt())
            val physH = Math.max(1, Math.ceil((height * px).toDouble()).toInt())
            val cacheKey = "$resourcePath@${physW}x$physH"
            var image = imageCache[cacheKey]
            if (!canvas.isCurrent(image)) {
                image = loadSVG(canvas, resourcePath, physW, physH) ?: return
                imageCache[cacheKey] = image
            }
            canvas.drawAtlasImage(image, x, y, width, height, argbColor)
        }

        private fun loadSVG(canvas: Canvas, resourcePath: String, width: Int, height: Int): Canvas.AtlasImage? {
            val svgText: String
            try {
                AetherResources.open(resourcePath).use { input ->
                    if (input == null) {
                        System.err.println("[Aether] SVG not found: $resourcePath")
                        return null
                    }
                    svgText = String(input.readAllBytes(), StandardCharsets.UTF_8)
                }
            } catch (e: IOException) {
                System.err.println("[Aether] IOException reading SVG $resourcePath: ${e.message}")
                return null
            }
            val svgImage: NSVGImage? = NanoSVG.nsvgParse(svgText, "px", 96f)
            if (svgImage == null) {
                System.err.println("[Aether] NanoSVG parse failed: $resourcePath")
                return null
            }
            val svgSize = readSvgSize(svgText)
            val svgW = svgSize[0]
            val svgH = svgSize[1]
            if (svgW <= 0f || svgH <= 0f) {
                NanoSVG.nsvgDelete(svgImage)
                System.err.println("[Aether] SVG has no usable size metadata: $resourcePath")
                return null
            }
            val rasterizer = NanoSVG.nsvgCreateRasterizer()
            if (rasterizer == MemoryUtil.NULL) {
                NanoSVG.nsvgDelete(svgImage)
                System.err.println("[Aether] NanoSVG could not create rasterizer")
                return null
            }
            val scale = Math.min(width.toFloat() / svgW, height.toFloat() / svgH)
            val rW = Math.max(1, (svgW * scale).toInt())
            val rH = Math.max(1, (svgH * scale).toInt())
            val pixels: ByteBuffer = MemoryUtil.memAlloc(rW * rH * 4)
            try {
                NanoSVG.nsvgRasterize(rasterizer, svgImage, 0f, 0f, scale, pixels, rW, rH, rW * 4)
                val image = canvas.uploadToAtlas(pixels, rW, rH)
                if (image == null) System.err.println("[Aether] SVG too large for the UI atlas: $resourcePath")
                return image
            } finally {
                MemoryUtil.memFree(pixels)
                NanoSVG.nsvgDeleteRasterizer(rasterizer)
                NanoSVG.nsvgDelete(svgImage)
            }
        }

        private fun readSvgSize(svgText: String): FloatArray {
            try {
                val factory = DocumentBuilderFactory.newInstance()
                factory.isNamespaceAware = true
                factory.isCoalescing = true
                factory.isExpandEntityReferences = false
                try {
                    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                } catch (_: Exception) {
                }
                val document = factory.newDocumentBuilder().parse(InputSource(StringReader(svgText)))
                val root: Element? = document.documentElement
                if (root == null || !"svg".equals(root.localName ?: root.tagName, ignoreCase = true)) return floatArrayOf(-1f, -1f)
                val width = parseSvgLength(root.getAttribute("width"))
                val height = parseSvgLength(root.getAttribute("height"))
                if (width != null && width > 0f && height != null && height > 0f) return floatArrayOf(width, height)
                val viewBox = root.getAttribute("viewBox")
                if (viewBox.isNotBlank()) {
                    val parts = viewBox.trim().split(Regex("[,\\s]+"))
                    if (parts.size == 4) {
                        val vbWidth = parts[2].toFloat()
                        val vbHeight = parts[3].toFloat()
                        if (vbWidth > 0f && vbHeight > 0f) return floatArrayOf(vbWidth, vbHeight)
                    }
                }
            } catch (_: Exception) {
            }
            return floatArrayOf(-1f, -1f)
        }

        private fun parseSvgLength(value: String?): Float? {
            if (value == null || value.isBlank()) return null
            val matcher = SVG_LENGTH_PATTERN.matcher(value)
            if (!matcher.matches()) return null
            val magnitude = matcher.group(1).toFloat()
            return when (matcher.group(2).lowercase()) {
                "", "px" -> magnitude
                "pt" -> magnitude * (96f / 72f)
                "pc" -> magnitude * 16f
                "mm" -> magnitude * (96f / 25.4f)
                "cm" -> magnitude * (96f / 2.54f)
                "in" -> magnitude * 96f
                else -> null
            }
        }

        @JvmStatic internal fun destroy() { imageCache.clear() }
    }
}
