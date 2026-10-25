package dev.aether.modules.failsafe;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.macro.farming.FarmingMacroManager;
import dev.aether.modules.pest.helpers.PestAotvManager;
import dev.aether.modules.pest.helpers.PestDestroyer;
import dev.aether.modules.pest.helpers.PestOnTheTrackManager;
import dev.aether.modules.session.RestartManager;
import dev.aether.notification.NotificationManager;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

final class TeleportFailsafe {
    private static final SelfTeleportWindow SELF_TELEPORT = new SelfTeleportWindow();

    private TeleportFailsafe() {}

    static void reset() {
        SELF_TELEPORT.reset();
    }

    static void onCommand(String command) {
        if (TeleportDetection.isTeleportCommand(command)) {
            SELF_TELEPORT.request(System.currentTimeMillis());
        }
    }

    static void onPositionPacket(Minecraft client, ClientboundPlayerPositionPacket packet) {
        if (client == null || client.player == null || client.level == null || packet.change() == null) {
            return;
        }
        long settleMs = Math.round(AetherConfig.FAILSAFE_TELEPORT_SETTLE_SECONDS.get() * 1000.0f);
        if (SELF_TELEPORT.onPositionPacket(System.currentTimeMillis(), settleMs)) return;

        if (!AetherConfig.FAILSAFE_TELEPORT.get()
                || RestartManager.isRestartSequenceActive()) {
            return;
        }
        boolean farming = FarmingMacroManager.isActive()
                && FarmingMacroManager.getActiveMacro().isFarmingState();
        boolean pestBusy = PestDestroyer.isActive() || PestAotvManager.isPreparationAotvActive()
                || PestOnTheTrackManager.getInstance().isBlockingFarming();
        if (!TeleportDetection.shouldMonitor(MacroStateManager.getCurrentState(),
                AetherConfig.FAILSAFE_TELEPORT_ONLY_DURING_FARMING.get(), farming, pestBusy,
                ClientUtils.getCurrentLocation() == MacroState.Location.GARDEN)) {
            return;
        }
        Vec3 displacement = TeleportDetection.displacement(
                PositionMoveRotation.of(client.player), packet.change(), packet.relatives());
        if (!TeleportDetection.isUnexpected(displacement,
                AetherConfig.FAILSAFE_TELEPORT_HORIZONTAL_LIMIT.get(),
                AetherConfig.FAILSAFE_TELEPORT_VERTICAL_LIMIT.get(),
                client.player.getMainHandItem().getHoverName().getString())) {
            return;
        }

        FailsafeAction action = FailsafeAction.fromConfig(AetherConfig.FAILSAFE_TELEPORT_ACTION.get());
        String details = String.format(Locale.US,
                "Unexpected teleport (horizontal %.2f blocks, vertical %.2f blocks).",
                Math.hypot(displacement.x, displacement.z), Math.abs(displacement.y));
        NotificationManager.error(FailsafeManager.getNotificationTitle(action), details);
        FailsafeManager.handleConfiguredAction(client, action,
                FailsafeCustomReplayManager.FailsafeReplayType.TELEPORT, details, "TeleportFailsafe: " + details);
    }
}
