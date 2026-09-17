package dev.aether.bootstrap;

import dev.aether.config.AetherConfig;
import dev.aether.update.AutoUpdateInstaller;
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
            switch (selectUpdateAction(AetherConfig.AUTO_UPDATE.get(), AetherConfig.CHECK_FOR_UPDATES.get())) {
                case INSTALL -> AutoUpdateInstaller.checkAndInstallLatest();
                case NOTIFY -> UpdateChecker.checkAndNotify();
                case NONE -> {
                    // Both update options are disabled.
                }
            }
        });
    }

    static UpdateAction selectUpdateAction(boolean automaticInstallationEnabled, boolean notificationsEnabled) {
        if (automaticInstallationEnabled) {
            return UpdateAction.INSTALL;
        }
        return notificationsEnabled ? UpdateAction.NOTIFY : UpdateAction.NONE;
    }

    enum UpdateAction {
        NONE,
        NOTIFY,
        INSTALL
    }
}
