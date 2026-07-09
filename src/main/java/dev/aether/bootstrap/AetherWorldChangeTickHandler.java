package dev.aether.bootstrap;

import dev.aether.config.AetherConfig;
import dev.aether.util.AetherResources;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.macro.ReconnectScheduler;
import dev.aether.modules.failsafe.FailsafeAction;
import dev.aether.modules.failsafe.FailsafeCustomReplayManager;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.session.RecoveryManager;
import dev.aether.modules.session.RestartManager;
import dev.aether.notification.NotificationManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

public final class AetherWorldChangeTickHandler {
    private static ClientLevel lastObservedLevel;
    private static Vec3 lastStablePlayerPosition;

    private AetherWorldChangeTickHandler() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
                        ClientLevel currentLevel = client.level;
            if (currentLevel == null) {
                lastObservedLevel = null;
                return;
            }

            if (lastObservedLevel == null) {
                lastObservedLevel = currentLevel;
                if (client.player != null) {
                    lastStablePlayerPosition = client.player.position();
                }
                return;
            }

            if (currentLevel != lastObservedLevel) {
                Vec3 savedPosition = lastStablePlayerPosition;
                lastObservedLevel = currentLevel;
                if (shouldHandleWorldChange()) {
                    handleWorldChangeFailsafe(client, savedPosition);
                }
            }

            if (client.player != null) {
                lastStablePlayerPosition = client.player.position();
            }
        });
    }

    private static boolean shouldHandleWorldChange() {
        if (!AetherConfig.FAILSAFE_WORLD_CHANGE.get()) {
            return false;
        }

        if (!MacroStateManager.isMacroRunning()) {
            return false;
        }

        if (MacroStateManager.isIntentionalDisconnect()) {
            return false;
        }

        if (MacroStateManager.getCurrentState() == MacroState.State.RECOVERING) {
            return false;
        }

        if (RestartManager.isRestartPending()) {
            return false;
        }

        return !ReconnectScheduler.isPending() && !ReconnectScheduler.shouldResume();
    }

    private static void handleWorldChangeFailsafe(Minecraft client, Vec3 savedPosition) {
        FailsafeAction action = FailsafeManager.getWorldChangeAction();
        String details = "World changed unexpectedly.";
        String debugReason = "World change detected";
        NotificationManager.error(FailsafeManager.getNotificationTitle(action), details);

        if (FailsafeManager.shouldStopMacroOnTrigger(action)) {
            FailsafeManager.handleConfiguredAction(
                    client,
                    action,
                    FailsafeCustomReplayManager.FailsafeReplayType.WORLD_CHANGE,
                    details,
                    "World changed; stopping macro");
            return;
        }

        FailsafeManager.handleConfiguredAction(
                client,
                action,
                FailsafeCustomReplayManager.FailsafeReplayType.WORLD_CHANGE,
                details,
                debugReason,
                "Macro stopped and world change recovery started.");
        if (action == FailsafeAction.CUSTOM) {
            return;
        }
        if (savedPosition == null) {
            MacroStateManager.stopMacro(client, "World change recovery skipped: no saved position", false);
            return;
        }

        MacroStateManager.stopMacro(client, "World change detected; starting recovery path", false);
        RecoveryManager.beginWorldChangeRecovery(savedPosition);
    }

}

