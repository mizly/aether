package dev.aether.mixin

import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.blaze3d.systems.RenderSystem
import dev.aether.renderer.SkyboxRenderer
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.state.level.SkyRenderState
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(LevelRenderer::class)
abstract class MixinSkybox {
    @Inject(method = ["render"], at = [At("HEAD")])
    private fun `aether$beginSkyFrame`(ci: CallbackInfo) {
        SkyboxRenderer.beginFrame()
    }

    @Inject(method = ["lambda\$addSkyPass\$0"], at = [At("HEAD")], cancellable = true)
    private fun `aether$replaceSky`(fog: GpuBufferSlice, state: SkyRenderState, ci: CallbackInfo) {
        if (SkyboxRenderer.render()) {
            RenderSystem.setShaderFog(fog)
            ci.cancel()
        }
    }
}
