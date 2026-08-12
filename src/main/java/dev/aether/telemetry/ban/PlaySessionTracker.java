package dev.aether.telemetry.ban;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.multiplayer.ServerData;

// Positive proof that a play session actually started, rather than inferring it from
// what a disconnect screen looks like.
public final class PlaySessionTracker {
    private static volatile boolean established;
    private static volatile String serverAddress = "";

    private PlaySessionTracker() {
    }

    public static void register() {
        ClientPlayConnectionEvents.INIT.register((handler, client) -> clear());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ServerData data = handler.getServerData();
            serverAddress = data == null || data.ip == null ? "" : data.ip;
            established = true;
        });
    }

    public static boolean isPlaySessionEstablished() {
        return established;
    }

    public static String getServerAddress() {
        return serverAddress;
    }

    public static void markSessionEnded() {
        established = false;
    }

    public static void clear() {
        established = false;
        serverAddress = "";
    }
}
