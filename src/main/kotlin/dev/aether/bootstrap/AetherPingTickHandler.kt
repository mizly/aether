package dev.aether.bootstrap

import dev.aether.config.AetherConfig
import dev.aether.util.PingTracker
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents

class AetherPingTickHandler private constructor() {
    companion object {
        @JvmStatic
        fun register() {
            ClientTickEvents.END_CLIENT_TICK.register { client ->
                if (!AetherConfig.SHOW_WATERMARK_HUD.get() || !AetherConfig.WATERMARK_SHOW_PING.get()) {
                    return@register
                }
                PingTracker.tick(client)
            }
        }
    }
}
