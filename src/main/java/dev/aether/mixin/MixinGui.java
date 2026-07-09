package dev.aether.mixin;

import dev.aether.bootstrap.AetherBootstrapHooks;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses the vanilla HUD (crosshair, hotbar, health, etc.) while
 * {@link MainGUI} or {@link HudEditScreen} is open, so the NanoVG UI
 * renders cleanly without vanilla elements overlapping it.
 *
 * <p>For {@link HudEditScreen} specifically, the HUD elements are re-drawn
 * by {@link dev.aether.hud.HudRegistry#renderEditMode} inside the NVG frame,
 * so cancelling the vanilla render avoids duplicate rendering.</p>
 */
@Mixin(Gui.class)
public class MixinGui {

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void suppressHudForNvgScreens(GuiGraphicsExtractor guiGraphics,
                                          DeltaTracker deltaTracker,
                                          CallbackInfo ci) {
        net.minecraft.client.gui.screens.Screen screen = Minecraft.getInstance().screen;
        if (AetherBootstrapHooks.shouldSuppressVanillaHud(screen)) {
            ci.cancel();
        }
    }

    @ModifyVariable(method = "setOverlayMessage", at = @At("HEAD"), argsOnly = true)
    private Component aether$modifyOverlayMessage(Component component) {
        return AetherBootstrapHooks.transformOverlayMessage(component);
    }

}

