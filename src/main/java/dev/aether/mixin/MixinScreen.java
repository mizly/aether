package dev.aether.mixin;

import dev.aether.bootstrap.AetherBootstrapHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public abstract class MixinScreen {

    @Shadow protected Minecraft minecraft;
    @Shadow public int width;
    @Shadow public int height;

    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
    private void onRenderBackground(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        Screen screen = (Screen) (Object) this;
        if (AetherBootstrapHooks.hasCustomScreenBackground(screen)) {
            AetherBootstrapHooks.renderCustomScreenBackground(width, height, mouseX, mouseY);
            ci.cancel();
        }
    }
}

