package dev.aether.telemetry.ban

import dev.aether.Aether
import dev.aether.telemetry.AetherApiClient
import dev.aether.telemetry.AetherApiException
import dev.aether.telemetry.AetherAuthService
import dev.aether.telemetry.AetherTelemetryService
import dev.aether.telemetry.AetherTokenStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AetherBanService private constructor() {
    companion object {
        private const val SHUTDOWN_AWAIT_SECONDS = 3L
        private val EXECUTOR: ExecutorService = Executors.newSingleThreadExecutor { task ->
            Thread(task, "Aether Ban Report").apply { isDaemon = true }
        }
        private val DEDUP = BanReportDeduplicator()
        @Volatile private var lastSimulatedScreenKey: Long = Long.MIN_VALUE

        // the caller must capture context before disconnect handling stops the macro or resets failsafes.
        @JvmStatic
        fun onPlayDisconnect(serverAddress: String?, disconnectReason: String?, context: BanDisconnectContext, playSessionEstablished: Boolean, intentionalDisconnect: Boolean) {
            val ban = HypixelBanDetector.detect(disconnectReason)
            if (!BanDetectionGate.allowsDetection(true, playSessionEstablished, intentionalDisconnect, serverAddress)) {
                if (ban != null && !ban.simulated()) {
                    Aether.LOGGER.info("[aether] Ban-shaped disconnect not reported (established={}, intentional={}, hypixel={})", playSessionEstablished, intentionalDisconnect, BanDetectionGate.isHypixelAddress(serverAddress))
                }
                return
            }
            if (ban == null) return
            if (ban.simulated()) {
                Aether.LOGGER.info("[aether] Detected Aether simulated ban screen (AETHERSB), not reporting")
                return
            }
            if (!context.eligibleForReport()) {
                Aether.LOGGER.info("[aether] Ban detected with no macro active and no failsafe in the last 10 minutes, not reporting")
                return
            }
            val report = BanReport.of(BanDetectionGate.SERVER_ID, ban, context)
            if (!DEDUP.accept(report, System.nanoTime())) {
                Aether.LOGGER.debug("[aether] Duplicate ban disconnect observed, skipping report")
                return
            }
            Aether.LOGGER.info("[aether] Hypixel ban disconnect detected (temporary={}, macroActive={}, failsafeRecent={})", report.temporary(), report.macroActiveAtDisconnect(), report.failsafeTriggeredWithinLast5Minutes())
            if (context.lastTriggeredFailsafe().isNotEmpty()) Aether.LOGGER.debug("[aether] Most recent failsafe before disconnect: {}", context.lastTriggeredFailsafe())
            submitReport(report)
        }

        // the dynamic-rest fake screen is a manual test; not a network disconnect, so it never reaches reporting.
        @JvmStatic
        fun observeSimulatedBanScreen(screenText: String?, screenKey: Long) {
            if (lastSimulatedScreenKey == screenKey) return
            lastSimulatedScreenKey = screenKey
            val ban = HypixelBanDetector.detect(screenText)
            if (ban == null) {
                Aether.LOGGER.debug("[aether] Dynamic Rest screen text was not recognised as ban-shaped")
                return
            }
            if (!ban.simulated()) {
                Aether.LOGGER.warn("[aether] Dynamic Rest screen is missing its simulated ban marker")
                return
            }
            Aether.LOGGER.info("[aether] Detected Aether simulated ban screen (AETHERSB), not reporting")
        }

        @JvmStatic
        fun shutdown() {
            EXECUTOR.shutdown()
            try {
                if (!EXECUTOR.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS)) EXECUTOR.shutdownNow()
            } catch (e: InterruptedException) {
                EXECUTOR.shutdownNow()
                Thread.currentThread().interrupt()
            }
        }

        private fun submitReport(report: BanReport) {
            if (!AetherAuthService.isAuthenticated()) {
                Aether.LOGGER.info("[aether] No Aether account linked, ban detected locally only")
                return
            }
            val token = AetherTokenStore.getToken()
            if (token.isEmpty()) return
            val sessionToken = AetherTelemetryService.getPlaytimeSessionToken()
            try {
                EXECUTOR.execute { send(report, token, sessionToken) }
            } catch (e: RuntimeException) {
                Aether.LOGGER.debug("[aether] Aether ban report rejected: {}", e.javaClass.simpleName)
            }
        }

        private fun send(report: BanReport, token: String, sessionToken: String) {
            try {
                AetherApiClient.reportBan(token, report.toJson(), sessionToken)
                Aether.LOGGER.info("[aether] Aether ban report delivered")
            } catch (e: AetherApiException) {
                Aether.LOGGER.warn("[aether] Aether /ban report failed: {}", e.message)
            } catch (e: RuntimeException) {
                Aether.LOGGER.warn("[aether] Aether ban report errored: {}", e.javaClass.simpleName)
            }
        }
    }
}
