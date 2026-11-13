package dev.aether.mixin

import com.mojang.blaze3d.platform.Window
import dev.aether.config.AetherConfig
import net.minecraft.client.Minecraft
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.Redirect
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(Minecraft::class)
class MixinKeepFocus {
    @Redirect(
        method = ["pauseIfInactive"],
        at = [At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;isFocused()Z")]
    )
    private fun keepFocusForInactivePause(window: Window): Boolean {
        return AetherConfig.KEEP_FOCUS.get() || window.isFocused()
    }

    @Inject(method = ["isWindowActive"], at = [At("HEAD")], cancellable = true)
    private fun keepFocusForWindowActive(cir: CallbackInfoReturnable<Boolean>) {
        if (AetherConfig.KEEP_FOCUS.get()) {
            cir.setReturnValue(true)
        }
    }
}
