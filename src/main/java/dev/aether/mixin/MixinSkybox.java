package dev.aether.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.aether.renderer.SkyboxRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class MixinSkybox {
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void aether$beginSkyFrame(CallbackInfo ci) {
        SkyboxRenderer.beginFrame();
    }

    @Inject(method = "lambda$addSkyPass$0", at = @At("HEAD"), cancellable = true)
    private static void aether$replaceSky(GpuBufferSlice fog, SkyRenderState state, SkyRenderer renderer, CallbackInfo ci) {
        if (SkyboxRenderer.render()) {
            RenderSystem.setShaderFog(fog);
            ci.cancel();
        }
    }
}
