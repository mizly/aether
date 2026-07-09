package dev.aether.mixin;

import dev.aether.bootstrap.AetherBootstrapHooks;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.DisconnectionDetails;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonPacketListenerImpl.class)
public class MixinClientPacketListener {

    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void onDisconnect(DisconnectionDetails details, CallbackInfo ci) {
        AetherBootstrapHooks.onUnexpectedDisconnect();
    }
}

