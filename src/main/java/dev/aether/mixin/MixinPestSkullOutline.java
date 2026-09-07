package dev.aether.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.aether.renderer.PestOutlineState;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(CustomHeadLayer.class)
public class MixinPestSkullOutline {
    @WrapMethod(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;FF)V")
    private void aether$submitPestSkull(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords,
                                      LivingEntityRenderState state, float yRot, float xRot, Operation<Void> original) {
        PestOutlineState.submitHead(state, () -> original.call(poseStack, collector, lightCoords, state, yRot, xRot));
    }
}
