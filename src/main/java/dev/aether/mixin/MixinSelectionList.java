package dev.aether.mixin;

import dev.aether.bootstrap.AetherBootstrapHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractSelectionList.class)
public class MixinSelectionList {

    @Inject(method = "extractListBackground", at = @At("HEAD"), cancellable = true)
    private void onRenderListBackground(GuiGraphicsExtractor guiGraphics, CallbackInfo ci) {
        Screen screen = Minecraft.getInstance().screen;
        if (screen != null && AetherBootstrapHooks.hasCustomScreenBackground(screen)) {
            ci.cancel();
        }
    }
}

