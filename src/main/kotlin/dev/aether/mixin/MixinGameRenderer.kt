package dev.aether.mixin

import dev.aether.bootstrap.AetherBootstrapHooks
import dev.aether.renderer.AetherRenderQueue
import dev.aether.ui.MainGUI
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(GameRenderer::class)
class MixinGameRenderer {
    @Inject(method = ["extract"], at = [At("HEAD")])
    private fun onRender(deltaTracker: DeltaTracker, tick: Boolean, ci: CallbackInfo) {
        AetherBootstrapHooks.onGameRenderStart(Minecraft.getInstance())
    }

    @Inject(method = ["extract"], at = [At("TAIL")])
    private fun onRenderTail(deltaTracker: DeltaTracker, tick: Boolean, ci: CallbackInfo) {
        AetherBootstrapHooks.onGameRenderEnd()
    }

    @Inject(method = ["render"], at = [At("TAIL")])
    private fun afterRender(ci: CallbackInfo) {
        AetherRenderQueue.flushBeforeGui()
        AetherRenderQueue.flush()
        val screen = Minecraft.getInstance().gui.screen()
        if (screen is MainGUI) {
            screen.renderAfterGameRenderer(Minecraft.getInstance().deltaTracker.gameTimeDeltaTicks)
        }
        AetherBootstrapHooks.renderPestEspTracerOverlay()
        AetherBootstrapHooks.renderFailsafeColourFlash()
    }

    @Inject(method = ["renderItemInHand"], at = [At("HEAD")], cancellable = true)
    private fun onRenderItemInHand(ci: CallbackInfo) {
        if (AetherBootstrapHooks.isFreecamEnabled()) {
            ci.cancel()
        }
    }
}
