package dev.aether.mixin

import dev.aether.bootstrap.AetherBootstrapHooks
import dev.aether.util.ProgrammaticAttackTracker
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.MouseHandler
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.Redirect
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(Minecraft::class)
class MixinMinecraftAttackInput {
    // swinging while a pest is on the lasso knocks it off, and a left click can come from a stale macro latch, a queued click, or the player's own mouse
    @Inject(method = ["startAttack"], at = [At("HEAD")], cancellable = true)
    private fun `aether$blockAttackWhileLassoing`(cir: CallbackInfoReturnable<Boolean>) {
        if (AetherBootstrapHooks.isAttackSuppressed()) {
            cir.returnValue = false
        }
    }

    @Inject(method = ["startAttack"], at = [At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;swing(Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/component/SwingAnimation;Z)Z")])
    private fun `aether$trackMousematUse`(cir: CallbackInfoReturnable<Boolean>) {
        AetherBootstrapHooks.onAttack(this as Minecraft)
    }

    @Inject(method = ["continueAttack"], at = [At("HEAD")], cancellable = true)
    private fun `aether$blockHeldAttackWhileLassoing`(holding: Boolean, ci: CallbackInfo) {
        if (AetherBootstrapHooks.isAttackSuppressed()) {
            ci.cancel()
        }
    }

    @Redirect(method = ["handleKeybinds"], at = [At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;isMouseGrabbed()Z")])
    private fun `aether$useHeldAttackInput`(mouseHandler: MouseHandler): Boolean {
        return mouseHandler.isMouseGrabbed() || ProgrammaticAttackTracker.shouldTreatMouseAsGrabbed(this as Minecraft)
    }

    @Redirect(method = ["handleKeybinds"], at = [At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;isDown()Z")])
    private fun `aether$useLatchedAttackKey`(mapping: KeyMapping): Boolean {
        val client = this as Minecraft
        return mapping.isDown() || (client.gui.screen() == null && client.options != null && mapping == client.options.keyAttack && ProgrammaticAttackTracker.isHeld(mapping))
    }
}
