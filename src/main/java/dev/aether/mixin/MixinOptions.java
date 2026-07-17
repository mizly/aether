package dev.aether.mixin;

import dev.aether.bootstrap.AetherBootstrapHooks;
import net.minecraft.client.Options;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Options.class)
public class MixinOptions {
    @Inject(method = "getSoundSourceVolume", at = @At("HEAD"), cancellable = true)
    private void onGetSoundSourceVolume(SoundSource soundSource, CallbackInfoReturnable<Float> ci) {
        if (AetherBootstrapHooks.isMuted() && soundSource == SoundSource.MASTER) {
            ci.setReturnValue(AetherBootstrapHooks.getMuteVolume());
        }
    }

    // Treat packs built for other versions as compatible so custom/server packs
    // load without the incompatible-version warning or confirm prompt.
    @Redirect(method = "updateResourcePacks", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/packs/repository/PackCompatibility;isCompatible()Z"))
    private boolean aether$forceCompatibleOnUpdate(PackCompatibility compatibility) {
        return true;
    }

    // ordinal 0: only the compatibility gate, not the later re-check in this method.
    @Redirect(method = "loadSelectedResourcePacks", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/server/packs/repository/PackCompatibility;isCompatible()Z"))
    private boolean aether$forceCompatibleOnLoad(PackCompatibility compatibility) {
        return true;
    }
}

