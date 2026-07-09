package dev.aether.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import dev.aether.renderer.AetherRenderQueue;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiRenderer.class)
public class MixinGuiRenderer {
    @Inject(method = "render", at = @At("TAIL"))
    private void aether$flushQueuedNvg(GpuBufferSlice fog, CallbackInfo ci) {
        AetherRenderQueue.flush();
    }
}
