package dev.aether.mixin

import dev.aether.modules.visuals.PipManager
import net.minecraft.client.Minecraft
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(Minecraft::class)
abstract class MixinWindow {
    @Inject(method = ["runTick"], at = [At("RETURN")])
    private fun `aether$syncPipWindowMode`(tick: Boolean, ci: CallbackInfo) {
        PipManager.render()
    }
}
