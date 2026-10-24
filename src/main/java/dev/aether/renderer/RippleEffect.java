package dev.aether.renderer;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.renderer.MappableRingBuffer;

import java.util.Optional;

// copies the finished frame aside, then redraws it through the ripple shader
public final class RippleEffect implements AutoCloseable {
    private static final int MAX_RIPPLES = 4;
    private static final int UBO_SIZE = new Std140SizeCalculator()
            .putVec4().putVec4().putVec4().putVec4()
            .putVec4()
            .putInt()
            .putFloat()
            .get();

    private GpuTexture scene;
    private GpuTextureView sceneView;
    private MappableRingBuffer ubo;

    // coords are logical pixels from the top left; lToP is the logical->physical scale
    public void composite(GpuTextureView target, float[] cx, float[] cy, float[] radius,
                          float[] thickness, float[] strength, int count, float lToP) {
        if (target == null || count <= 0 || !EffectPipelines.usable(EffectPipelines.RIPPLE)) return;
        count = Math.min(count, MAX_RIPPLES);
        int w = target.getWidth(0);
        int h = target.getHeight(0);
        ensureScene(target.texture(), w, h);

        if (ubo == null) {
            ubo = new MappableRingBuffer(() -> "Aether ripple", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, UBO_SIZE);
        }
        float[] strengths = new float[MAX_RIPPLES];
        try (GpuBufferSlice.MappedView view = ubo.currentBuffer().map(false, true)) {
            Std140Builder builder = Std140Builder.intoBuffer(view.data());
            for (int i = 0; i < MAX_RIPPLES; i++) {
                if (i < count) {
                    // the shader works in uv with y up, distances measured in screen heights
                    builder.putVec4(cx[i] * lToP / w, 1f - cy[i] * lToP / h, radius[i] * lToP / h, thickness[i] * lToP / h);
                    strengths[i] = strength[i];
                } else {
                    builder.putVec4(0f, 0f, 0f, 0f);
                }
            }
            builder.putVec4(strengths[0], strengths[1], strengths[2], strengths[3])
                    .putInt(count)
                    .putFloat((float) w / h);
        }

        GpuDevice device = RenderSystem.getDevice();
        device.createCommandEncoder().copyTextureToTexture(target.texture(), scene, 0, 0, 0, 0, 0, w, h);
        try (RenderPass pass = device.createCommandEncoder().createRenderPass(() -> "Aether ripple", target, Optional.empty())) {
            pass.setPipeline(EffectPipelines.RIPPLE);
            pass.setUniform("RippleInfo", ubo.currentBuffer());
            pass.bindTexture("Scene", sceneView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.draw(3, 1, 0, 0);
        }
        ubo.rotate();
    }

    private void ensureScene(GpuTexture source, int w, int h) {
        if (scene != null && scene.getWidth(0) == w && scene.getHeight(0) == h && scene.getFormat() == source.getFormat()) return;
        closeScene();
        GpuDevice device = RenderSystem.getDevice();
        GpuFormat format = source.getFormat();
        scene = device.createTexture(() -> "Aether ripple scene", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                format, w, h, 1, 1);
        sceneView = device.createTextureView(scene);
    }

    private void closeScene() {
        if (sceneView != null) sceneView.close();
        if (scene != null) scene.close();
        sceneView = null;
        scene = null;
    }

    @Override
    public void close() {
        closeScene();
        if (ubo != null) {
            ubo.close();
            ubo = null;
        }
    }
}
