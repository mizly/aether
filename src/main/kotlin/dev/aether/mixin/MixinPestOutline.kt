package dev.aether.mixin

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.mojang.blaze3d.vertex.PoseStack
import dev.aether.bootstrap.AetherBootstrapHooks
import dev.aether.renderer.PestOutlineState
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.LivingEntityRenderer
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.world.entity.LivingEntity
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(LivingEntityRenderer::class)
class MixinPestOutline {
    @Inject(method = ["extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V"], at = [At("TAIL")])
    private fun `aether$pestOutline`(entity: LivingEntity, state: LivingEntityRenderState, partialTick: Float, ci: CallbackInfo) {
        PestOutlineState.extract(state, if (state.wornHeadType == null) 0 else AetherBootstrapHooks.pestOutlineColor(entity))
    }

    @WrapMethod(method = ["submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"])
    private fun `aether$submitPest`(state: LivingEntityRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState, original: Operation<Void>) {
        PestOutlineState.submitBody(state) { original.call(state, poseStack, collector, camera) }
    }
}
