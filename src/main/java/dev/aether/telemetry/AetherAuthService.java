package dev.aether.telemetry;

import dev.aether.Aether;
import dev.aether.modules.irc.IrcManager;
import dev.aether.notification.NotificationManager;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class AetherAuthService {
    private static final long POLL_INTERVAL_SECONDS = 2L;
    private static final long MAX_LOGIN_SECONDS = 600L;
    private static final long REFRESH_CHECK_MINUTES = 30L;
    // rotate early so an offline client still returns with a live token
    private static final long REFRESH_BEFORE_MILLIS = 7L * 24L * 60L * 60L * 1000L;
    private static final String EXPECTED_HOST = URI.create(AetherApiClient.BASE_URL).getHost();

    private static final Object LOCK = new Object();
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "Aether Auth");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile AetherAuthState state = AetherAuthState.LOGGED_OUT;
    private static volatile String discordId = "";
    private static volatile String statusDetail = "";
    private static volatile long totalSeconds;

    private static ScheduledFuture<?> pollTask;
    private static ScheduledFuture<?> refreshTask;
    // read by poll() on the auth thread outside LOCK, written by the render thread inside it.
    private static volatile int generation;

    private AetherAuthService() {
    }

    public static void initialize() {
        AetherTokenStore.load();
        if (!AetherTokenStore.hasToken()) {
            return;
        }

        // trust the saved token up front so an offline restart is not seen as a logout; /me demotes if revoked.
        synchronized (LOCK) {
            state = AetherAuthState.AUTHENTICATED;
            discordId = AetherTokenStore.getDiscordId();
            statusDetail = "";
        }
        SCHEDULER.execute(AetherAuthService::validateSavedToken);
        startRefreshLoop();
    }

    public static void shutdown() {
        synchronized (LOCK) {
            cancelPollTask();
            if (refreshTask != null) {
                refreshTask.cancel(false);
                refreshTask = null;
            }
            generation++;
        }
        SCHEDULER.shutdownNow();
    }

    private static void startRefreshLoop() {
        synchronized (LOCK) {
            if (refreshTask != null) {
                return;
            }
            try {
                refreshTask = SCHEDULER.scheduleWithFixedDelay(
                        AetherAuthService::refreshIfDue,
                        1L, REFRESH_CHECK_MINUTES, TimeUnit.MINUTES);
            } catch (RuntimeException e) {
                Aether.LOGGER.debug("[aether] Aether token refresh loop rejected: {}", e.getClass().getSimpleName());
            }
        }
    }

    private static void refreshIfDue() {
        if (state != AetherAuthState.AUTHENTICATED || !AetherTokenStore.needsRefresh(REFRESH_BEFORE_MILLIS)) {
            return;
        }

        String token = AetherTokenStore.getToken();
        if (token.isEmpty()) {
            return;
        }

        // the refresh retires the old token
        IrcManager.suspendForRotation();

        try {
            AetherApiClient.TokenRefresh refreshed = AetherApiClient.refreshToken(token);
            long expiresAt = refreshed.expiresInSeconds() > 0L
                    ? System.currentTimeMillis() + refreshed.expiresInSeconds() * 1000L
                    : 0L;

            synchronized (LOCK) {
                if (state != AetherAuthState.AUTHENTICATED) {
                    return;
                }
                AetherTokenStore.save(refreshed.token(), discordId, expiresAt);
            }

            Aether.LOGGER.info("[aether] Aether token rotated");
            // the socket still holds the retired token
            IrcManager.onTokenRotated();
        } catch (AetherApiException e) {
            if (e.isUnauthorized()) {
                invalidateToken();
                return;
            }
            Aether.LOGGER.debug("[aether] Aether token refresh deferred: {}", e.getMessage());
            // refresh never landed, so the old token still works
            IrcManager.onTokenRotated();
        }
    }

    public static AetherAuthState getState() {
        return state;
    }

    public static boolean isAuthenticated() {
        return state == AetherAuthState.AUTHENTICATED;
    }

    public static String getDiscordId() {
        return discordId;
    }

    public static String getStatusDetail() {
        return statusDetail;
    }

    public static long getTotalSeconds() {
        return totalSeconds;
    }

    public static void refreshAccount() {
        if (state != AetherAuthState.AUTHENTICATED) {
            return;
        }
        try {
            SCHEDULER.execute(AetherAuthService::validateSavedToken);
        } catch (RuntimeException e) {
            Aether.LOGGER.debug("[aether] Aether account refresh rejected: {}", e.getClass().getSimpleName());
        }
    }

    // /mod/* replies carry the running total and keep it fresh for free; the zero guard covers replies that omit it.
    static void updateTotalSeconds(long seconds) {
        if (seconds <= 0L) {
            return;
        }
        synchronized (LOCK) {
            if (state == AetherAuthState.AUTHENTICATED) {
                totalSeconds = seconds;
            }
        }
    }

    public static void beginLogin() {
        int gen;
        synchronized (LOCK) {
            if (state != AetherAuthState.LOGGED_OUT) {
                return;
            }
            state = AetherAuthState.AUTHENTICATING;
            statusDetail = "Contacting Aether...";
            gen = ++generation;
        }
        int startGeneration = gen;
        SCHEDULER.execute(() -> startLogin(startGeneration));
    }

    public static void cancelLogin() {
        synchronized (LOCK) {
            if (state != AetherAuthState.AUTHENTICATING) {
                return;
            }
            generation++;
            cancelPollTask();
            state = AetherAuthState.LOGGED_OUT;
            statusDetail = "";
        }
        Aether.LOGGER.info("[aether] Aether login cancelled");
    }

    public static void logout() {
        String token = AetherTokenStore.getToken();
        synchronized (LOCK) {
            generation++;
            cancelPollTask();
            state = AetherAuthState.LOGGED_OUT;
            discordId = "";
            statusDetail = "";
            totalSeconds = 0L;
        }
        // the revoke endpoint exists server-side but the client does not call it yet; a call would slot in here.
        AetherTelemetryService.stopForLogout(token);
        IrcManager.onLoggedOut();
        AetherTokenStore.clear();
        Aether.LOGGER.info("[aether] Aether account disconnected");
    }

    static void invalidateToken() {
        boolean wasAuthenticated;
        synchronized (LOCK) {
            wasAuthenticated = state == AetherAuthState.AUTHENTICATED;
            generation++;
            cancelPollTask();
            state = AetherAuthState.LOGGED_OUT;
            discordId = "";
            statusDetail = "";
            totalSeconds = 0L;
        }

        AetherTelemetryService.onTokenInvalidated();
        IrcManager.onLoggedOut();
        AetherTokenStore.clear();
        Aether.LOGGER.warn("[aether] Aether token validation failed; local token cleared");
        if (wasAuthenticated) {
            NotificationManager.error("Aether Login Expired", "Reconnect Discord in Aether settings", 8000);
        }
    }

    private static void startLogin(int gen) {
        AetherApiClient.LoginStart start;
        try {
            start = AetherApiClient.startLogin();
        } catch (AetherApiException e) {
            failLogin(gen, "Could not reach Aether");
            Aether.LOGGER.warn("[aether] Aether login start failed: {}", e.getMessage());
            return;
        }

        URI loginUri = safeLoginUri(start.loginUrl());
        if (loginUri == null) {
            failLogin(gen, "Aether returned an unexpected login link");
            Aether.LOGGER.warn("[aether] Aether login start returned a rejected login URL");
            return;
        }

        long window = Math.min(MAX_LOGIN_SECONDS, Math.max(POLL_INTERVAL_SECONDS, start.expiresInSeconds()));
        long deadline = System.currentTimeMillis() + window * 1000L;

        synchronized (LOCK) {
            if (gen != generation) {
                return;
            }
            statusDetail = "Waiting for authorization...";
            cancelPollTask();
            pollTask = SCHEDULER.scheduleWithFixedDelay(
                    () -> poll(start.loginId(), start.claimSecret(), deadline, gen),
                    POLL_INTERVAL_SECONDS, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
        }

        Aether.LOGGER.info("[aether] Aether login started");
        openInBrowser(loginUri);
    }

    private static void poll(String loginId, String claimSecret, long deadlineMillis, int gen) {
        if (gen != generation) {
            return;
        }
        if (System.currentTimeMillis() > deadlineMillis) {
            failLogin(gen, "Login timed out");
            return;
        }

        try {
            AetherApiClient.LoginStatus status = AetherApiClient.getLoginStatus(loginId, claimSecret);
            switch (status.kind()) {
                case COMPLETE -> completeLogin(status, gen);
                case CLAIMED -> failLogin(gen, "Login link was already used");
                case EXPIRED -> failLogin(gen, "Login link expired");
                case INVALID -> failLogin(gen, "Login link is invalid");
                case OUTDATED_CLIENT -> failLogin(gen, "This Aether build is too old to log in");
                case PENDING, UNKNOWN -> {
                }
            }
        } catch (AetherApiException e) {
            Aether.LOGGER.debug("[aether] Aether login poll failed: {}", e.getMessage());
        }
    }

    private static void completeLogin(AetherApiClient.LoginStatus status, int gen) {
        if (status.token().isEmpty()) {
            failLogin(gen, "Aether returned an incomplete login");
            return;
        }

        synchronized (LOCK) {
            if (gen != generation) {
                return;
            }
            generation++;
            cancelPollTask();
            AetherTokenStore.save(
                    status.token(),
                    status.discordId(),
                    status.expiresInSeconds() > 0L
                            ? System.currentTimeMillis() + status.expiresInSeconds() * 1000L
                            : 0L);
            discordId = status.discordId();
            state = AetherAuthState.AUTHENTICATED;
            statusDetail = "";
        }

        Aether.LOGGER.info("[aether] Aether authentication completed");
        NotificationManager.success("Discord Connected", "Aether is linked to your Discord account");
        AetherTelemetryService.onAuthenticated();
        IrcManager.onAuthenticated();
        // a fresh login has no total yet; without this the UI would read zero until the next restart.
        validateSavedToken();
    }

    private static void failLogin(int gen, String reason) {
        synchronized (LOCK) {
            if (gen != generation || state != AetherAuthState.AUTHENTICATING) {
                return;
            }
            generation++;
            cancelPollTask();
            state = AetherAuthState.LOGGED_OUT;
            statusDetail = "";
        }
        Aether.LOGGER.info("[aether] Aether login failed: {}", reason);
        NotificationManager.error("Discord Login Failed", reason, 6000);
    }

    private static void validateSavedToken() {
        String token = AetherTokenStore.getToken();
        if (token.isEmpty()) {
            return;
        }

        try {
            AetherApiClient.AccountInfo info = AetherApiClient.getCurrentUser(token);
            synchronized (LOCK) {
                if (state != AetherAuthState.AUTHENTICATED) {
                    return;
                }
                totalSeconds = info.totalSeconds();
                if (!info.discordId().isEmpty() && !info.discordId().equals(discordId)) {
                    discordId = info.discordId();
                    AetherTokenStore.save(token, info.discordId());
                }
            }
            Aether.LOGGER.info("[aether] Aether saved login validated");
        } catch (AetherApiException e) {
            if (e.isUnauthorized()) {
                invalidateToken();
            } else {
                Aether.LOGGER.warn("[aether] Aether token validation deferred: {}", e.getMessage());
            }
        }
    }

    private static void cancelPollTask() {
        if (pollTask != null) {
            pollTask.cancel(false);
            pollTask = null;
        }
    }

    private static URI safeLoginUri(String rawUrl) {
        try {
            URI uri = Util.parseAndValidateUntrustedUri(rawUrl);
            boolean https = "https".equalsIgnoreCase(uri.getScheme());
            boolean sameHost = EXPECTED_HOST != null
                    && EXPECTED_HOST.equalsIgnoreCase(uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT));
            return https && sameHost ? uri : null;
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    private static void openInBrowser(URI uri) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            Util.getPlatform().openUri(uri);
            return;
        }
        client.execute(() -> Util.getPlatform().openUri(uri));
    }
}
