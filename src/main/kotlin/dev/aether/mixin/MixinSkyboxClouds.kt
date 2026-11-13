package dev.aether.mixin

import dev.aether.renderer.SkyboxRenderer
import net.minecraft.client.renderer.CloudRenderer
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(CloudRenderer::class)
abstract class MixinSkyboxClouds {
    @Inject(method = ["render"], at = [At("HEAD")], cancellable = true)
    private fun `aether$hideVanillaClouds`(ci: CallbackInfo) {
        if (SkyboxRenderer.hidesVanillaClouds()) ci.cancel()
    }
}
