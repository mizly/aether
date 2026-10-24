package dev.aether.renderer;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.aether.mixin.AccessorGpuDevice;

public final class GraphicsBackend {
    private static Boolean openGl;

    private GraphicsBackend() {}

    // nanovg and our raw gl passes need a gl context, which 26.2's vulkan backend never creates
    public static boolean isOpenGl() {
        if (openGl == null) {
            GpuDevice device = RenderSystem.tryGetDevice();
            // nothing renders before the device exists, so don't cache a guess
            if (device == null) return true;
            Object backend = ((AccessorGpuDevice) device).aether$getBackend();
            openGl = backend.getClass().getName().startsWith("com.mojang.blaze3d.opengl.");
        }
        return openGl;
    }
}
