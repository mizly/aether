package dev.aether.mixin;

import dev.aether.bootstrap.AetherBootstrapHooks;
import dev.aether.renderer.AetherRenderQueue;
import dev.aether.ui.MainGUI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class MixinGameRenderer {
    @Inject(method = "extract", at = @At("HEAD"))
    private void onRender(DeltaTracker deltaTracker, boolean tick, CallbackInfo ci) {
        AetherBootstrapHooks.onGameRenderStart(Minecraft.getInstance());
    }

    /** Fires after GUI render-state extraction. */
    @Inject(method = "extract", at = @At("TAIL"))
    private void onRenderTail(DeltaTracker deltaTracker, boolean tick, CallbackInfo ci) {
        AetherBootstrapHooks.onGameRenderEnd();
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void afterRender(DeltaTracker deltaTracker, boolean tick, CallbackInfo ci) {
        AetherRenderQueue.flush();
        if (Minecraft.getInstance().screen instanceof MainGUI mainGUI) {
            mainGUI.renderAfterGameRenderer(deltaTracker.getGameTimeDeltaTicks());
        }
        AetherBootstrapHooks.renderFailsafeColourFlash();
    }

    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void onRenderItemInHand(CameraRenderState cameraRenderState, float partialTick, org.joml.Matrix4fc matrix4f, CallbackInfo ci) {
        if (AetherBootstrapHooks.isFreecamEnabled()) {
            ci.cancel();
        }
    }
}

