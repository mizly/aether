package dev.aether.mixin

import dev.aether.bootstrap.AetherBootstrapHooks
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.network.protocol.game.ClientboundAwardStatsPacket
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(ClientPacketListener::class)
class MixinClientPacketListenerPing {
    @Inject(method = ["handleAwardStats"], at = [At("HEAD")])
    private fun onHandleAwardStats(packet: ClientboundAwardStatsPacket, ci: CallbackInfo) {
        AetherBootstrapHooks.onStatsPacketReceived()
    }
}
