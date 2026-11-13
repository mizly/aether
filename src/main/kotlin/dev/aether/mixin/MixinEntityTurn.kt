package dev.aether.mixin

import dev.aether.bootstrap.AetherBootstrapHooks
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(Entity::class)
abstract class MixinEntityTurn {
    // lunar fabric redirects turnPlayer's #turn invocation so we inject a layer
    // deeper to prevent lunar from complaining that its gone
    @Inject(method = ["turn"], at = [At("HEAD")], cancellable = true)
    private fun `aether$routeLocalPlayerTurn`(yRot: Double, xRot: Double, ci: CallbackInfo) {
        val client = Minecraft.getInstance()
        if (this !== client.player) {
            return
        }
        if (AetherBootstrapHooks.turnFreecamCamera(yRot, xRot) // verbatim
            || AetherBootstrapHooks.turnFreelookCamera(yRot, xRot)
        ) {
            ci.cancel()
        }
    }
}
