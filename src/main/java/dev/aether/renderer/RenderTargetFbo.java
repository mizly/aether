package dev.aether.renderer;

import com.mojang.blaze3d.opengl.DirectStateAccess;
import com.mojang.blaze3d.opengl.FrameBufferAttachment;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.aether.mixin.AccessorGlDevice;
import dev.aether.mixin.AccessorGpuDevice;

import java.util.List;

final class RenderTargetFbo {
    private RenderTargetFbo() {
    }

    static int get(RenderTarget target, DirectStateAccess access) {
        if (!(target.getColorTexture() instanceof GlTexture color)) {
            return 0;
        }
        FrameBufferAttachment depth = target.getDepthTexture() instanceof FrameBufferAttachment attachment
                ? attachment
                : null;
        AccessorGpuDevice gpuDevice = (AccessorGpuDevice) RenderSystem.getDevice();
        AccessorGlDevice glDevice = (AccessorGlDevice) gpuDevice.aether$getBackend();
        return glDevice.aether$frameBufferCache().getFbo(access, List.of(color), depth);
    }
}
