package dev.aether.bootstrap;

import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.macro.ReconnectScheduler;
import dev.aether.util.AetherResources;
import dev.aether.ui.DynamicRestScreen;
import dev.aether.util.ClientUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;

public final class AetherReconnectTickHandler {
    private static boolean hasCheckedPersistenceOnJoin = false;
    private static final String PROXY_RESTART_SCREEN_TITLE = "Proxy Restart Recovery";
    private static final String PROXY_RESTART_STATUS_LABEL = "reconnecting to Hypixel in";
    private static final String PROXY_RESTART_CANCEL_LABEL = "Cancel Reconnect & Exit to Menu";

    private AetherReconnectTickHandler() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
                        if (client.player == null) {
                hasCheckedPersistenceOnJoin = false;
                if (!(client.screen instanceof TitleScreen)
                        && !(client.screen instanceof DisconnectedScreen)
                        && !(client.screen instanceof DynamicRestScreen)) {
                    return;
                }

                long reconnectAt = ReconnectScheduler.loadReconnectTime();
                ReconnectScheduler.ReconnectMode reconnectMode = ReconnectScheduler.getReconnectMode();
                if (reconnectAt <= 0) {
                    return;
                }

                long now = java.time.Instant.now().getEpochSecond();
                long remaining = reconnectAt - now;
                if (remaining <= 0) {
                    if (!ReconnectScheduler.isPending()) {
                        ReconnectScheduler.scheduleReconnect(10, ReconnectScheduler.shouldResume(), reconnectMode);
                        if (client.screen instanceof DisconnectedScreen) {
                            client.execute(() -> client.setScreen(createReconnectScreen(
                                    reconnectMode,
                                    java.time.Instant.now().getEpochSecond() * 1000 + 10000,
                                    10000)));
                        }
                    }
                } else if (!ReconnectScheduler.isPending()) {
                    ReconnectScheduler.scheduleReconnect(remaining, ReconnectScheduler.shouldResume(), reconnectMode);
                    if (client.screen instanceof DisconnectedScreen) {
                        client.execute(() -> client.setScreen(createReconnectScreen(
                                reconnectMode,
                                reconnectAt * 1000,
                                remaining * 1000)));
                    }
                }
                return;
            }

            if (hasCheckedPersistenceOnJoin) {
                return;
            }

            long reconnectAt = ReconnectScheduler.loadReconnectTime();
            if (reconnectAt != 0) {
                if (ReconnectScheduler.shouldResume()) {
                    if (ReconnectScheduler.getReconnectMode() == ReconnectScheduler.ReconnectMode.PROXY_RESTART) {
                        dev.aether.modules.session.RecoveryManager.beginRecovery(
                                dev.aether.modules.session.RecoveryManager.RecoveryMode.PROXY_RESTART);
                    } else {
                        dev.aether.modules.session.RecoveryManager.beginRecovery(
                                dev.aether.modules.session.RecoveryManager.RecoveryMode.STANDARD);
                    }
                    ClientUtils.sendMessage(client,
                            "Session persistence detected! Initializing recovery...");
                    MacroStateManager.setCurrentState(MacroState.State.RECOVERING);
                }
                ReconnectScheduler.clearState();
            }
            hasCheckedPersistenceOnJoin = true;
            MacroStateManager.setIntentionalDisconnect(false);
        });
    }

    private static DynamicRestScreen createReconnectScreen(
            ReconnectScheduler.ReconnectMode reconnectMode,
            long reconnectAtMs,
            long durationMs
    ) {
        if (reconnectMode == ReconnectScheduler.ReconnectMode.PROXY_RESTART) {
            return new DynamicRestScreen(
                    PROXY_RESTART_SCREEN_TITLE,
                    PROXY_RESTART_SCREEN_TITLE,
                    PROXY_RESTART_STATUS_LABEL,
                    PROXY_RESTART_CANCEL_LABEL,
                    reconnectAtMs,
                    durationMs);
        }

        return new DynamicRestScreen(reconnectAtMs, durationMs);
    }

}

