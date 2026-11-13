package dev.aether.telemetry

import dev.aether.Aether
import dev.aether.modules.irc.IrcManager
import dev.aether.notification.NotificationManager
import net.minecraft.client.Minecraft
import net.minecraft.util.Util
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class AetherAuthService private constructor() {
    companion object {
        private const val POLL_INTERVAL_SECONDS = 2L
        private const val MAX_LOGIN_SECONDS = 600L
        private const val REFRESH_CHECK_MINUTES = 30L
        private const val REFRESH_BEFORE_MILLIS = 7L * 24L * 60L * 60L * 1000L
        private val EXPECTED_HOST = URI.create(AetherApiClient.BASE_URL).host
        private val LOCK = Any()
        private val SCHEDULER: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "Aether Auth").apply { isDaemon = true } }
        @Volatile private var state = AetherAuthState.LOGGED_OUT
        @Volatile private var discordId = ""
        @Volatile private var statusDetail = ""
        @Volatile private var totalSeconds = 0L
        private var pollTask: ScheduledFuture<*>? = null
        private var refreshTask: ScheduledFuture<*>? = null
        @Volatile private var generation = 0

        @JvmStatic fun initialize() {
            AetherTokenStore.load()
            if (!AetherTokenStore.hasToken()) return
            synchronized(LOCK) { state = AetherAuthState.AUTHENTICATED; discordId = AetherTokenStore.getDiscordId(); statusDetail = "" }
            SCHEDULER.execute { validateSavedToken() }
            startRefreshLoop()
        }

        @JvmStatic fun shutdown() {
            synchronized(LOCK) { cancelPollTask(); refreshTask?.cancel(false); refreshTask = null; generation++ }
            SCHEDULER.shutdownNow()
        }

        private fun startRefreshLoop() {
            synchronized(LOCK) {
                if (refreshTask != null) return
                try { refreshTask = SCHEDULER.scheduleWithFixedDelay({ refreshIfDue() }, 1L, REFRESH_CHECK_MINUTES, TimeUnit.MINUTES) }
                catch (e: RuntimeException) { Aether.LOGGER.debug("[aether] Aether token refresh loop rejected: {}", e.javaClass.simpleName) }
            }
        }

        private fun refreshIfDue() {
            if (state != AetherAuthState.AUTHENTICATED || !AetherTokenStore.needsRefresh(REFRESH_BEFORE_MILLIS)) return
            val token = AetherTokenStore.getToken()
            if (token.isEmpty()) return
            IrcManager.suspendForRotation()
            try {
                val refreshed = AetherApiClient.refreshToken(token)
                val expiresAt = if (refreshed.expiresInSeconds > 0L) System.currentTimeMillis() + refreshed.expiresInSeconds * 1000L else 0L
                synchronized(LOCK) { if (state != AetherAuthState.AUTHENTICATED) return; AetherTokenStore.save(refreshed.token, discordId, expiresAt) }
                Aether.LOGGER.info("[aether] Aether token rotated")
                IrcManager.onTokenRotated()
            } catch (e: AetherApiException) {
                if (e.isUnauthorized()) { invalidateToken(); return }
                Aether.LOGGER.debug("[aether] Aether token refresh deferred: {}", e.message)
                IrcManager.onTokenRotated()
            }
        }

        @JvmStatic fun getState() = state
        @JvmStatic fun isAuthenticated() = state == AetherAuthState.AUTHENTICATED
        @JvmStatic fun getDiscordId() = discordId
        @JvmStatic fun getStatusDetail() = statusDetail
        @JvmStatic fun getTotalSeconds() = totalSeconds

        @JvmStatic fun refreshAccount() {
            if (state != AetherAuthState.AUTHENTICATED) return
            try { SCHEDULER.execute { validateSavedToken() } }
            catch (e: RuntimeException) { Aether.LOGGER.debug("[aether] Aether account refresh rejected: {}", e.javaClass.simpleName) }
        }

        @JvmStatic internal fun updateTotalSeconds(seconds: Long) {
            if (seconds <= 0L) return
            synchronized(LOCK) { if (state == AetherAuthState.AUTHENTICATED) totalSeconds = seconds }
        }

        @JvmStatic fun beginLogin() {
            val gen: Int
            synchronized(LOCK) { if (state != AetherAuthState.LOGGED_OUT) return; state = AetherAuthState.AUTHENTICATING; statusDetail = "Contacting Aether..."; gen = ++generation }
            val startGeneration = gen
            SCHEDULER.execute { startLogin(startGeneration) }
        }

        @JvmStatic fun cancelLogin() {
            synchronized(LOCK) {
                if (state != AetherAuthState.AUTHENTICATING) return
                generation++; cancelPollTask(); state = AetherAuthState.LOGGED_OUT; statusDetail = ""
            }
            Aether.LOGGER.info("[aether] Aether login cancelled")
        }

        @JvmStatic fun logout() {
            val token = AetherTokenStore.getToken()
            synchronized(LOCK) { generation++; cancelPollTask(); state = AetherAuthState.LOGGED_OUT; discordId = ""; statusDetail = ""; totalSeconds = 0L }
            AetherTelemetryService.stopForLogout(token)
            IrcManager.onLoggedOut()
            AetherTokenStore.clear()
            Aether.LOGGER.info("[aether] Aether account disconnected")
        }

        @JvmStatic internal fun invalidateToken() {
            val wasAuthenticated: Boolean
            synchronized(LOCK) {
                wasAuthenticated = state == AetherAuthState.AUTHENTICATED
                generation++; cancelPollTask(); state = AetherAuthState.LOGGED_OUT; discordId = ""; statusDetail = ""; totalSeconds = 0L
            }
            AetherTelemetryService.onTokenInvalidated()
            IrcManager.onLoggedOut()
            AetherTokenStore.clear()
            Aether.LOGGER.warn("[aether] Aether token validation failed; local token cleared")
            if (wasAuthenticated) NotificationManager.error("Aether Login Expired", "Reconnect Discord in Aether settings", 8000)
        }

        private fun startLogin(gen: Int) {
            val start = try { AetherApiClient.startLogin() } catch (e: AetherApiException) {
                failLogin(gen, "Could not reach Aether")
                Aether.LOGGER.warn("[aether] Aether login start failed: {}", e.message)
                return
            }
            val loginUri = safeLoginUri(start.loginUrl)
            if (loginUri == null) {
                failLogin(gen, "Aether returned an unexpected login link")
                Aether.LOGGER.warn("[aether] Aether login start returned a rejected login URL")
                return
            }
            val window = Math.min(MAX_LOGIN_SECONDS, Math.max(POLL_INTERVAL_SECONDS, start.expiresInSeconds))
            val deadline = System.currentTimeMillis() + window * 1000L
            synchronized(LOCK) {
                if (gen != generation) return
                statusDetail = "Waiting for authorization..."
                cancelPollTask()
                pollTask = SCHEDULER.scheduleWithFixedDelay({ poll(start.loginId, start.claimSecret, deadline, gen) }, POLL_INTERVAL_SECONDS, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS)
            }
            Aether.LOGGER.info("[aether] Aether login started")
            openInBrowser(loginUri)
        }

        private fun poll(loginId: String, claimSecret: String, deadlineMillis: Long, gen: Int) {
            if (gen != generation) return
            if (System.currentTimeMillis() > deadlineMillis) { failLogin(gen, "Login timed out"); return }
            try {
                val status = AetherApiClient.getLoginStatus(loginId, claimSecret)
                when (status.kind()) {
                    AetherApiClient.LoginStatusKind.COMPLETE -> completeLogin(status, gen)
                    AetherApiClient.LoginStatusKind.CLAIMED -> failLogin(gen, "Login link was already used")
                    AetherApiClient.LoginStatusKind.EXPIRED -> failLogin(gen, "Login link expired")
                    AetherApiClient.LoginStatusKind.INVALID -> failLogin(gen, "Login link is invalid")
                    AetherApiClient.LoginStatusKind.OUTDATED_CLIENT -> failLogin(gen, "This Aether build is too old to log in")
                    AetherApiClient.LoginStatusKind.PENDING, AetherApiClient.LoginStatusKind.UNKNOWN -> Unit
                }
            } catch (e: AetherApiException) { Aether.LOGGER.debug("[aether] Aether login poll failed: {}", e.message) }
        }

        private fun completeLogin(status: AetherApiClient.LoginStatus, gen: Int) {
            if (status.token().isEmpty()) { failLogin(gen, "Aether returned an incomplete login"); return }
            synchronized(LOCK) {
                if (gen != generation) return
                generation++; cancelPollTask()
                AetherTokenStore.save(status.token(), status.discordId(), if (status.expiresInSeconds() > 0L) System.currentTimeMillis() + status.expiresInSeconds() * 1000L else 0L)
                discordId = status.discordId(); state = AetherAuthState.AUTHENTICATED; statusDetail = ""
            }
            Aether.LOGGER.info("[aether] Aether authentication completed")
            NotificationManager.success("Discord Connected", "Aether is linked to your Discord account")
            AetherTelemetryService.onAuthenticated(); IrcManager.onAuthenticated(); validateSavedToken()
        }

        private fun failLogin(gen: Int, reason: String) {
            synchronized(LOCK) {
                if (gen != generation || state != AetherAuthState.AUTHENTICATING) return
                generation++; cancelPollTask(); state = AetherAuthState.LOGGED_OUT; statusDetail = ""
            }
            Aether.LOGGER.info("[aether] Aether login failed: {}", reason)
            NotificationManager.error("Discord Login Failed", reason, 6000)
        }

        private fun validateSavedToken() {
            val token = AetherTokenStore.getToken()
            if (token.isEmpty()) return
            try {
                val info = AetherApiClient.getCurrentUser(token)
                synchronized(LOCK) {
                    if (state != AetherAuthState.AUTHENTICATED) return
                    totalSeconds = info.totalSeconds
                    if (info.discordId.isNotEmpty() && info.discordId != discordId) { discordId = info.discordId; AetherTokenStore.save(token, info.discordId) }
                }
                Aether.LOGGER.info("[aether] Aether saved login validated")
            } catch (e: AetherApiException) {
                if (e.isUnauthorized()) invalidateToken() else Aether.LOGGER.warn("[aether] Aether token validation deferred: {}", e.message)
            }
        }

        private fun cancelPollTask() { pollTask?.let { it.cancel(false); pollTask = null } }

        private fun safeLoginUri(rawUrl: String): URI? {
            try {
                val uri = Util.parseAndValidateUntrustedUri(rawUrl)
                val https = "https".equals(uri.scheme, ignoreCase = true)
                val sameHost = EXPECTED_HOST != null && EXPECTED_HOST.equals((uri.host ?: "").lowercase(Locale.ROOT), ignoreCase = true)
                return if (https && sameHost) uri else null
            } catch (_: URISyntaxException) { return null } catch (_: RuntimeException) { return null }
        }

        private fun openInBrowser(uri: URI) {
            val client = Minecraft.getInstance()
            val platform: Util.OS = Util.getPlatform()
            val openUri: (URI) -> Unit = { target -> platform.javaClass.getMethod("openUri", URI::class.java).invoke(platform, target) }
            if (client == null) { openUri(uri); return }
            client.execute { openUri(uri) }
        }
    }
}
