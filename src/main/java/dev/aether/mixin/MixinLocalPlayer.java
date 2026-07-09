package dev.aether.mixin;

import dev.aether.bootstrap.AetherBootstrapHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LocalPlayer.class)
public abstract class MixinLocalPlayer {

    @Shadow
    protected abstract boolean isControlledCamera();

    @Redirect(
        method = "applyInput",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;isControlledCamera()Z"
        )
    )
    private boolean aether$allowRealPlayerInputUpdate(LocalPlayer player) {
        if (AetherBootstrapHooks.isFreecamEnabled() && player == Minecraft.getInstance().player) {
            return true;
        }
        return this.isControlledCamera();
    }

    @Redirect(
        method = "sendPosition",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;isControlledCamera()Z"
        )
    )
    private boolean aether$allowRealPlayerPositionSync(LocalPlayer player) {
        if (AetherBootstrapHooks.isFreecamEnabled() && player == Minecraft.getInstance().player) {
            return true;
        }
        return this.isControlledCamera();
    }
}

