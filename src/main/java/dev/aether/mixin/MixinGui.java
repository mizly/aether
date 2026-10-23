package dev.aether.mixin;

import dev.aether.bootstrap.AetherBootstrapHooks;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// hides the vanilla hud while the nanovg screens are open; the hud editor redraws the elements itself inside the nvg frame
@Mixin(Gui.class)
public class MixinGui {
    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void suppressHudForNvgScreens(DeltaTracker deltaTracker,
                                          boolean renderCrosshair,
                                          boolean renderDebug,
                                          CallbackInfo ci) {
        net.minecraft.client.gui.screens.Screen screen = Minecraft.getInstance().gui.screen();
        if (AetherBootstrapHooks.shouldSuppressVanillaHud(screen)) {
            ci.cancel();
        }
    }
}
