package dev.aether.mixin;

import dev.aether.util.ProgrammaticAttackTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Minecraft.class)
public class MixinMinecraftAttackInput {
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
