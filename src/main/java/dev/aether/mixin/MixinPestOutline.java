package dev.aether.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.aether.bootstrap.AetherBootstrapHooks;
import dev.aether.renderer.PestOutlineState;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public class MixinPestOutline {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
            at = @At("TAIL"))
    private void aether$pestOutline(LivingEntity entity, LivingEntityRenderState state, float partialTick, CallbackInfo ci) {
        PestOutlineState.extract(state, state.wornHeadType == null ? 0 : AetherBootstrapHooks.pestOutlineColor(entity));
    }

    @WrapMethod(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V")
    private void aether$submitPest(LivingEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                                  CameraRenderState camera, Operation<Void> original) {
        PestOutlineState.submitBody(state, () -> original.call(state, poseStack, collector, camera));
    }
}
