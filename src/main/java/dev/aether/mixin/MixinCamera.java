package dev.aether.mixin;

import dev.aether.bootstrap.AetherBootstrapHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import net.minecraft.client.Camera;

// while freelook is on the camera reads a free yaw/pitch, so the view orbits the player while the body keeps facing its real direction
@Mixin(Camera.class)
public class MixinCamera {

    @Redirect(
        method = "alignWithEntity",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewYRot(F)F")
    )
    private float aether$freelookViewYRot(Entity entity, float partialTick) {
        if (AetherBootstrapHooks.isFreelookActive() && entity == Minecraft.getInstance().player) {
            return AetherBootstrapHooks.getFreelookYaw();
        }
        return entity.getViewYRot(partialTick);
    }

    @Redirect(
        method = "alignWithEntity",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewXRot(F)F")
    )
    private float aether$freelookViewXRot(Entity entity, float partialTick) {
        if (AetherBootstrapHooks.isFreelookActive() && entity == Minecraft.getInstance().player) {
            return AetherBootstrapHooks.getFreelookPitch();
        }
        return entity.getViewXRot(partialTick);
    }
}
