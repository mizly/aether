package dev.aether.mixin;

import dev.aether.bootstrap.AetherBootstrapHooks;
import net.minecraft.client.Options;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Options.class)
public class MixinOptions {
    @Inject(method = "getSoundSourceVolume", at = @At("HEAD"), cancellable = true)
    private void onGetSoundSourceVolume(SoundSource soundSource, CallbackInfoReturnable<Float> ci) {
        if (AetherBootstrapHooks.isMuted() && soundSource == SoundSource.MASTER) {
            ci.setReturnValue(AetherBootstrapHooks.getMuteVolume());
        }
    }
}

