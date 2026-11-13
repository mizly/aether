package dev.aether.renderer

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.Std140Builder
import com.mojang.blaze3d.buffers.Std140SizeCalculator
import com.mojang.renderpearl.api.device.GpuDevice
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import net.minecraft.client.renderer.MappableRingBuffer
import java.util.Optional
import org.joml.Vector4fc

class RippleEffect : AutoCloseable {
    private var scene: GpuTexture? = null
    private var sceneView: GpuTextureView? = null
    private var ubo: MappableRingBuffer? = null

    fun composite(target: GpuTextureView?, cx: FloatArray, cy: FloatArray, radius: FloatArray,
                  thickness: FloatArray, strength: FloatArray, count: Int, lToP: Float) {
        if (target == null || count <= 0 || !EffectPipelines.usable(EffectPipelines.RIPPLE)) return
        val rippleCount = Math.min(count, MAX_RIPPLES)
        val w = target.getWidth(0)
        val h = target.getHeight(0)
        ensureScene(target.texture(), w, h)
        if (ubo == null) ubo = MappableRingBuffer({ "Aether ripple" }, GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_MAP_WRITE, UBO_SIZE)
        val buffer = ubo ?: return
        val strengths = FloatArray(MAX_RIPPLES)
        buffer.currentBuffer().map(false, true).use { view ->
            val builder = Std140Builder.intoBuffer(view.data())
            for (i in 0 until MAX_RIPPLES) {
                if (i < rippleCount) {
                    builder.putVec4(cx[i] * lToP / w, 1f - cy[i] * lToP / h, radius[i] * lToP / h, thickness[i] * lToP / h)
                    strengths[i] = strength[i]
                } else builder.putVec4(0f, 0f, 0f, 0f)
            }
            builder.putVec4(strengths[0], strengths[1], strengths[2], strengths[3]).putInt(rippleCount).putFloat(w.toFloat() / h)
        }
        val device: GpuDevice = RenderSystem.getDevice()
        device.createCommandEncoder().copyTextureToTexture(target.texture(), scene ?: return, 0, 0, 0, 0, 0, w, h)
        device.createCommandEncoder().createRenderPass({ "Aether ripple" }, target, Optional.empty<Vector4fc>()).use { pass ->
            pass.setPipeline(RenderSystem.getCompiledPipeline(EffectPipelines.RIPPLE))
            pass.setUniform("RippleInfo", buffer.currentBuffer())
            pass.setUniform("Scene", sceneView ?: return, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR))
            pass.draw(3, 1, 0, 0)
        }
        buffer.rotate()
    }

    private fun ensureScene(source: GpuTexture, w: Int, h: Int) {
        val current = scene
        if (current != null && current.getWidth(0) == w && current.getHeight(0) == h && current.getFormat() == source.getFormat()) return
        closeScene()
        val device: GpuDevice = RenderSystem.getDevice()
        val format: GpuFormat = source.getFormat()
        val texture = device.createTexture({ "Aether ripple scene" }, GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_COPY_DST, format, w, h, 1, 1)
        scene = texture
        sceneView = device.createTextureView(texture)
    }

    private fun closeScene() {
        sceneView?.close()
        scene?.close()
        sceneView = null
        scene = null
    }

    override fun close() {
        closeScene()
        ubo?.close()
        ubo = null
    }

    companion object {
        private const val MAX_RIPPLES = 4
        private val UBO_SIZE = Std140SizeCalculator().putVec4().putVec4().putVec4().putVec4().putVec4().putInt().putFloat().get()
    }
}
