package dev.aether.mixin;

import dev.aether.bootstrap.AetherBootstrapHooks;
import dev.aether.util.ProgrammaticAttackTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public class MixinMinecraftAttackInput {
    /**
     * Swinging while a pest is on the lasso knocks it off, and a left click can
     * come from a stale macro latch, a queued click, or the player's own mouse.
     * Blocked at the game's own entry points so none of those can reach it.
     */
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void aether$blockAttackWhileLassoing(CallbackInfoReturnable<Boolean> cir) {
        if (AetherBootstrapHooks.isAttackSuppressed()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void aether$blockHeldAttackWhileLassoing(boolean holding, CallbackInfo ci) {
        if (AetherBootstrapHooks.isAttackSuppressed()) {
            ci.cancel();
        }
    }

    @Redirect(
            method = "handleKeybinds",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/MouseHandler;isMouseGrabbed()Z"
            )
    )
    private boolean aether$useHeldAttackInput(MouseHandler mouseHandler) {
        return mouseHandler.isMouseGrabbed()
                || ProgrammaticAttackTracker.shouldTreatMouseAsGrabbed((Minecraft) (Object) this);
    }

    @Redirect(
            method = "handleKeybinds",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/KeyMapping;isDown()Z"
            )
    )
    private boolean aether$useLatchedAttackKey(KeyMapping mapping) {
        Minecraft client = (Minecraft) (Object) this;
        return mapping.isDown()
                || (client.screen == null
                && client.options != null
                && mapping == client.options.keyAttack
                && ProgrammaticAttackTracker.isHeld(mapping));
    }
}
