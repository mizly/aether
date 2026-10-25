package dev.aether.renderer;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.aether.modules.visuals.Skybox;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.joml.Matrix4f;

import java.util.Optional;

public final class SkyboxShader implements AutoCloseable {
    private static final int UBO_SIZE = new Std140SizeCalculator().putMat4f().putInt().putFloat().putFloat().get();
    private MappableRingBuffer ubo;

    // false falls back to the vanilla sky, e.g. while the pipeline failed to compile
    public boolean draw(Matrix4f inverseViewProjection, int preset, float time, float brightness, GpuTextureView target) {
        if (preset < 0 || preset >= Skybox.PRESETS.size() || target == null) return false;
        if (!EffectPipelines.usable(EffectPipelines.SKYBOX)) return false;
        if (ubo == null) {
            ubo = new MappableRingBuffer(() -> "Aether skybox", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, UBO_SIZE);
        }
        try (GpuBufferSlice.MappedView view = ubo.currentBuffer().map(false, true)) {
            Std140Builder.intoBuffer(view.data())
                    .putMat4f(inverseViewProjection)
                    .putInt(preset)
                    .putFloat(time)
                    .putFloat(brightness);
        }
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                .createRenderPass(() -> "Aether skybox", target, Optional.empty())) {
            pass.setPipeline(EffectPipelines.SKYBOX);
            pass.setUniform("SkyboxInfo", ubo.currentBuffer());
            pass.draw(3, 1, 0, 0);
        }
        ubo.rotate();
        return true;
    }

    @Override
    public void close() {
        if (ubo != null) {
            ubo.close();
            ubo = null;
        }
    }
}
