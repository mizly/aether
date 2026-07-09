package dev.aether.mixin;

import dev.aether.bootstrap.AetherBootstrapHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public class MixinMouseHandler {
    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void onTurnPlayer(double frameTime, CallbackInfo ci) {
        if (AetherBootstrapHooks.shouldCancelMouseTurn()) {
            ci.cancel();
        }
    }

    @Redirect(
        method = "turnPlayer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"
        )
    )
    private void redirectTurnPlayer(LocalPlayer player, double yRot, double xRot) {
        if (AetherBootstrapHooks.turnFreecamCamera(yRot, xRot)) {
            return;
        }
        if (AetherBootstrapHooks.turnFreelookCamera(yRot, xRot)) {
            return;
        }
        player.turn(yRot, xRot);
    }

    /** Block vanilla from re-grabbing the cursor while the macro has released it. */
    @Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
    private void onGrabMouse(CallbackInfo ci) {
        if (AetherBootstrapHooks.isMouseUngrabbed()) {
            ci.cancel();
        }
    }

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void onMouseClick(long l, MouseButtonInfo mouseButtonInfo, int i, CallbackInfo ci) {
        if (AetherBootstrapHooks.isMouseUngrabbed()) {
            ci.cancel();
            return;
        }
        if (mouseButtonInfo.button() == 0 && i == 1) {
            Minecraft mc = Minecraft.getInstance();
            Screen screen = mc.screen;
            if (screen != null && AetherBootstrapHooks.hasCustomScreenBackground(screen)) {
                double mouseX = mc.mouseHandler.xpos() * (double) mc.getWindow().getGuiScaledWidth() / (double) mc.getWindow().getWidth();
                double mouseY = mc.mouseHandler.ypos() * (double) mc.getWindow().getGuiScaledHeight() / (double) mc.getWindow().getHeight();
                AetherBootstrapHooks.onBackgroundLeftClick(mc, screen, mouseX, mouseY);
            }
        }
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void onMouseScroll(long l, double d, double e, CallbackInfo ci) {
        if (AetherBootstrapHooks.isMouseUngrabbed()) {
            ci.cancel();
        }
    }
}

