package dev.aether.mixin;

import dev.aether.bootstrap.AetherBootstrapHooks;
import dev.aether.renderer.AetherRenderQueue;
import dev.aether.ui.MainGUI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class MixinGameRenderer {
    @Shadow
    @Final
    private GameRenderState gameRenderState;

    // a menu with its own scene skips the level entirely; the reset is what renderLevel would have done last
    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZLnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;)V"))
    private void aether$replaceLevel(LevelRenderer renderer, com.mojang.blaze3d.resource.GraphicsResourceAllocator pool,
                                     DeltaTracker deltaTracker, boolean outline, CameraRenderState camera,
                                     org.joml.Matrix4fc modelView, com.mojang.blaze3d.buffers.GpuBufferSlice fog,
                                     org.joml.Vector4f fogColor, boolean sky,
                                     net.minecraft.client.renderer.chunk.ChunkSectionsToRender sections,
                                     Operation<Void> original) {
        if (AetherBootstrapHooks.replaceLevelRender()) {
            AetherBootstrapHooks.renderReplacementLevel();
            gameRenderState.levelRenderState.reset();
            return;
        }
        original.call(renderer, pool, deltaTracker, outline, camera, modelView, fog, fogColor, sky, sections);
    }

    @Inject(method = "extract", at = @At("HEAD"))
    private void onRender(DeltaTracker deltaTracker, boolean tick, CallbackInfo ci) {
        AetherBootstrapHooks.onGameRenderStart(Minecraft.getInstance());
    }

    @Inject(method = "extract", at = @At("TAIL"))
    private void onRenderTail(DeltaTracker deltaTracker, boolean tick, CallbackInfo ci) {
        AetherBootstrapHooks.onGameRenderEnd();
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void afterRender(DeltaTracker deltaTracker, boolean tick, CallbackInfo ci) {
        AetherRenderQueue.flushBeforeGui();
        AetherRenderQueue.flush();
        if (Minecraft.getInstance().screen instanceof MainGUI mainGUI) {
            mainGUI.renderAfterGameRenderer(deltaTracker.getGameTimeDeltaTicks());
        }
        AetherBootstrapHooks.renderPestEspTracerOverlay();
        AetherBootstrapHooks.renderFailsafeColourFlash();
    }

    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void onRenderItemInHand(CameraRenderState cameraRenderState, float partialTick, org.joml.Matrix4fc matrix4f, CallbackInfo ci) {
        if (AetherBootstrapHooks.isFreecamEnabled() || AetherBootstrapHooks.cameraOverride() != null) {
            ci.cancel();
        }
    }
}

