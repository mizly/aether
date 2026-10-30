package dev.aether.mixin;

import dev.aether.bootstrap.AetherBootstrapHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class MixinTeleportFailsafe {
    @Inject(method = "handleMovePlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
            shift = At.Shift.AFTER))
    private void aether$onServerPosition(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        AetherBootstrapHooks.onPlayerPositionPacket(Minecraft.getInstance(), packet);
    }

    @Inject(method = "sendCommand", at = @At("HEAD"))
    private void aether$onTeleportCommand(String command, CallbackInfo ci) {
        AetherBootstrapHooks.onTeleportCommand(command);
    }
}
