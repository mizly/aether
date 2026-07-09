package dev.aether.mixin;

import dev.aether.bootstrap.AetherBootstrapHooks;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ParticleEngine.class)
public class MixinParticleEngine {

    @Inject(method = "add", at = @At("HEAD"), cancellable = true, require = 0)
    private void onAddParticle(Particle particle, CallbackInfo ci) {
        if (AetherBootstrapHooks.areParticlesDisabled() && (particle instanceof net.minecraft.client.particle.TerrainParticle || particle instanceof net.minecraft.client.particle.BreakingItemParticle)) {
            ci.cancel();
        }
    }
}

