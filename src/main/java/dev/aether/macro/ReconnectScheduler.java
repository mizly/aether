package dev.aether.macro;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ReconnectScheduler {
    public enum ReconnectMode {
        STANDARD,
        PROXY_RESTART
    }

    private static ScheduledExecutorService scheduler;
    private static ScheduledFuture<?> pendingReconnect;
    private static boolean stateLoaded = false;
    private static long cachedReconnectAt = 0;
    private static boolean cachedShouldResume = false;
    private static ReconnectMode cachedReconnectMode = ReconnectMode.STANDARD;

    // --- Inlined from RestStateManager ---
    private static final Path STATE_FILE = FabricLoader.getInstance()
            .getGameDir().resolve("macro_rest_state.json");

    private static synchronized void cacheState(long reconnectAt, boolean shouldResume, ReconnectMode reconnectMode) {
        stateLoaded = true;
        cachedReconnectAt = reconnectAt;
        cachedShouldResume = shouldResume;
        cachedReconnectMode = reconnectMode == null ? ReconnectMode.STANDARD : reconnectMode;
    }

    private static synchronized void ensureStateLoaded() {
        if (stateLoaded) {
            return;
        }

        if (!Files.exists(STATE_FILE)) {
            cacheState(0, false, ReconnectMode.STANDARD);
            return;
        }

        try {
            JsonObject obj = JsonParser.parseString(Files.readString(STATE_FILE)).getAsJsonObject();
            cacheState(
                    obj.has("reconnectAt") ? obj.get("reconnectAt").getAsLong() : 0L,
                    obj.has("shouldResume") && obj.get("shouldResume").getAsBoolean(),
                    parseReconnectMode(obj));
        } catch (Exception e) {
            cacheState(0, false, ReconnectMode.STANDARD);
        }
    }

    private static ReconnectMode parseReconnectMode(JsonObject obj) {
        if (obj == null || !obj.has("reconnectMode")) {
            return ReconnectMode.STANDARD;
        }

        try {
            return ReconnectMode.valueOf(obj.get("reconnectMode").getAsString());
        } catch (Exception ignored) {
            return ReconnectMode.STANDARD;
        }
    }

    private static void saveReconnectTime(long epochSeconds, boolean shouldResume, ReconnectMode reconnectMode) {
        try {
            JsonObject obj = new JsonObject();
            obj.addProperty("reconnectAt", epochSeconds);
            obj.addProperty("shouldResume", shouldResume);
            obj.addProperty("reconnectMode", reconnectMode.name());
            Files.writeString(STATE_FILE, new Gson().toJson(obj));
            cacheState(epochSeconds, shouldResume, reconnectMode);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static long loadReconnectTime() {
        ensureStateLoaded();
        return cachedReconnectAt;
    }

    public static boolean shouldResume() {
        ensureStateLoaded();
        return cachedShouldResume;
    }

    public static ReconnectMode getReconnectMode() {
        ensureStateLoaded();
        return cachedReconnectMode;
    }

    public static void clearState() {
        try {
            Files.deleteIfExists(STATE_FILE);
            cacheState(0, false, ReconnectMode.STANDARD);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    // --- End inlined RestStateManager ---

    public static void scheduleReconnect(long delaySeconds, boolean shouldResume) {
        scheduleReconnect(delaySeconds, shouldResume, ReconnectMode.STANDARD);
    }

    public static void scheduleReconnect(long delaySeconds, boolean shouldResume, ReconnectMode reconnectMode) {
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "aether-reconnect");
                t.setDaemon(true);
                return t;
            });
        }

        if (pendingReconnect != null) {
            pendingReconnect.cancel(false);
        }

        long reconnectAt = Instant.now().getEpochSecond() + delaySeconds;
        saveReconnectTime(reconnectAt, shouldResume, reconnectMode);

        pendingReconnect = scheduler.schedule(
                ReconnectScheduler::doReconnect,
                delaySeconds,
                TimeUnit.SECONDS);
    }

    private static void doReconnect() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            ServerData serverData = new ServerData(
                    "Hypixel", "mc.hypixel.net", ServerData.Type.OTHER);

            ConnectScreen.startConnecting(
                    new TitleScreen(),
                    mc,
                    ServerAddress.parseString("mc.hypixel.net"),
                    serverData,
                    false,
                    null);

            // Note: We no longer clear state here. AetherClient will clear it after
            // re-joining.
        });
    }

    public static void cancel() {
        if (pendingReconnect != null) {
            pendingReconnect.cancel(false);
        }
        clearState();
    }

    public static boolean isPending() {
        return pendingReconnect != null && !pendingReconnect.isDone();
    }
}
