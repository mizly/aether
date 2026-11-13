package dev.aether.mixin

import com.llamalad7.mixinextras.injector.ModifyExpressionValue
import com.llamalad7.mixinextras.sugar.Local
import dev.aether.renderer.AetherCape
import net.minecraft.client.renderer.entity.layers.CapeLayer
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.core.ClientAsset
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At

@Mixin(CapeLayer::class)
class MixinAetherCape {
    @ModifyExpressionValue(
        method = ["submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V"],
        at = [At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/PlayerSkin;cape()Lnet/minecraft/core/ClientAsset\$Texture;")]
    )
    private fun `aether$capeTexture`(original: ClientAsset.Texture?, @Local(argsOnly = true) state: AvatarRenderState): ClientAsset.Texture? {
        return AetherCape.textureFor(state.id, original)
    }
}
