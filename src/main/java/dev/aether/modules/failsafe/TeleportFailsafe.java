package dev.aether.modules.failsafe;

import dev.aether.config.AetherConfig;
import dev.aether.macro.fishing.FishingMacroManager;
import dev.aether.modules.pathfinding.PathfindingManager;
import dev.aether.notification.NotificationManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

final class TeleportFailsafe {
    // an etherwarp lands a tick or two after the route reports done, so its jump must not read as a tp
    private static final long OWN_MOVEMENT_GRACE_MS = 1_500L;

    private static Vec3 lastPosition;
    private static Level lastLevel;
    private static long ownMovementAt = 0L;
    private static volatile long pendingTriggerAt = 0L;
    private static volatile double pendingDistance = 0.0;
    private static volatile boolean triggered = false;

    private TeleportFailsafe() {
    }

    static void reset() {
        lastPosition = null;
        lastLevel = null;
        ownMovementAt = 0L;
        pendingTriggerAt = 0L;
        pendingDistance = 0.0;
        triggered = false;
    }

    static void tick(Minecraft client) {
        if (client == null || client.player == null || client.level == null
                || !AetherConfig.FAILSAFE_TP_CHECK.get()
                || !FailsafeManager.isFishingMonitored()) {
            reset();
            return;
        }

        long now = System.currentTimeMillis();
        Vec3 position = client.player.position();
        Vec3 previous = lastPosition;
        boolean sameLevel = lastLevel == client.level;
        lastPosition = position;
        lastLevel = client.level;

        if (pendingTriggerAt != 0L) {
            if (now >= pendingTriggerAt) {
                trigger(client, pendingDistance);
            }
            return;
        }

        // our own etherwarps are teleports too, and a changed world belongs to the world change failsafe
        if (PathfindingManager.isNavigating() || FishingMacroManager.isRestarting()) {
            ownMovementAt = now;
            return;
        }
        if (previous == null || !sameLevel || now - ownMovementAt < OWN_MOVEMENT_GRACE_MS) {
            return;
        }

        double distance = position.distanceTo(previous);
        if (!isTeleport(distance, AetherConfig.FAILSAFE_TP_CHECK_DISTANCE.get())) {
            return;
        }
        pendingDistance = distance;
        pendingTriggerAt = now + FailsafeManager.sampleAdditionalTriggerDelayMs();
    }

    // walking, sprinting and even terminal velocity falls stay under four blocks a tick
    static boolean isTeleport(double movedThisTick, double threshold) {
        return movedThisTick >= threshold;
    }

    private static void trigger(Minecraft client, double distance) {
        if (triggered) {
            return;
        }

        FailsafeAction action = FailsafeManager.getTpCheckAction();
        triggered = true;
        NotificationManager.error(FailsafeManager.getNotificationTitle(action), "Player was teleported.");
        FailsafeManager.handleConfiguredAction(
                client,
                action,
                FailsafeCustomReplayManager.FailsafeReplayType.TP_CHECK,
                String.format(Locale.US, "teleported %.1f blocks.", distance),
                String.format(Locale.US, "TeleportFailsafe: teleported %.1f blocks", distance));
        reset();
    }
}
