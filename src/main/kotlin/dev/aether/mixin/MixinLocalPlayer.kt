package dev.aether.mixin

import dev.aether.bootstrap.AetherBootstrapHooks
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Redirect

@Mixin(LocalPlayer::class)
abstract class MixinLocalPlayer {
    @Shadow protected abstract fun isControlledCamera(): Boolean

    @Redirect(method = ["applyInput"], at = [At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isControlledCamera()Z")])
    private fun `aether$allowRealPlayerInputUpdate`(player: LocalPlayer): Boolean {
        if (AetherBootstrapHooks.isFreecamEnabled() && player === Minecraft.getInstance().player) return true
        return isControlledCamera()
    }

    @Redirect(method = ["sendPosition"], at = [At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isControlledCamera()Z")])
    private fun `aether$allowRealPlayerPositionSync`(player: LocalPlayer): Boolean {
        if (AetherBootstrapHooks.isFreecamEnabled() && player === Minecraft.getInstance().player) return true
        return isControlledCamera()
    }
}
