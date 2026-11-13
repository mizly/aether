package dev.aether.modules.irc

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aether.Aether
import dev.aether.telemetry.AetherApiClient
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

// reconnects on its own and replays whatever it missed
class AetherIrcSocket(private val scheduler: ScheduledExecutorService, private val handler: Handler) {
    interface Handler {
        fun onMessage(message: AetherApiClient.IrcMessage)

        fun onConnected()

        fun onUnauthorized()

        fun onRejected(reason: String)

        fun onSendFailed(error: String, retryAfterSeconds: Long)
    }

    private val http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build()
    private val lock = Any()
    private val lastActivity = AtomicLong()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var running = false
    @Volatile private var generation = 0
    @Volatile private var token = ""
    @Volatile private var cursor = ""
    private var backoffSeconds = RECONNECT_BASE_SECONDS
    private var reconnectTask: ScheduledFuture<*>? = null
    private var watchdogTask: ScheduledFuture<*>? = null

    fun isConnected(): Boolean {
        val current = socket
        return current != null && !current.isInputClosed && !current.isOutputClosed
    }

    fun start(authToken: String?) {
        val gen: Int
        synchronized(lock) {
            if (running) {
                return
            }
            running = true
            token = authToken ?: ""
            cursor = ""
            backoffSeconds = RECONNECT_BASE_SECONDS
            gen = ++generation
        }
        connect(gen)
        startWatchdog()
    }

    fun stop() {
        val current: WebSocket?
        synchronized(lock) {
            if (!running) {
                return
            }
            running = false
            generation++
            cancel(reconnectTask)
            reconnectTask = null
            cancel(watchdogTask)
            watchdogTask = null
            current = socket
            socket = null
        }
        if (current != null) {
            try {
                current.sendClose(NORMAL_CLOSURE, "client_stop")
            } catch (_: RuntimeException) {
                current.abort()
            }
        }
    }

    fun send(content: String): Boolean {
        val current = socket
        if (current == null || current.isOutputClosed) {
            return false
        }
        val payload = JsonObject()
        payload.addProperty("op", "send")
        payload.addProperty("content", content)
        try {
            current.sendText(payload.toString(), true)
            return true
        } catch (e: RuntimeException) {
            Aether.LOGGER.debug("[aether] Aether IRC socket send failed: {}", e.javaClass.simpleName)
            return false
        }
    }

