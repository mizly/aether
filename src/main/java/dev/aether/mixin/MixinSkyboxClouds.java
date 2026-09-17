package dev.aether.mixin;

import dev.aether.renderer.SkyboxRenderer;
import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CloudRenderer.class)
public abstract class MixinSkyboxClouds {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void aether$hideVanillaClouds(CallbackInfo ci) {
        if (SkyboxRenderer.hidesVanillaClouds()) ci.cancel();
    }
}
