package dev.aether.renderer;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.joml.Matrix4f;

import java.util.Optional;
import java.util.OptionalDouble;

// one bounded upload, one opaque wing draw and one additive effect draw
public final class CosmeticShader implements AutoCloseable {
    private static final int UBO_SIZE = new Std140SizeCalculator().putMat4f().get();
    private static final int VERTEX_BYTES = CosmeticMesh.MAX_VERTICES * CosmeticMesh.FLOATS_PER_VERTEX * Float.BYTES;
    private MappableRingBuffer ubo;
    private MappableRingBuffer vertices;

    public void draw(CosmeticMesh mesh, Matrix4f projection, GpuTextureView color, GpuTextureView depth, int wingVertices) {
        int count = mesh.size();
        if (count == 0 || color == null || depth == null) return;
        if (!EffectPipelines.usable(EffectPipelines.COSMETIC_OPAQUE) || !EffectPipelines.usable(EffectPipelines.COSMETIC_ADDITIVE)) return;
        if (ubo == null) {
            ubo = new MappableRingBuffer(() -> "Aether cosmetics uniforms", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, UBO_SIZE);
            vertices = new MappableRingBuffer(() -> "Aether cosmetics vertices", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, VERTEX_BYTES);
        }
        try (GpuBufferSlice.MappedView view = ubo.currentBuffer().map(false, true)) {
            Std140Builder.intoBuffer(view.data()).putMat4f(projection);
        }
        try (GpuBufferSlice.MappedView view = vertices.currentBuffer().map(false, true)) {
            view.data().asFloatBuffer().put(mesh.data());
        }
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                .createRenderPass(() -> "Aether cosmetics", color, Optional.empty(), depth, OptionalDouble.empty())) {
            pass.setUniform("CosmeticInfo", ubo.currentBuffer());
            pass.setVertexBuffer(0, vertices.currentBuffer().slice());
            if (wingVertices > 0) {
                pass.setPipeline(EffectPipelines.COSMETIC_OPAQUE);
                pass.draw(wingVertices, 1, 0, 0);
            }
            if (count > wingVertices) {
                pass.setPipeline(EffectPipelines.COSMETIC_ADDITIVE);
                pass.draw(count - wingVertices, 1, wingVertices, 0);
            }
        }
        ubo.rotate();
        vertices.rotate();
    }

    @Override
    public void close() {
        if (ubo != null) {
            ubo.close();
            vertices.close();
            ubo = null;
            vertices = null;
        }
    }
}
