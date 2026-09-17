package dev.aether.telemetry;

import dev.aether.Aether;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class AetherTelemetryService {
    private static final long HEARTBEAT_SECONDS = 60L;
    private static final Duration SHUTDOWN_OFF_TIMEOUT = Duration.ofSeconds(3);
    private static final long SHUTDOWN_AWAIT_SECONDS = 4L;

    private static final Object LOCK = new Object();
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "Aether Telemetry");
        thread.setDaemon(true);
        return thread;
    });

    private static boolean modEnabled;
    private static boolean running;
    private static volatile boolean serverActive;
    private static volatile String playtimeSessionToken = "";
    private static ScheduledFuture<?> heartbeatTask;
    private static volatile int generation;

    private AetherTelemetryService() {
    }

    public static boolean isActive() {
        return serverActive;
    }

    public static String getPlaytimeSessionToken() {
        return playtimeSessionToken;
    }

    public static void onModEnabledChanged(boolean enabled) {
        synchronized (LOCK) {
            if (modEnabled == enabled) {
                return;
            }
            modEnabled = enabled;
        }

        if (enabled) {
            start();
        } else {
            stop(true, null);
        }
    }

    public static void onAuthenticated() {
        start();
    }

    public static void onTokenInvalidated() {
        synchronized (LOCK) {
            playtimeSessionToken = "";
        }
        stop(false, null);
    }

    public static void stopForLogout(String token) {
        stop(true, token);
    }

    public static void shutdown() {
        String token;
        String sessionToken;
        synchronized (LOCK) {
            token = running && serverActive ? AetherTokenStore.getToken() : "";
            sessionToken = playtimeSessionToken;
            modEnabled = false;
            running = false;
            serverActive = false;
            playtimeSessionToken = "";
            generation++;
            cancelHeartbeat();
        }

        if (!token.isEmpty()) {
            SCHEDULER.execute(() -> sendModOff(token, sessionToken, SHUTDOWN_OFF_TIMEOUT));
        }

        SCHEDULER.shutdown();
        try {
            if (!SCHEDULER.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                SCHEDULER.shutdownNow();
            }
        } catch (InterruptedException e) {
            SCHEDULER.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static void start() {
        int gen;
        synchronized (LOCK) {
            if (running || !modEnabled || !AetherAuthService.isAuthenticated()) {
                return;
            }
            running = true;
            gen = ++generation;
        }
        submit(() -> activate(gen));
    }

    private static void stop(boolean sendOff, String tokenOverride) {
        boolean wasActive;
        String sessionToken;
        synchronized (LOCK) {
            if (!running) {
                return;
            }
            running = false;
            wasActive = serverActive;
            serverActive = false;
            sessionToken = playtimeSessionToken;
            generation++;
            cancelHeartbeat();
        }

        if (sendOff && wasActive) {
            String token = tokenOverride == null || tokenOverride.isEmpty()
                    ? AetherTokenStore.getToken()
                    : tokenOverride;
            if (!token.isEmpty()) {
                submit(() -> sendModOff(token, sessionToken, null));
            }
        }
        Aether.LOGGER.info("[aether] Aether telemetry stopped");
    }

    private static void activate(int gen) {
        if (gen != generation) {
            return;
        }
        String token = AetherTokenStore.getToken();
        if (token.isEmpty()) {
            stop(false, null);
            return;
        }

        AetherApiClient.ModState state;
        try {
            state = AetherApiClient.modOn(token);
        } catch (AetherApiException e) {
            if (e.isUnauthorized()) {
                AetherAuthService.invalidateToken();
                return;
            }
            // keep the loop alive; the heartbeat retries /mod/on while serverActive is false.
            Aether.LOGGER.warn("[aether] Aether /mod/on failed: {}", e.getMessage());
            scheduleHeartbeat(gen, false);
            return;
        }

        if (gen != generation) {
            // toggled off while /mod/on was in flight, so undo it server-side.
            sendModOff(token, state.sessionToken(), null);
            return;
        }
        updateFromModState(state);
        scheduleHeartbeat(gen, true);
        Aether.LOGGER.info("[aether] Aether telemetry activated");
    }

    private static void scheduleHeartbeat(int gen, boolean active) {
        synchronized (LOCK) {
            if (gen != generation || !running) {
                return;
            }
            serverActive = active;
            cancelHeartbeat();
            heartbeatTask = SCHEDULER.scheduleWithFixedDelay(
                    () -> heartbeat(gen), HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        }
    }

    private static void heartbeat(int gen) {
        if (gen != generation) {
            return;
        }
        String token = AetherTokenStore.getToken();
        if (token.isEmpty()) {
            stop(false, null);
            return;
        }

        try {
            if (!serverActive) {
                AetherApiClient.ModState state = AetherApiClient.modOn(token);
                if (gen != generation) {
                    sendModOff(token, state.sessionToken(), null);
                    return;
                }
                updateFromModState(state);
                serverActive = true;
                Aether.LOGGER.info("[aether] Aether telemetry activated");
                return;
            }
            AetherApiClient.ModState state = AetherApiClient.heartbeat(token, playtimeSessionToken);
            updateFromModState(state);
        } catch (AetherApiException e) {
            if (e.isUnauthorized()) {
                AetherAuthService.invalidateToken();
                return;
            }
            if (e.isNotActive()) {
                recoverNotActive(gen, token);
                return;
            }
            Aether.LOGGER.warn("[aether] Aether heartbeat failed: {}", e.getMessage());
        }
    }

    private static void recoverNotActive(int gen, String token) {
        if (gen != generation) {
            return;
        }
        playtimeSessionToken = "";
        try {
            AetherApiClient.ModState state = AetherApiClient.modOn(token);
            if (gen != generation) {
                sendModOff(token, state.sessionToken(), null);
                return;
            }
            updateFromModState(state);
            serverActive = true;
            Aether.LOGGER.info("[aether] Aether telemetry re-activated after not_active");
        } catch (AetherApiException e) {
            if (e.isUnauthorized()) {
                AetherAuthService.invalidateToken();
                return;
            }
            serverActive = false;
            Aether.LOGGER.warn("[aether] Aether /mod/on recovery failed: {}", e.getMessage());
        }
    }

    private static void sendModOff(String token, String sessionToken, Duration timeout) {
        try {
            if (timeout == null) {
                AetherApiClient.modOff(token, sessionToken);
            } else {
                AetherApiClient.modOff(token, sessionToken, timeout);
            }
        } catch (AetherApiException e) {
            if (e.isUnauthorized()) {
                AetherAuthService.invalidateToken();
                return;
            }
            Aether.LOGGER.warn("[aether] Aether /mod/off failed: {}", e.getMessage());
        }
    }

    private static void updateFromModState(AetherApiClient.ModState state) {
        AetherAuthService.updateTotalSeconds(state.totalSeconds());
        if (!state.sessionToken().isEmpty()) {
            playtimeSessionToken = state.sessionToken();
        }
    }

    private static void cancelHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
    }

    private static void submit(Runnable task) {
        try {
            SCHEDULER.execute(task);
        } catch (RuntimeException e) {
            Aether.LOGGER.debug("[aether] Aether telemetry task rejected: {}", e.getClass().getSimpleName());
        }
    }
}
