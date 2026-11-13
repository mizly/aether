package dev.aether.mixin

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.network.Connection
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

@Mixin(ClientCommonPacketListenerImpl::class)
interface AccessorClientCommonPacketListener {
    @Accessor("connection")
    fun getConnectionField(): Connection

    @Accessor("serverData")
    fun getServerDataField(): ServerData?
}
