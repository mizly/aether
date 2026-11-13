package dev.aether.renderer.canvas

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.device.GpuDevice
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuSampler
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import com.mojang.renderpearl.util.UncheckedAutoCloseable
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.util.ArrayList

// shared rgba pages for glyphs, svg rasters and the white block solid fills sample from
internal class UiAtlas {
    companion object {
        const val SIZE: Int = 2048
        private const val PAD = 2
        // more pages than this get dropped at the start of a frame, never mid-frame
        private const val MAX_PAGES = 3
        private const val WHITE = 4
    }

    private val pages: MutableList<Page> = ArrayList()
    private var generation: Int = 0

    internal class Region(
        private val pageValue: Page,
        private val u0Value: Float,
        private val v0Value: Float,
        private val u1Value: Float,
        private val v1Value: Float,
        private val widthValue: Int,
        private val heightValue: Int
    ) {
        fun page(): Page = pageValue
        fun u0(): Float = u0Value
        fun v0(): Float = v0Value
        fun u1(): Float = u1Value
        fun v1(): Float = v1Value
        fun width(): Int = widthValue
        fun height(): Int = heightValue
    }

    internal class Page private constructor(device: GpuDevice, index: Int) {
        val texture: GpuTexture
        val view: GpuTextureView
        val whiteU: Float
        val whiteV: Float
        private var shelfX: Int = WHITE + PAD
        private var shelfY: Int = 0
        private var shelfHeight: Int = WHITE

        init {
            texture = device.createTexture({ "Aether UI atlas $index" },
                GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1)
            view = device.createTextureView(texture)
            val white = MemoryUtil.memAlloc(WHITE * WHITE * 4)
            try {
                for (i in 0 until WHITE * WHITE * 4) white.put(i, 0xFF.toByte())
                device.createCommandEncoder().writeToTexture(texture, white, 0, 0, 0, 0, WHITE, WHITE)
            } finally {
                MemoryUtil.memFree(white)
            }
            whiteU = (WHITE / 2f) / SIZE
            whiteV = (WHITE / 2f) / SIZE
        }

        internal fun place(w: Int, h: Int): IntArray? {
            if (shelfX + w + PAD > SIZE) {
                shelfY += shelfHeight + PAD
                shelfX = 0
                shelfHeight = 0
            }
            if (shelfY + h + PAD > SIZE || w + PAD > SIZE) return null
            val at = intArrayOf(shelfX, shelfY)
            shelfX += w + PAD
            shelfHeight = Math.max(shelfHeight, h)
            return at
        }

        fun close() {
            UncheckedAutoCloseable.safeClose(view)
            UncheckedAutoCloseable.safeClose(texture)
        }

        companion object {
            fun create(device: GpuDevice, index: Int): Page = Page(device, index)
        }
    }

    fun sampler(): GpuSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)

    // anything cached against an older generation points at a page that may be gone
    fun generation(): Int = generation

    fun currentPage(): Page {
        if (pages.isEmpty()) pages.add(Page.create(RenderSystem.getDevice(), 0))
        return pages[pages.size - 1]
    }

    fun beginFrame() {
        if (pages.size > MAX_PAGES) reset()
    }

    fun reset() {
        pages.forEach { page -> page.close() }
        pages.clear()
        generation++
    }

    // rgba is tightly packed, width * height * 4 bytes
    fun upload(rgba: ByteBuffer, width: Int, height: Int): Region? {
        if (width <= 0 || height <= 0 || width > SIZE - PAD || height > SIZE - PAD) return null
        var page = currentPage()
        var at = page.place(width, height)
        if (at == null) {
            page = Page.create(RenderSystem.getDevice(), pages.size)
            pages.add(page)
            at = page.place(width, height)
            if (at == null) return null
        }
        RenderSystem.getDevice().createCommandEncoder()
            .writeToTexture(page.texture, rgba, 0, 0, at[0], at[1], width, height)
        return Region(page, at[0].toFloat() / SIZE, at[1].toFloat() / SIZE,
            (at[0] + width).toFloat() / SIZE, (at[1] + height).toFloat() / SIZE, width, height)
    }

    fun close() {
        reset()
    }
}
