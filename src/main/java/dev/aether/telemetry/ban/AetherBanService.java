package dev.aether.telemetry.ban;

import dev.aether.Aether;
import dev.aether.telemetry.AetherApiClient;
import dev.aether.telemetry.AetherApiException;
import dev.aether.telemetry.AetherAuthService;
import dev.aether.telemetry.AetherTokenStore;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class AetherBanService {
    private static final long SHUTDOWN_AWAIT_SECONDS = 3L;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Aether Ban Report");
        thread.setDaemon(true);
        return thread;
    });
    private static final BanReportDeduplicator DEDUP = new BanReportDeduplicator();

    private static volatile long lastSimulatedScreenKey = Long.MIN_VALUE;

    private AetherBanService() {
    }

    // the caller must capture context before disconnect handling stops the macro or resets failsafes.
    public static void onPlayDisconnect(
            String serverAddress,
            String disconnectReason,
            BanDisconnectContext context,
            boolean playSessionEstablished,
            boolean intentionalDisconnect
    ) {
        DetectedBan ban = HypixelBanDetector.detect(disconnectReason);
        if (!BanDetectionGate.allowsDetection(true, playSessionEstablished, intentionalDisconnect, serverAddress)) {
            // silence here used to make a dropped punishment indistinguishable from a normal quit.
            if (ban != null && !ban.simulated()) {
                Aether.LOGGER.info("[aether] Ban-shaped disconnect not reported (established={}, intentional={}, hypixel={})",
                        playSessionEstablished, intentionalDisconnect, BanDetectionGate.isHypixelAddress(serverAddress));
            }
            return;
        }
        if (ban == null) {
            return;
        }
        if (ban.simulated()) {
            Aether.LOGGER.info("[aether] Detected Aether simulated ban screen (AETHERSB), not reporting");
            return;
        }
        if (!context.eligibleForReport()) {
            Aether.LOGGER.info("[aether] Ban detected with no macro active and no failsafe in the last 10 minutes, not reporting");
            return;
        }

        BanReport report = BanReport.of(BanDetectionGate.SERVER_ID, ban, context);
        if (!DEDUP.accept(report, System.nanoTime())) {
            Aether.LOGGER.debug("[aether] Duplicate ban disconnect observed, skipping report");
            return;
        }

        Aether.LOGGER.info("[aether] Hypixel ban disconnect detected (temporary={}, macroActive={}, failsafeRecent={})",
                report.temporary(), report.macroActiveAtDisconnect(), report.failsafeTriggeredWithinLast5Minutes());
        if (!context.lastTriggeredFailsafe().isEmpty()) {
            Aether.LOGGER.debug("[aether] Most recent failsafe before disconnect: {}", context.lastTriggeredFailsafe());
        }
        submitReport(report);
    }

    // the dynamic-rest fake screen is a manual test; not a network disconnect, so it never reaches reporting.
    public static void observeSimulatedBanScreen(String screenText, long screenKey) {
        if (lastSimulatedScreenKey == screenKey) {
            return;
        }
        lastSimulatedScreenKey = screenKey;

        DetectedBan ban = HypixelBanDetector.detect(screenText);
        if (ban == null) {
            Aether.LOGGER.debug("[aether] Dynamic Rest screen text was not recognised as ban-shaped");
            return;
        }
        if (!ban.simulated()) {
            Aether.LOGGER.warn("[aether] Dynamic Rest screen is missing its simulated ban marker");
            return;
        }
        Aether.LOGGER.info("[aether] Detected Aether simulated ban screen (AETHERSB), not reporting");
    }

    public static void shutdown() {
        EXECUTOR.shutdown();
        try {
            if (!EXECUTOR.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static void submitReport(BanReport report) {
        if (!AetherAuthService.isAuthenticated()) {
            Aether.LOGGER.info("[aether] No Aether account linked, ban detected locally only");
            return;
        }
        String token = AetherTokenStore.getToken();
        if (token.isEmpty()) {
            return;
        }

        try {
            EXECUTOR.execute(() -> send(report, token));
        } catch (RuntimeException e) {
            Aether.LOGGER.debug("[aether] Aether ban report rejected: {}", e.getClass().getSimpleName());
        }
    }

    private static void send(BanReport report, String token) {
        try {
            AetherApiClient.reportBan(token, report.toJson());
            Aether.LOGGER.info("[aether] Aether ban report delivered");
        } catch (AetherApiException e) {
            Aether.LOGGER.warn("[aether] Aether /ban report failed: {}", e.getMessage());
        } catch (RuntimeException e) {
            Aether.LOGGER.warn("[aether] Aether ban report errored: {}", e.getClass().getSimpleName());
        }
    }
}
