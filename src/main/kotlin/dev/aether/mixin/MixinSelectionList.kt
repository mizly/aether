package dev.aether.mixin

import dev.aether.bootstrap.AetherBootstrapHooks
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractSelectionList
import net.minecraft.client.gui.screens.Screen
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(AbstractSelectionList::class)
class MixinSelectionList {
    @Inject(method = ["extractListBackground"], at = [At("HEAD")], cancellable = true)
    private fun onRenderListBackground(guiGraphics: GuiGraphicsExtractor, ci: CallbackInfo) {
        val screen: Screen? = Minecraft.getInstance().gui.screen()
        if (screen != null && AetherBootstrapHooks.hasCustomScreenBackground(screen)) {
            ci.cancel()
        }
    }
}
