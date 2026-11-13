package dev.aether.mixin

import dev.aether.bootstrap.AetherBootstrapHooks
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.network.DisconnectionDetails
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import java.util.UUID

@Mixin(ClientCommonPacketListenerImpl::class)
class MixinClientPacketListener {
    // Configuration-stage disconnects share this method and login-stage ones never reach it,
    // so the instanceof narrows us to an established PLAY connection being terminated.
    @Inject(method = ["onDisconnect"], at = [At("HEAD")])
    private fun onDisconnect(details: DisconnectionDetails, ci: CallbackInfo) {
        AetherBootstrapHooks.onPlayStageDisconnect((this as AccessorClientCommonPacketListener).getServerDataField()?.ip ?: "", details.reason())
        AetherBootstrapHooks.onUnexpectedDisconnect()
    }

    // Server pushed a resource pack. Report accepted + loaded without downloading or
    // showing the prompt, so packs are never applied and we're never kicked (even if required).
    @Inject(method = ["handleResourcePackPush"], at = [At("HEAD")], cancellable = true)
    private fun `aether$bypassResourcePack`(packet: ClientboundResourcePackPushPacket, ci: CallbackInfo) {
        val id: UUID = packet.id()
        val connection = (this as AccessorClientCommonPacketListener).getConnectionField()
        connection.send(ServerboundResourcePackPacket(id, ServerboundResourcePackPacket.Action.ACCEPTED))
        connection.send(ServerboundResourcePackPacket(id, ServerboundResourcePackPacket.Action.DOWNLOADED))
        connection.send(ServerboundResourcePackPacket(id, ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED))
        ci.cancel()
    }
}
