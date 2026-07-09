package dev.aether.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FramerateLimitTracker.class)
public class MenuFPSUncap {
    @Inject(method = "getFramerateLimit", at = @At(value = "CONSTANT", args = "intValue=60"), cancellable = true)
    private void getFramerateLimit(CallbackInfoReturnable<Integer> ci) {
        ci.setReturnValue(144);
    }
}