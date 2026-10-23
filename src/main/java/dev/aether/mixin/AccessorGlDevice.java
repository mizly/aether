package dev.aether.mixin;

import com.mojang.blaze3d.opengl.DirectStateAccess;
import com.mojang.blaze3d.opengl.FrameBufferCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets = "com.mojang.blaze3d.opengl.GlDevice")
public interface AccessorGlDevice {
    @Invoker("directStateAccess")
    DirectStateAccess aether$directStateAccess();

    @Invoker("frameBufferCache")
    FrameBufferCache aether$frameBufferCache();
}
