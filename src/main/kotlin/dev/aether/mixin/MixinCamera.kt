package dev.aether.mixin

import dev.aether.bootstrap.AetherBootstrapHooks
import net.minecraft.client.Minecraft
import net.minecraft.client.Camera
import net.minecraft.world.entity.Entity
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Redirect

// while freelook is on the camera reads a free yaw/pitch, so the view orbits the player while the body keeps facing its real direction
@Mixin(Camera::class)
class MixinCamera {
    @Redirect(
        method = ["alignWithEntity"],
        at = [At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewYRot(F)F")]
    )
    private fun `aether$freelookViewYRot`(entity: Entity, partialTick: Float): Float {
        if (AetherBootstrapHooks.isFreelookActive() && entity == Minecraft.getInstance().player) {
            return AetherBootstrapHooks.getFreelookYaw()
        }
        return entity.getViewYRot(partialTick)
    }

    @Redirect(
        method = ["alignWithEntity"],
        at = [At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewXRot(F)F")]
    )
    private fun `aether$freelookViewXRot`(entity: Entity, partialTick: Float): Float {
        if (AetherBootstrapHooks.isFreelookActive() && entity == Minecraft.getInstance().player) {
            return AetherBootstrapHooks.getFreelookPitch()
        }
        return entity.getViewXRot(partialTick)
    }
}
