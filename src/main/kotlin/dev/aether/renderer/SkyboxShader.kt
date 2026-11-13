package dev.aether.renderer

import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.blaze3d.buffers.Std140Builder
import com.mojang.blaze3d.buffers.Std140SizeCalculator
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.textures.GpuTextureView
import dev.aether.modules.visuals.Skybox
import net.minecraft.client.renderer.MappableRingBuffer
import org.joml.Matrix4f
import java.util.Optional
import org.joml.Vector4fc

class SkyboxShader : AutoCloseable {
    private var ubo: MappableRingBuffer? = null

    fun draw(inverseViewProjection: Matrix4f, preset: Int, time: Float, brightness: Float, target: GpuTextureView?): Boolean {
        if (preset < 0 || preset >= Skybox.PRESETS.size || target == null) return false
        if (!EffectPipelines.usable(EffectPipelines.SKYBOX)) return false
        if (ubo == null) {
            ubo = MappableRingBuffer({ "Aether skybox" }, GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_MAP_WRITE, UBO_SIZE)
        }
        val buffer = ubo ?: return false
        buffer.currentBuffer().map(false, true).use { view ->
            Std140Builder.intoBuffer(view.data())
                .putMat4f(inverseViewProjection)
                .putInt(preset)
                .putFloat(time)
                .putFloat(brightness)
        }
        RenderSystem.getDevice().createCommandEncoder().createRenderPass({ "Aether skybox" }, target, Optional.empty<Vector4fc>()).use { pass ->
            pass.setPipeline(RenderSystem.getCompiledPipeline(EffectPipelines.SKYBOX))
            pass.setUniform("SkyboxInfo", buffer.currentBuffer())
            pass.draw(3, 1, 0, 0)
        }
        buffer.rotate()
        return true
    }

    override fun close() {
        ubo?.close()
        ubo = null
    }

    companion object {
        private val UBO_SIZE = Std140SizeCalculator().putMat4f().putInt().putFloat().putFloat().get()
    }
}
