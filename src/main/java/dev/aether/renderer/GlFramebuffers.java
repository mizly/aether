package dev.aether.renderer;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.aether.mixin.AccessorGlDevice;
import dev.aether.mixin.AccessorGpuDevice;

import java.util.Collections;

// 26.2 moved fbo lookup off GlTexture into the device-wide FrameBufferCache,
// this mirrors what GlCommandEncoder does internally
public final class GlFramebuffers {
    private GlFramebuffers() {}

    private static AccessorGlDevice device() {
        return (AccessorGlDevice) ((AccessorGpuDevice) RenderSystem.getDevice()).aether$getBackend();
    }

    public static int fbo(GpuTexture color, GpuTexture depth) {
        AccessorGlDevice device = device();
        return device.aether$frameBufferCache().getFbo(
                device.aether$directStateAccess(),
                Collections.singletonList((GlTexture) color),
                (GlTexture) depth);
    }
}
