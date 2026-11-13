package dev.aether.mixin

import dev.aether.bootstrap.AetherBootstrapHooks
import net.minecraft.client.particle.BreakingItemParticle
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleEngine
import net.minecraft.client.particle.TerrainParticle
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(ParticleEngine::class)
class MixinParticleEngine {
    @Inject(method = ["add"], at = [At("HEAD")], cancellable = true, require = 0)
    private fun onAddParticle(particle: Particle, ci: CallbackInfo) {
        if (AetherBootstrapHooks.areParticlesDisabled() && (particle is TerrainParticle || particle is BreakingItemParticle)) {
            ci.cancel()
        }
    }
}
