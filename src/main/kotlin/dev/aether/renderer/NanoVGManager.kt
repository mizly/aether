package dev.aether.renderer

import com.mojang.renderpearl.api.textures.GpuTextureView
import com.mojang.renderpearl.util.UncheckedAutoCloseable
import dev.aether.renderer.canvas.Canvas
import dev.aether.ui.util.Fonts
import dev.aether.util.AetherResources
import net.minecraft.client.Minecraft
import org.lwjgl.system.MemoryUtil
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

class NanoVGManager private constructor() {
    companion object {
        private var canvas: Canvas? = null
        private var renderer: NVGRenderer? = null
        private var initialized = false
        private var drawing = false
        private var pxRatio = 1f
        private var frameTarget: GpuTextureView? = null
        private const val UNICODE_FALLBACK_FONT = "Aether-Unicode-Fallback"

        @JvmStatic fun ensureInitialized(): Boolean {
            if (!initialized) init()
            return true
        }

        @JvmStatic fun init() {
            if (initialized) return
            val newCanvas = Canvas()
            canvas = newCanvas
            renderer = NVGRenderer(newCanvas)
            loadFont("Inter-Regular", "/assets/aether/fonts/Inter-Regular.otf")
            loadFont("Inter-Bold", "/assets/aether/fonts/Inter-Bold.otf")
            loadFont("Inter-Mono", "/assets/aether/fonts/Inter-Mono.otf")
            loadFont(Fonts.SCOREBOARD_BOLD, "/assets/aether/fonts/scoreboard/Inter-Bold.otf")
            loadUnicodeFallbackFont()
            initialized = true
        }

        @JvmStatic fun destroy() {
            if (!initialized) return
            SVGRenderer.destroy()
            renderer!!.deleteAllImages()
            canvas?.close()
            canvas = null
            renderer = null
            initialized = false
            drawing = false
        }

        @JvmStatic fun beginFrame(width: Float, height: Float) {
            if (!initialized) throw IllegalStateException("[Aether] NanoVGManager.init() must be called first")
            if (drawing) throw IllegalStateException("[Aether] endFrame() was not called before beginFrame()")
            val target = mainTarget()
            frameTarget = target
            pxRatio = if (target != null && width > 0) target.getWidth(0) / width else 1f
            canvas!!.beginFrame(width, height, pxRatio)
            renderer!!.beginFrame()
            drawing = true
        }

        @JvmStatic fun endFrame() {
            if (!drawing) throw IllegalStateException("[Aether] beginFrame() was not called before endFrame()")
            try {
                canvas!!.endFrame(frameTarget)
            } finally {
                frameTarget = null
                drawing = false
            }
        }

        @JvmStatic fun getRenderer(): NVGRenderer? = renderer
        @JvmStatic fun isInitialized(): Boolean = initialized
        @JvmStatic fun mainTarget(): GpuTextureView? {
            val rt = Minecraft.getInstance().gameRenderer.mainRenderTarget()
            return if (rt != null) rt.getColorTextureView() else null
        }
        @JvmStatic fun isDrawing(): Boolean = drawing
        @JvmStatic fun getPxRatio(): Float = pxRatio
        @JvmStatic fun getFontId(name: String): Int = canvas?.fontId(name) ?: -1

        @JvmStatic fun loadFont(name: String, resourcePath: String) {
            val currentCanvas = canvas ?: return
            if (currentCanvas.fontId(name) != -1) return
            try {
                AetherResources.open(resourcePath).use { input ->
                    if (input == null) {
                        System.err.println("[Aether] Font resource not found: $resourcePath")
                        return
                    }
                    val bytes = input.readAllBytes()
                    val buffer: ByteBuffer = MemoryUtil.memAlloc(bytes.size).put(bytes).flip()
                    if (currentCanvas.loadFont(name, buffer) == -1) {
                        MemoryUtil.memFree(buffer)
                        System.err.println("[Aether] Failed to load font: $name")
                    }
                }
            } catch (e: IOException) {
                System.err.println("[Aether] IOException loading font $name: ${e.message}")
            }
        }

        private fun loadUnicodeFallbackFont() {
            val candidates = getUnicodeFallbackCandidates()
            var loadedAny = false
            for (i in candidates.indices) {
                val id = loadFontFromFile("$UNICODE_FALLBACK_FONT-$i", candidates[i])
                if (id != -1) {
                    attachFallbackToUiFonts(id)
                    loadedAny = true
                }
            }
            if (!loadedAny) System.err.println("[Aether] No Unicode fallback font found; some glyphs may render as missing.")
        }

        private fun getUnicodeFallbackCandidates(): Array<String> {
            val osName = System.getProperty("os.name", "").lowercase(Locale.ROOT)
            if (osName.contains("win")) {
                val winDir = System.getenv("WINDIR")
                val fontDir = if (winDir.isNullOrBlank()) "C:\\Windows\\Fonts" else "$winDir\\Fonts"
                return arrayOf(fontDir + "\\msyh.ttc", fontDir + "\\msjh.ttc", fontDir + "\\simsun.ttc", fontDir + "\\YuGothR.ttc", fontDir + "\\msgothic.ttc", fontDir + "\\malgun.ttf", fontDir + "\\seguisym.ttf", fontDir + "\\seguiemj.ttf", fontDir + "\\arial.ttf")
            }
            if (osName.contains("mac")) {
                return arrayOf("/System/Library/Fonts/PingFang.ttc", "/System/Library/Fonts/ヒラギノ角ゴシック W3.ttc", "/System/Library/Fonts/Hiragino Sans GB.ttc", "/System/Library/Fonts/AppleSDGothicNeo.ttc", "/System/Library/Fonts/Apple Symbols.ttf", "/System/Library/Fonts/Supplemental/Arial Unicode.ttf", "/System/Library/Fonts/Supplemental/Arial.ttf")
            }
            return arrayOf("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc", "/usr/share/fonts/opentype/noto/NotoSansCJKsc-Regular.otf", "/usr/share/fonts/opentype/noto/NotoSansCJKtc-Regular.otf", "/usr/share/fonts/opentype/noto/NotoSansCJKkr-Regular.otf", "/usr/share/fonts/google-noto-cjk/NotoSansCJKjp-Regular.otf", "/usr/share/fonts/google-noto-cjk/NotoSansCJKsc-Regular.otf", "/usr/share/fonts/google-noto-cjk/NotoSansCJKtc-Regular.otf", "/usr/share/fonts/google-noto-cjk/NotoSansCJKkr-Regular.otf", "/usr/share/fonts/opentype/noto-cjk/NotoSansCJK-Regular.ttc", "/usr/share/fonts/truetype/noto/NotoSansSymbols2-Regular.ttf", "/usr/share/fonts/opentype/noto/NotoSansSymbols2-Regular.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf")
        }

        private fun loadFontFromFile(name: String, filePath: String): Int {
            val currentCanvas = canvas ?: return -1
            val existing = currentCanvas.fontId(name)
            if (existing != -1) return existing
            val path = Path.of(filePath)
            if (!Files.isRegularFile(path)) return -1
            try {
                val bytes = Files.readAllBytes(path)
                val buffer: ByteBuffer = MemoryUtil.memAlloc(bytes.size).put(bytes).flip()
                val id = currentCanvas.loadFont(name, buffer)
                if (id == -1) {
                    MemoryUtil.memFree(buffer)
                    System.err.println("[Aether] Failed to load font: $filePath")
                }
                return id
            } catch (e: IOException) {
                System.err.println("[Aether] IOException loading font $filePath: ${e.message}")
                return -1
            }
        }

        private fun attachFallbackToUiFonts(fallbackId: Int) {
            if (fallbackId == -1) return
            addFallback(Fonts.REGULAR, fallbackId)
            addFallback(Fonts.BOLD, fallbackId)
            addFallback(Fonts.MONO, fallbackId)
            addFallback(Fonts.SCOREBOARD_BOLD, fallbackId)
        }
        private fun addFallback(baseFont: String, fallbackId: Int) {
            val currentCanvas = canvas ?: return
            val baseId = currentCanvas.fontId(baseFont)
            if (baseId != -1) currentCanvas.addFallbackFont(baseId, fallbackId)
        }
    }
}
