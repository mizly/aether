package dev.aether.bootstrap

import dev.aether.modules.visuals.FreecamManager
import dev.aether.modules.visuals.FreelookManager
import dev.aether.modules.movement.MovementPlaybackManager
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents

class AetherTickHandlers private constructor() {
    companion object {
        @JvmStatic
        fun register() {
            AetherKeybindHandler.register()
            AetherReconnectTickHandler.register()
            AetherUpdateTickHandler.register()
            AetherWorldChangeTickHandler.register()
            AetherAutomationTickHandler.register()
            AetherPingTickHandler.register()
            MovementPlaybackManager.register()
            FreecamManager.register()
            FreelookManager.register()
            ClientTickEvents.END_CLIENT_TICK.register(dev.aether.modules.visuals.PestDefeatEffects::tick)
        }

        @JvmStatic
        fun setPickingUpStash(pickingUpStash: Boolean) {
            AetherAutomationTickHandler.setPickingUpStash(pickingUpStash)
        }
    }
}
