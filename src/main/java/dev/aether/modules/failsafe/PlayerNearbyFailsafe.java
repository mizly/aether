package dev.aether.modules.failsafe;

import dev.aether.config.AetherConfig;
import dev.aether.notification.NotificationManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.entity.player.Player;

import java.util.Locale;
import java.util.UUID;

final class PlayerNearbyFailsafe {
    private static volatile long nearbySince = 0L;
    private static volatile long nearbyRandomDelayMs = 0L;
    private static volatile boolean triggered = false;

    private PlayerNearbyFailsafe() {
    }

    static void reset() {
        nearbySince = 0L;
        nearbyRandomDelayMs = 0L;
        triggered = false;
    }

    static void tick(Minecraft client) {
        if (client == null || client.player == null || client.level == null
                || !AetherConfig.FAILSAFE_PLAYER_NEARBY.get()
                || !FailsafeManager.isFishingMonitored()) {
            reset();
            return;
        }

        Player nearest = findNearestRealPlayer(client, AetherConfig.FAILSAFE_PLAYER_NEARBY_RADIUS.get());
        if (nearest == null) {
            nearbySince = 0L;
            nearbyRandomDelayMs = 0L;
            return;
        }

        long now = System.currentTimeMillis();
        if (nearbySince == 0L) {
            nearbySince = now;
            nearbyRandomDelayMs = FailsafeManager.sampleAdditionalTriggerDelayMs();
        }
        if (!hasStayedLongEnough(nearbySince, now, requiredMs() + nearbyRandomDelayMs)) {
            return;
        }
        trigger(client, nearest.getName().getString(), nearest.distanceTo(client.player));
    }

    static boolean hasStayedLongEnough(long since, long now, long requiredMs) {
        return since > 0L && now - since >= requiredMs;
    }

    // hypixel npcs and watchdog bots are fake profiles with version 2 uuids, real accounts are version 4
    static boolean isRealPlayerUuid(UUID uuid) {
        return uuid != null && uuid.version() == 4;
    }

    private static long requiredMs() {
        return Math.round(AetherConfig.FAILSAFE_PLAYER_NEARBY_SECONDS.get() * 1000.0f);
    }

    private static Player findNearestRealPlayer(Minecraft client, double radius) {
        ClientPacketListener connection = client.getConnection();
        Player nearest = null;
        double nearestSq = radius * radius;
        for (Player other : client.level.players()) {
            if (other == client.player || other.isRemoved() || !isRealPlayerUuid(other.getUUID())) {
                continue;
            }
            // tab list membership filters out the leftover fake entities that still carry a random uuid
            if (connection != null && connection.getPlayerInfo(other.getUUID()) == null) {
                continue;
            }
            double distanceSq = other.distanceToSqr(client.player);
            if (distanceSq <= nearestSq) {
                nearestSq = distanceSq;
                nearest = other;
            }
        }
        return nearest;
    }

    private static void trigger(Minecraft client, String name, double distance) {
        if (triggered) {
            return;
        }

        FailsafeAction action = FailsafeManager.getPlayerNearbyAction();
        triggered = true;
        NotificationManager.error(FailsafeManager.getNotificationTitle(action), "A player stayed close by.");
        FailsafeManager.handleConfiguredAction(
                client,
                action,
                FailsafeCustomReplayManager.FailsafeReplayType.PLAYER_NEARBY,
                String.format(Locale.US, "player %s stayed %.1f blocks away.", name, distance),
                "PlayerNearbyFailsafe: " + name + " stayed nearby");
        reset();
    }
}
