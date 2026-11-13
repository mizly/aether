package dev.aether.mixin

import dev.aether.renderer.AetherRenderQueue
import net.minecraft.client.gui.render.GuiRenderer
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(GuiRenderer::class)
class MixinGuiRenderer {
    @Inject(method = ["render"], at = [At("HEAD")])
    private fun `aether$flushHudBackgrounds`(ci: CallbackInfo) {
        AetherRenderQueue.flushBeforeGui()
    }

    @Inject(method = ["render"], at = [At("TAIL")])
    private fun `aether$flushQueuedNvg`(ci: CallbackInfo) {
        AetherRenderQueue.flush()
    }
}
