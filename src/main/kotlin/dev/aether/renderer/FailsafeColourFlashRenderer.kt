package dev.aether.renderer

import net.minecraft.client.Minecraft

class FailsafeColourFlashRenderer private constructor() {
    companion object {
        @JvmStatic fun render(argb: Int, opacity: Float) {
            if (opacity <= 0.0f || NanoVGManager.isDrawing()) return
            val window = Minecraft.getInstance().window
            val width = window.guiScaledWidth.toFloat()
            val height = window.guiScaledHeight.toFloat()
            if (width <= 0 || height <= 0) return
            val alpha = Math.round(Math.max(0.0f, Math.min(1.0f, opacity)) * ((argb ushr 24) and 0xFF))
            NanoVGManager.ensureInitialized()
            NanoVGManager.beginFrame(width, height)
            try { NanoVGManager.getRenderer()?.let { it.rect(0f, 0f, width, height, (alpha shl 24) or (argb and 0x00FFFFFF)) } } finally { NanoVGManager.endFrame() }
        }
    }
}
