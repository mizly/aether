package dev.aether.mixin

import dev.aether.bootstrap.AetherBootstrapHooks
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(Screen::class)
abstract class MixinScreen {
    @Shadow protected lateinit var minecraft: Minecraft
    @Shadow var width: Int = 0
    @Shadow var height: Int = 0

    @Inject(method = ["extractBackground"], at = [At("HEAD")], cancellable = true)
    private fun onRenderBackground(guiGraphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float, ci: CallbackInfo) {
        val screen = this as Screen
        if (AetherBootstrapHooks.hasCustomScreenBackground(screen)) {
            AetherBootstrapHooks.renderCustomScreenBackground(width, height, mouseX, mouseY)
            ci.cancel()
        }
    }
}
