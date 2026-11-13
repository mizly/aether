package dev.aether.renderer

import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.Std140Builder
import com.mojang.blaze3d.buffers.Std140SizeCalculator
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.textures.GpuTextureView
import net.minecraft.client.renderer.MappableRingBuffer
import org.joml.Matrix4f
import java.util.Optional
import org.joml.Vector4fc
import java.util.OptionalDouble

// one bounded upload, one opaque wing draw and one additive effect draw
class CosmeticShader : AutoCloseable {
    private var ubo: MappableRingBuffer? = null
    private var vertices: MappableRingBuffer? = null

    fun draw(mesh: CosmeticMesh, projection: Matrix4f, color: GpuTextureView?, depth: GpuTextureView?, wingVertices: Int) {
        val count = mesh.size()
        if (count == 0 || color == null || depth == null) return
        if (!EffectPipelines.usable(EffectPipelines.COSMETIC_OPAQUE) || !EffectPipelines.usable(EffectPipelines.COSMETIC_ADDITIVE)) return
        var uniformBuffer = ubo
        var vertexBuffer = vertices
        if (uniformBuffer == null) {
            uniformBuffer = MappableRingBuffer({ "Aether cosmetics uniforms" }, GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_MAP_WRITE, UBO_SIZE)
            ubo = uniformBuffer
            vertexBuffer = MappableRingBuffer({ "Aether cosmetics vertices" }, GpuBuffer.USAGE_VERTEX or GpuBuffer.USAGE_MAP_WRITE, VERTEX_BYTES)
            vertices = vertexBuffer
        }
        val uniforms = uniformBuffer ?: return
        val verts = vertexBuffer ?: return
        uniforms.currentBuffer().map(false, true).use { view ->
            Std140Builder.intoBuffer(view.data()).putMat4f(projection)
        }
        verts.currentBuffer().map(false, true).use { view ->
            view.data().asFloatBuffer().put(mesh.data())
        }
        RenderSystem.getDevice().createCommandEncoder()
            .createRenderPass({ "Aether cosmetics" }, color, Optional.empty<Vector4fc>(), depth, OptionalDouble.empty()).use { pass ->
                pass.setUniform("CosmeticInfo", uniforms.currentBuffer())
                pass.setVertexBuffer(0, verts.currentBuffer().slice())
                if (wingVertices > 0) {
                    pass.setPipeline(RenderSystem.getCompiledPipeline(EffectPipelines.COSMETIC_OPAQUE))
                    pass.draw(wingVertices, 1, 0, 0)
                }
                if (count > wingVertices) {
                    pass.setPipeline(RenderSystem.getCompiledPipeline(EffectPipelines.COSMETIC_ADDITIVE))
                    pass.draw(count - wingVertices, 1, wingVertices, 0)
                }
            }
        uniforms.rotate()
        verts.rotate()
    }

    override fun close() {
        val uniforms = ubo
        if (uniforms != null) {
            uniforms.close()
            vertices?.close()
            ubo = null
            vertices = null
        }
    }

    companion object {
        private val UBO_SIZE = Std140SizeCalculator().putMat4f().get()
        private val VERTEX_BYTES = CosmeticMesh.MAX_VERTICES * CosmeticMesh.FLOATS_PER_VERTEX * Float.SIZE_BYTES
    }
}
