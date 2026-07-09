package dev.aether;

import dev.aether.bootstrap.AetherBootstrapHooks;
import dev.aether.config.AetherConfig;
import dev.aether.feature.ClientFeatureBootstrap;
import dev.aether.feature.LiveAetherBootstrapHooks;
import dev.aether.proxy.AetherProxyManager;
import dev.aether.renderer.AetherRenderQueue;
import dev.aether.renderer.NanoVGManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

public class AetherClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        AetherConfig.init();
        AetherProxyManager.init();
        AetherBootstrapHooks.install(new LiveAetherBootstrapHooks());
        ClientFeatureBootstrap.initialize();

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ClientFeatureBootstrap.shutdown());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> AetherConfig.flush());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            AetherBootstrapHooks.reset();
            AetherRenderQueue.clear();
            if (NanoVGManager.isInitialized()) {
                NanoVGManager.destroy();
            }
        });
    }
}
