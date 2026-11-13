package dev.aether.modules.irc

import dev.aether.Aether
import dev.aether.config.AetherConfig
import dev.aether.modules.visuals.StreamerModeManager
import dev.aether.telemetry.AetherApiClient
import dev.aether.telemetry.AetherAuthService.Companion.isAuthenticated
import dev.aether.telemetry.AetherTokenStore
import dev.aether.util.ClientUtils.Companion.sendMessage
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicInteger

class IrcManager private constructor() {
    private class SocketHandler : AetherIrcSocket.Handler {
        override fun onMessage(message: AetherApiClient.IrcMessage) {
            display(message)
        }

        // handshake worked, so the token is fine
        override fun onConnected() {
            unauthorizedRetries.set(0)
            Aether.LOGGER.debug("[aether] Aether IRC connected")
        }

        override fun onUnauthorized() {
            // rotation closes sockets on a healthy account
            if (isAuthenticated()
                    && AetherTokenStore.hasToken()
                    && !AetherTokenStore.isExpired()
                    && unauthorizedRetries.incrementAndGet() <= MAX_UNAUTHORIZED_RETRIES) {
                SOCKET.stop()
                SOCKET.start(AetherTokenStore.getToken())
                return
            }
            stop()
            sendMessage("§clogin expired pls relink", false)
        }

        override fun onRejected(reason: String) {
            if ("too_many_connections" == reason) {
                sendMessage("§eirc is already open on another game", false)
            }
            stop()
        }

        override fun onSendFailed(error: String, retryAfterSeconds: Long) {
            when (error) {
                "blocked_content" -> sendMessage("§ewatch your language", false)
                "rate_limited" -> sendMessage("§eslow down buddy", false)
                "empty_message" -> {
                }
                "blocked" -> sendMessage("§cur banned", false)
                else -> {
                    sendMessage("§cwhoops cant deliver ur msg", false)
                    Aether.LOGGER.warn("[aether] Aether IRC send failed: {}", error)
                }
            }
        }
    }

    companion object {
        private const val CHAT_PREFIX = "§8[§caether §7#irc§8] "
        private const val MAX_UNAUTHORIZED_RETRIES = 3
        private val LOCK = Any()
        private val SCHEDULER: ScheduledExecutorService = Executors.newScheduledThreadPool(2) { task ->
            Thread(task, "Aether IRC").apply { isDaemon = true }
        }
        private val SOCKET = AetherIrcSocket(SCHEDULER, SocketHandler())
        private val unauthorizedRetries = AtomicInteger()
        @Volatile private var running = false
        @Volatile private var redirecting = false

        @JvmStatic fun isEnabled(): Boolean {
            return AetherConfig.IRC_ENABLED.get()
        }

        @JvmStatic fun initialize() {
            syncFromConfig()
        }

        @JvmStatic fun syncFromConfig() {
            if (isEnabled()) {
                start()
            } else {
                stop()
            }
        }

        @JvmStatic fun setEnabled(enabled: Boolean) {
            AetherConfig.IRC_ENABLED.set(enabled)
            AetherConfig.save()
            syncFromConfig()
        }

        @JvmStatic fun shutdown() {
            stop()
            SCHEDULER.shutdownNow()
        }

        @JvmStatic fun onAuthenticated() {
            syncFromConfig()
        }

        @JvmStatic fun onLoggedOut() {
            stop()
        }

        // drop the socket before the refresh retires its token
        @JvmStatic fun suspendForRotation() {
            synchronized(LOCK) {
                if (!running) {
                    return
                }
            }
            SOCKET.stop()
        }

        // a stale 4401 may have stopped us
        @JvmStatic fun onTokenRotated() {
            if (!isEnabled() || !isAuthenticated()) {
                return
            }
            synchronized(LOCK) {
                running = true
            }
            unauthorizedRetries.set(0)
            SOCKET.stop()
            SOCKET.start(AetherTokenStore.getToken())
        }

        @JvmStatic fun isConnected(): Boolean {
            return SOCKET.isConnected()
        }

        @JvmStatic fun isRedirecting(): Boolean {
            return redirecting
        }

        @JvmStatic fun toggleRedirect() {
            if (redirecting) {
                redirecting = false
                sendMessage("§echat is back to normal", false)
                return
            }
            if (!isAuthenticated() || AetherTokenStore.getToken().isEmpty()) {
                sendMessage("§clink your account dude", false)
                return
            }
            if (!isEnabled()) {
                sendMessage("§eirc is off turn it on", false)
                return
            }
            redirecting = true
            sendMessage("§aall chat goes to irc now - §f/aether irc§a to stop", false)
        }

        // true when the message was taken over, so the caller cancels the outgoing chat
        @JvmStatic fun interceptChat(message: String): Boolean {
            if (!redirecting) {
                return false
            }
            // never swallow a message that cannot be delivered; fall back to normal chat instead.
            if (!isEnabled() || !isAuthenticated()) {
                redirecting = false
                sendMessage("§echat is back to normal", false)
                return false
            }
            sendChat(message)
            return true
        }

        @JvmStatic fun sendChat(message: String?) {
            if (message == null || message.isBlank()) {
                sendMessage("§cUsage: /aether irc chat <message>", false)
                return
            }
            if (!isAuthenticated() || AetherTokenStore.getToken().isEmpty()) {
                sendMessage("§clink your account dude", false)
                return
            }
            if (!isEnabled()) {
                sendMessage("§eirc is off turn it on", false)
                return
            }
            if (!SOCKET.send(message)) {
                sendMessage("§ereconnecting - try that again in a sec", false)
            }
        }

        private fun start() {
            synchronized(LOCK) {
                // stay fully dormant while unlinked; onAuthenticated() starts the socket later.
                if (running || !isAuthenticated()) {
                    return
                }
                running = true
            }
            unauthorizedRetries.set(0)
            SOCKET.start(AetherTokenStore.getToken())
        }

        private fun stop() {
            redirecting = false
            synchronized(LOCK) {
                if (!running) {
                    return
                }
                running = false
            }
            SOCKET.stop()
        }

        // the backend strips these too, but the client must not let relayed text restyle its own chat line.
        private fun stripFormatting(value: String?): String {
            return if (value == null) "" else value.replace('§', ' ')
        }

        private fun display(message: AetherApiClient.IrcMessage) {
            if (StreamerModeManager.isEnabled()) {
                return
            }
            val client = Minecraft.getInstance()
            if (client == null) {
                return
            }
            val replyTo = stripFormatting(message.replyTo)
            val author = "§b" + stripFormatting(message.author) +
                    (if (replyTo.isBlank()) "" else " §7replied to §b" + replyTo)
            client.execute {
                val player = client.player
                if (player == null) {
                    return@execute
                }
                player.sendSystemMessage(Component.literal(
                        CHAT_PREFIX + author + "§7: §f" + stripFormatting(message.content)))
            }
        }
    }
}
