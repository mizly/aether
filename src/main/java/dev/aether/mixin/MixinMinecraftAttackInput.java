package dev.aether.mixin;

import dev.aether.util.ProgrammaticAttackTracker;
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
}
