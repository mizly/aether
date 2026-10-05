package dev.aether.mixin;

import dev.aether.bootstrap.AetherBootstrapHooks;
import dev.aether.bootstrap.CameraOverride;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.client.Camera;

// while freelook is on the camera reads a free yaw/pitch, so the view orbits the player while the body keeps facing its real direction
@Mixin(Camera.class)
public abstract class MixinCamera {
    @Shadow private boolean detached;

    @Shadow private float fov;

    @Shadow protected abstract void setRotation(float yRot, float xRot);

    @Shadow protected abstract void setPosition(double x, double y, double z);

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

    // the orbit menu films the player from outside; detached makes vanilla draw the local player
    @Inject(method = "alignWithEntity", at = @At("TAIL"))
    private void aether$orbitCamera(float partialTicks, CallbackInfo ci) {
        CameraOverride pose = AetherBootstrapHooks.cameraOverride();
        if (pose == null) return;
        setRotation(pose.yRot(), pose.xRot());
        setPosition(pose.x(), pose.y(), pose.z());
        detached = true;
    }

    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
    private void aether$orbitFov(float partialTicks, CallbackInfoReturnable<Float> cir) {
        CameraOverride pose = AetherBootstrapHooks.cameraOverride();
        if (pose != null && pose.fov() > 0) cir.setReturnValue(pose.fov());
    }

    // zoom and speed-fov mods hook calculateFov too and may run after us; pin it where the projection is built
    @ModifyArg(method = "update", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;setupPerspective(FFFFF)V"), index = 2)
    private float aether$orbitPerspectiveFov(float fov) {
        CameraOverride pose = AetherBootstrapHooks.cameraOverride();
        if (pose == null || pose.fov() <= 0) return fov;
        this.fov = pose.fov();
        return pose.fov();
    }
}
