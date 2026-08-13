package dev.aether.mixin;

import dev.aether.bootstrap.AetherBootstrapHooks;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(ClientCommonPacketListenerImpl.class)
public class MixinClientPacketListener {

    @Shadow @Final protected Connection connection;

    @Shadow @Final protected ServerData serverData;

    // Configuration-stage disconnects share this method and login-stage ones never reach it,
    // so the instanceof narrows us to an established PLAY connection being terminated.
    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void onDisconnect(DisconnectionDetails details, CallbackInfo ci) {
        if ((Object) this instanceof ClientPacketListener) {
            AetherBootstrapHooks.onPlayStageDisconnect(
                    serverData == null ? "" : serverData.ip,
                    details == null ? null : details.reason());
        }
        AetherBootstrapHooks.onUnexpectedDisconnect();
    }

    // Server pushed a resource pack. Report accepted + loaded without downloading or
    // showing the prompt, so packs are never applied and we're never kicked (even if required).
    @Inject(method = "handleResourcePackPush", at = @At("HEAD"), cancellable = true)
    private void aether$bypassResourcePack(ClientboundResourcePackPushPacket packet, CallbackInfo ci) {
        UUID id = packet.id();
        connection.send(new ServerboundResourcePackPacket(id, ServerboundResourcePackPacket.Action.ACCEPTED));
        connection.send(new ServerboundResourcePackPacket(id, ServerboundResourcePackPacket.Action.DOWNLOADED));
        connection.send(new ServerboundResourcePackPacket(id, ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED));
        ci.cancel();
    }
}

