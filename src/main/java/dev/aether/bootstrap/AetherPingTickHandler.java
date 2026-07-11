package dev.aether.bootstrap;

import dev.aether.config.AetherConfig;
import dev.aether.util.PingTracker;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public final class AetherPingTickHandler {
    private AetherPingTickHandler() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!AetherConfig.SHOW_WATERMARK_HUD.get() || !AetherConfig.WATERMARK_SHOW_PING.get()) {
                return;
            }
            PingTracker.tick(client);
        });
    }
}