    private fun connect(gen: Int) {
        if (gen != generation || !running) {
            return
        }
        val authToken = token
        if (authToken.isEmpty()) {
            return
        }
        try {
            http.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + authToken)
                    .connectTimeout(CONNECT_TIMEOUT)
                    .buildAsync(URI.create(AetherApiClient.IRC_SOCKET_URL), SocketListener(gen))
                    .whenComplete { ws, error ->
                        if (error != null) {
                            handleFailure(gen, error)
                            return@whenComplete
                        }
                        synchronized(lock) {
                            if (gen != generation || !running) {
                                ws.abort()
                                return@whenComplete
                            }
                            backoffSeconds = RECONNECT_BASE_SECONDS
                        }
                    }
        } catch (e: RuntimeException) {
            handleFailure(gen, e)
        }
    }

    // the real status is on the cause, not the wrapper
    private fun handleFailure(gen: Int, error: Throwable) {
        var cause: Throwable? = error
        while (cause is CompletionException && cause.cause != null) {
            cause = cause.cause
        }
        val handshake = cause as? WebSocketHandshakeException
        if (handshake != null) {
            val status = handshake.getResponse().statusCode()
            if (status == 401) {
                handler.onUnauthorized()
                return
            }
            if (status == 403) {
                handler.onRejected("blocked")
                return
            }
        }
        Aether.LOGGER.debug("[aether] Aether IRC socket connect failed: {}",
                cause?.javaClass?.simpleName)
        scheduleReconnect(gen)
    }

    private fun scheduleReconnect(gen: Int) {
        synchronized(lock) {
            if (gen != generation || !running) {
                return
            }
            val delay = backoffSeconds
            backoffSeconds = Math.min(backoffSeconds * 2L, RECONNECT_MAX_SECONDS)
            cancel(reconnectTask)
            try {
                reconnectTask = scheduler.schedule({ connect(gen) }, delay, TimeUnit.SECONDS)
            } catch (e: RuntimeException) {
                Aether.LOGGER.debug("[aether] Aether IRC reconnect rejected: {}", e.javaClass.simpleName)
            }
        }
    }

    // a dead tcp connection never fires onClose
    private fun startWatchdog() {
        synchronized(lock) {
            cancel(watchdogTask)
            try {
                watchdogTask = scheduler.scheduleWithFixedDelay(
                        { checkLiveness() }, WATCHDOG_SECONDS, WATCHDOG_SECONDS, TimeUnit.SECONDS)
            } catch (e: RuntimeException) {
                Aether.LOGGER.debug("[aether] Aether IRC watchdog rejected: {}", e.javaClass.simpleName)
            }
        }
    }

    private fun checkLiveness() {
        if (!running) {
            return
        }
        val current = socket
        if (current == null) {
            return
        }
        val idleMillis = System.currentTimeMillis() - lastActivity.get()
        if (idleMillis < WATCHDOG_SECONDS * 3L * 1000L) {
            return
        }
        Aether.LOGGER.debug("[aether] Aether IRC socket went quiet; reconnecting")
        val gen = generation
        synchronized(lock) {
            socket = null
        }
        current.abort()
        scheduleReconnect(gen)
    }

    private fun onDisconnected(gen: Int) {
        synchronized(lock) {
            if (gen != generation || !running) {
                return
            }
            socket = null
        }
        scheduleReconnect(gen)
    }

    private inner class SocketListener(private val gen: Int) : WebSocket.Listener {
        private val buffer = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            lastActivity.set(System.currentTimeMillis())
            webSocket.request(1)
            // onOpen lands before buildAsync completes
            synchronized(lock) {
                if (gen != generation || !running) {
                    webSocket.abort()
                    return
                }
                socket = webSocket
            }
            val resumeFrom = cursor
            if (!resumeFrom.isEmpty()) {
                val payload = JsonObject()
                payload.addProperty("op", "resume")
                payload.addProperty("after", resumeFrom)
                try {
                    webSocket.sendText(payload.toString(), true)
                } catch (_: RuntimeException) {
                    // a failed resume only costs backlog
                }
            }
            handler.onConnected()
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            lastActivity.set(System.currentTimeMillis())
            webSocket.request(1)
            if (buffer.length + data.length > MAX_BUFFERED_FRAME) {
                buffer.setLength(0)
                return null
            }
            buffer.append(data)
            if (!last) {
                return null
            }
            val raw = buffer.toString()
            buffer.setLength(0)
            try {
                handleFrame(raw)
            } catch (e: RuntimeException) {
                Aether.LOGGER.debug("[aether] Aether IRC frame failed: {}", e.javaClass.simpleName)
            }
            return null
        }

        override fun onPing(webSocket: WebSocket, message: ByteBuffer): CompletionStage<*>? {
            lastActivity.set(System.currentTimeMillis())
            webSocket.request(1)
            return CompletableFuture.completedFuture<Any?>(null)
        }

        override fun onPong(webSocket: WebSocket, message: ByteBuffer): CompletionStage<*>? {
            lastActivity.set(System.currentTimeMillis())
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            if (statusCode == CLOSE_UNAUTHORIZED) {
                handler.onUnauthorized()
                return null
            }
            if (statusCode == CLOSE_TOO_MANY) {
                handler.onRejected("too_many_connections")
                return null
            }
            Aether.LOGGER.debug("[aether] Aether IRC socket closed: {} {}", statusCode, reason)
            onDisconnected(gen)
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            Aether.LOGGER.debug("[aether] Aether IRC socket error: {}", error.javaClass.simpleName)
            onDisconnected(gen)
        }

        private fun handleFrame(raw: String) {
            val element = JsonParser.parseString(raw)
            if (!element.isJsonObject) {
                return
            }
            val frame = element.asJsonObject
            when (text(frame, "op")) {
                "hello" -> {
                    // empty cursor means a fresh join, so skip backlog
                    if (cursor.isEmpty()) {
                        cursor = text(frame, "latest_id")
                    }
                }
                "message" -> dispatch(frame.getAsJsonObject("message"))
                "backlog" -> {
                    val messages = frame.get("messages")
                    if (messages != null && messages.isJsonArray) {
                        for (entry in messages.asJsonArray) {
                            if (entry.isJsonObject) {
                                dispatch(entry.asJsonObject)
                            }
                        }
                    }
                }
                "error" -> handler.onSendFailed(
                        text(frame, "error"),
                        if (frame.has("retry_after")) frame.get("retry_after").asLong else 0L)
            }
        }

        private fun dispatch(message: com.google.gson.JsonObject?) {
            if (message == null) {
                return
            }
            val id = text(message, "id")
            val content = text(message, "content")
            if (content.isEmpty()) {
                return
            }
            if (!id.isEmpty()) {
                cursor = id
            }
            handler.onMessage(AetherApiClient.IrcMessage(
                    id,
                    text(message, "source"),
                    text(message, "author"),
                    text(message, "reply_to"),
                    content))
        }

        private fun text(obj: com.google.gson.JsonObject?, key: String): String {
            if (obj == null || !obj.has(key) || obj.get(key).isJsonNull) {
                return ""
            }
            try {
                return obj.get(key).asString
            } catch (_: RuntimeException) {
                return ""
            }
        }
    }

    companion object {
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
        private const val RECONNECT_BASE_SECONDS = 3L
        private const val RECONNECT_MAX_SECONDS = 120L
        private const val WATCHDOG_SECONDS = 20L
        private const val NORMAL_CLOSURE = 1000
        private const val CLOSE_UNAUTHORIZED = 4401
        private const val CLOSE_TOO_MANY = 4409
        private const val MAX_BUFFERED_FRAME = 64 * 1024

        private fun cancel(task: ScheduledFuture<*>?) {
            if (task != null) {
                task.cancel(false)
            }
        }
    }
}
