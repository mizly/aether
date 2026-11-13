package dev.aether.mixin

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import com.mojang.blaze3d.vertex.PoseStack
import dev.aether.renderer.PestOutlineState
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState
import org.spongepowered.asm.mixin.Mixin

@Mixin(CustomHeadLayer::class)
class MixinPestSkullOutline {
    @WrapMethod(method = ["submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;FF)V"])
    private fun `aether$submitPestSkull`(poseStack: PoseStack, collector: SubmitNodeCollector, lightCoords: Int, state: LivingEntityRenderState, yRot: Float, xRot: Float, original: Operation<Void>) {
        PestOutlineState.submitHead(state) { original.call(poseStack, collector, lightCoords, state, yRot, xRot) }
    }
}
