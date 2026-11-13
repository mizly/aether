package dev.aether.telemetry

import dev.aether.Aether
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class AetherTelemetryService private constructor() {
    companion object {
        private const val HEARTBEAT_SECONDS = 60L
        private val SHUTDOWN_OFF_TIMEOUT = Duration.ofSeconds(3)
        private const val SHUTDOWN_AWAIT_SECONDS = 4L
        private val LOCK = Any()
        private val SCHEDULER: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "Aether Telemetry").apply { isDaemon = true } }
        private var modEnabled = false
        private var running = false
        @Volatile private var serverActive = false
        @Volatile private var playtimeSessionToken = ""
        private var heartbeatTask: ScheduledFuture<*>? = null
        @Volatile private var generation = 0

        @JvmStatic fun isActive() = serverActive
        @JvmStatic fun getPlaytimeSessionToken() = playtimeSessionToken

        @JvmStatic fun onModEnabledChanged(enabled: Boolean) {
            synchronized(LOCK) { if (modEnabled == enabled) return; modEnabled = enabled }
            if (enabled) start() else stop(true, null)
        }

        @JvmStatic fun onAuthenticated() = start()

        @JvmStatic fun onTokenInvalidated() {
            synchronized(LOCK) { playtimeSessionToken = "" }
            stop(false, null)
        }

        @JvmStatic fun stopForLogout(token: String) = stop(true, token)

        @JvmStatic fun shutdown() {
            val token: String
            val sessionToken: String
            synchronized(LOCK) {
                token = if (running && serverActive) AetherTokenStore.getToken() else ""
                sessionToken = playtimeSessionToken
                modEnabled = false
                running = false
                serverActive = false
                playtimeSessionToken = ""
                generation++
                cancelHeartbeat()
            }
            if (token.isNotEmpty()) SCHEDULER.execute { sendModOff(token, sessionToken, SHUTDOWN_OFF_TIMEOUT) }
            SCHEDULER.shutdown()
            try {
                if (!SCHEDULER.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS)) SCHEDULER.shutdownNow()
            } catch (_: InterruptedException) {
                SCHEDULER.shutdownNow()
                Thread.currentThread().interrupt()
            }
        }

        private fun start() {
            val gen: Int
            synchronized(LOCK) {
                if (running || !modEnabled || !AetherAuthService.isAuthenticated()) return
                running = true
                gen = ++generation
            }
            submit { activate(gen) }
        }

        private fun stop(sendOff: Boolean, tokenOverride: String?) {
            val wasActive: Boolean
            val sessionToken: String
            synchronized(LOCK) {
                if (!running) return
                running = false
                wasActive = serverActive
                serverActive = false
                sessionToken = playtimeSessionToken
                generation++
                cancelHeartbeat()
            }
            if (sendOff && wasActive) {
                val token = if (tokenOverride.isNullOrEmpty()) AetherTokenStore.getToken() else tokenOverride
                if (token.isNotEmpty()) submit { sendModOff(token, sessionToken, null) }
            }
            Aether.LOGGER.info("[aether] Aether telemetry stopped")
        }

        private fun activate(gen: Int) {
            if (gen != generation) return
            val token = AetherTokenStore.getToken()
            if (token.isEmpty()) { stop(false, null); return }
            val state = try { AetherApiClient.modOn(token) } catch (e: AetherApiException) {
                if (e.isUnauthorized()) { AetherAuthService.invalidateToken(); return }
                Aether.LOGGER.warn("[aether] Aether /mod/on failed: {}", e.message)
                scheduleHeartbeat(gen, false)
                return
            }
            if (gen != generation) { sendModOff(token, state.sessionToken, null); return }
            updateFromModState(state)
            scheduleHeartbeat(gen, true)
            Aether.LOGGER.info("[aether] Aether telemetry activated")
        }

        private fun scheduleHeartbeat(gen: Int, active: Boolean) {
            synchronized(LOCK) {
                if (gen != generation || !running) return
                serverActive = active
                cancelHeartbeat()
                heartbeatTask = SCHEDULER.scheduleWithFixedDelay({ heartbeat(gen) }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS)
            }
        }

        private fun heartbeat(gen: Int) {
            if (gen != generation) return
            val token = AetherTokenStore.getToken()
            if (token.isEmpty()) { stop(false, null); return }
            try {
                if (!serverActive) {
                    val state = AetherApiClient.modOn(token)
                    if (gen != generation) { sendModOff(token, state.sessionToken, null); return }
                    updateFromModState(state)
                    serverActive = true
                    Aether.LOGGER.info("[aether] Aether telemetry activated")
                    return
                }
                updateFromModState(AetherApiClient.heartbeat(token, playtimeSessionToken))
            } catch (e: AetherApiException) {
                if (e.isUnauthorized()) { AetherAuthService.invalidateToken(); return }
                if (e.isNotActive()) { recoverNotActive(gen, token); return }
                Aether.LOGGER.warn("[aether] Aether heartbeat failed: {}", e.message)
            }
        }

        private fun recoverNotActive(gen: Int, token: String) {
            if (gen != generation) return
            playtimeSessionToken = ""
            try {
                val state = AetherApiClient.modOn(token)
                if (gen != generation) { sendModOff(token, state.sessionToken, null); return }
                updateFromModState(state)
                serverActive = true
                Aether.LOGGER.info("[aether] Aether telemetry re-activated after not_active")
            } catch (e: AetherApiException) {
                if (e.isUnauthorized()) { AetherAuthService.invalidateToken(); return }
                serverActive = false
                Aether.LOGGER.warn("[aether] Aether /mod/on recovery failed: {}", e.message)
            }
        }

        private fun sendModOff(token: String, sessionToken: String, timeout: Duration?) {
            try {
                if (timeout == null) AetherApiClient.modOff(token, sessionToken) else AetherApiClient.modOff(token, sessionToken, timeout)
            } catch (e: AetherApiException) {
                if (e.isUnauthorized()) { AetherAuthService.invalidateToken(); return }
                Aether.LOGGER.warn("[aether] Aether /mod/off failed: {}", e.message)
            }
        }

        private fun updateFromModState(state: AetherApiClient.ModState) {
            AetherAuthService.updateTotalSeconds(state.totalSeconds)
            if (state.sessionToken.isNotEmpty()) playtimeSessionToken = state.sessionToken
        }

        private fun cancelHeartbeat() {
            heartbeatTask?.let { it.cancel(false); heartbeatTask = null }
        }

        private fun submit(task: Runnable) {
            try { SCHEDULER.execute(task) } catch (e: RuntimeException) { Aether.LOGGER.debug("[aether] Aether telemetry task rejected: {}", e.javaClass.simpleName) }
        }
    }
}
