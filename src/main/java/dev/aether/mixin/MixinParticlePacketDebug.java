package dev.aether.mixin;

import dev.aether.bootstrap.AetherBootstrapHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class MixinParticlePacketDebug {

    @Inject(method = "handleParticleEvent", at = @At("HEAD"), require = 0)
    private void onHandleParticleEvent(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
        AetherBootstrapHooks.onParticlePacket(Minecraft.getInstance(), packet);
    }
}

