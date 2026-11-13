package dev.aether.telemetry.ban

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.multiplayer.ServerData

// positive proof that a play session actually started, rather than inferring it from the disconnect screen.
class PlaySessionTracker private constructor() {
    companion object {
        @Volatile private var established = false
        @Volatile private var serverAddress = ""

        @JvmStatic
        fun register() {
            ClientPlayConnectionEvents.INIT.register { _, _ -> clear() }
            ClientPlayConnectionEvents.JOIN.register { handler, _, _ ->
                val data: ServerData? = handler.serverData
                serverAddress = if (data == null || data.ip == null) "" else data.ip
                established = true
            }
        }

        @JvmStatic fun isPlaySessionEstablished(): Boolean = established
        @JvmStatic fun getServerAddress(): String = serverAddress
        @JvmStatic fun markSessionEnded() { established = false }
        @JvmStatic fun clear() {
            established = false
            serverAddress = ""
        }
    }
}
