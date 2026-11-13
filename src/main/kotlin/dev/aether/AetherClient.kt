package dev.aether

import dev.aether.bootstrap.AetherBootstrapHooks
import dev.aether.config.AetherConfig
import dev.aether.feature.ClientFeatureBootstrap
import dev.aether.feature.LiveAetherBootstrapHooks
import dev.aether.modules.discord.DiscordRemoteControlManager
import dev.aether.proxy.AetherProxyManager
import dev.aether.renderer.AetherRenderQueue
import dev.aether.renderer.NanoVGManager
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents

class AetherClient : ClientModInitializer {
    override fun onInitializeClient() {
        AetherConfig.init()
        AetherProxyManager.init()
        AetherBootstrapHooks.install(LiveAetherBootstrapHooks())
        ClientFeatureBootstrap.initialize()
        DiscordRemoteControlManager.restartFromConfig()

        ClientLifecycleEvents.CLIENT_STOPPING.register { DiscordRemoteControlManager.shutdown() }
        ClientLifecycleEvents.CLIENT_STOPPING.register { ClientFeatureBootstrap.shutdown() }
        ClientLifecycleEvents.CLIENT_STOPPING.register { AetherConfig.flush() }
        ClientLifecycleEvents.CLIENT_STOPPING.register {
            AetherBootstrapHooks.reset()
            AetherRenderQueue.clear()
            if (NanoVGManager.isInitialized()) {
                NanoVGManager.destroy()
            }
        }
    }
}
