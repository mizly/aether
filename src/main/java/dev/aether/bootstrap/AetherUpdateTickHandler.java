package dev.aether.bootstrap;

import dev.aether.update.UpdateChecker;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public final class AetherUpdateTickHandler {
    private static boolean checkedForCurrentJoin;

    private AetherUpdateTickHandler() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) {
                checkedForCurrentJoin = false;
                return;
            }

            if (checkedForCurrentJoin) {
                return;
            }

            checkedForCurrentJoin = true;
            UpdateChecker.checkAndNotify(client);
        });
    }
}
