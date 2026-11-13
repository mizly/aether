package dev.aether.mixin

import com.mojang.blaze3d.platform.FramerateLimitTracker
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(FramerateLimitTracker::class)
class MenuFPSUncap {
    @Inject(method = ["getFramerateLimit"], at = [At(value = "CONSTANT", args = ["intValue=60"])], cancellable = true)
    private fun getFramerateLimit(ci: CallbackInfoReturnable<Int>) {
        ci.returnValue = 144
    }
}
