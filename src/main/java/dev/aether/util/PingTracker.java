package dev.aether.util;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;

// times a vanilla REQUEST_STATS packet against its reply, because hypixel pins the tab-list latency field near 0-1ms
// same approach as skytils' ping feature
public final class PingTracker {
    private static final long INTERVAL_MS = 5000L;
    private static final long TIMEOUT_MS = 10_000L;

    private static long lastSentAtNanos = -1L;
    private static long lastSentAtMillis = 0L;
    private static double cachedPingMs = -1.0;
    private static boolean wasConnected = false;

    private PingTracker() {
    }

    public static void tick(Minecraft client) {
        if (client.player == null || client.getConnection() == null) {
            wasConnected = false;
            return;
        }

        if (!wasConnected) {
            wasConnected = true;
            reset();
        }

        long now = System.currentTimeMillis();

        // Abandon a request that never got a reply so the counter never freezes.
        if (lastSentAtNanos > 0 && now - lastSentAtMillis > TIMEOUT_MS) {
            lastSentAtNanos = -1L;
        }

        if (lastSentAtNanos > 0 || now - lastSentAtMillis < INTERVAL_MS) {
            return;
        }

        lastSentAtMillis = now;
        lastSentAtNanos = System.nanoTime();
        client.getConnection().send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.REQUEST_STATS));
    }

    public static void onStatsReceived() {
        if (lastSentAtNanos < 0) {
            return;
        }
        cachedPingMs = (System.nanoTime() - lastSentAtNanos) / 1_000_000.0;
        lastSentAtNanos = -1L;
    }

    public static void reset() {
        lastSentAtNanos = -1L;
        lastSentAtMillis = 0L;
        cachedPingMs = -1.0;
    }

    public static String getFormattedPing() {
        return cachedPingMs >= 0 ? Math.round(cachedPingMs) + "ms" : "---";
    }
}
