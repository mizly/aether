package dev.aether.modules.irc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aether.Aether;
import dev.aether.telemetry.AetherApiClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

// reconnects on its own and replays whatever it missed
public final class AetherIrcSocket {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final long RECONNECT_BASE_SECONDS = 3L;
    private static final long RECONNECT_MAX_SECONDS = 120L;
    private static final long WATCHDOG_SECONDS = 20L;
    private static final int NORMAL_CLOSURE = 1000;
    private static final int CLOSE_UNAUTHORIZED = 4401;
    private static final int CLOSE_TOO_MANY = 4409;
    private static final int MAX_BUFFERED_FRAME = 64 * 1024;

    public interface Handler {
        void onMessage(AetherApiClient.IrcMessage message);

        void onConnected();

        void onUnauthorized();

        void onRejected(String reason);

        void onSendFailed(String error, long retryAfterSeconds);
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    private final Object lock = new Object();
    private final ScheduledExecutorService scheduler;
    private final Handler handler;
    private final AtomicLong lastActivity = new AtomicLong();

    private volatile WebSocket socket;
    private volatile boolean running;
    private volatile int generation;
    private volatile String token = "";
    private volatile String cursor = "";
    private long backoffSeconds = RECONNECT_BASE_SECONDS;
    private ScheduledFuture<?> reconnectTask;
    private ScheduledFuture<?> watchdogTask;

    public AetherIrcSocket(ScheduledExecutorService scheduler, Handler handler) {
        this.scheduler = scheduler;
        this.handler = handler;
    }

    public boolean isConnected() {
        WebSocket current = socket;
        return current != null && !current.isInputClosed() && !current.isOutputClosed();
    }

    public void start(String authToken) {
        int gen;
        synchronized (lock) {
            if (running) {
                return;
            }
            running = true;
            token = authToken == null ? "" : authToken;
            cursor = "";
            backoffSeconds = RECONNECT_BASE_SECONDS;
            gen = ++generation;
        }
        connect(gen);
        startWatchdog();
    }

    public void stop() {
        WebSocket current;
        synchronized (lock) {
            if (!running) {
                return;
            }
            running = false;
            generation++;
            cancel(reconnectTask);
            reconnectTask = null;
            cancel(watchdogTask);
            watchdogTask = null;
            current = socket;
            socket = null;
        }

        if (current != null) {
            try {
                current.sendClose(NORMAL_CLOSURE, "client_stop");
            } catch (RuntimeException ignored) {
                current.abort();
            }
        }
    }

    public boolean send(String content) {
        WebSocket current = socket;
        if (current == null || current.isOutputClosed()) {
            return false;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("op", "send");
        payload.addProperty("content", content);

        try {
            current.sendText(payload.toString(), true);
            return true;
        } catch (RuntimeException e) {
            Aether.LOGGER.debug("[aether] Aether IRC socket send failed: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    private void connect(int gen) {
        if (gen != generation || !running) {
            return;
        }

        String authToken = token;
        if (authToken.isEmpty()) {
            return;
        }

        try {
            http.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + authToken)
                    .connectTimeout(CONNECT_TIMEOUT)
                    .buildAsync(URI.create(AetherApiClient.IRC_SOCKET_URL), new SocketListener(gen))
                    .whenComplete((ws, error) -> {
                        if (error != null) {
                            handleFailure(gen, error);
                            return;
                        }
                        synchronized (lock) {
                            if (gen != generation || !running) {
                                ws.abort();
                                return;
                            }
                            backoffSeconds = RECONNECT_BASE_SECONDS;
                        }
                    });
        } catch (RuntimeException e) {
            handleFailure(gen, e);
        }
    }

    // the real status is on the cause, not the wrapper
    private void handleFailure(int gen, Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }

        if (cause instanceof WebSocketHandshakeException handshake) {
            int status = handshake.getResponse().statusCode();
            if (status == 401) {
                handler.onUnauthorized();
                return;
            }
            if (status == 403) {
                handler.onRejected("blocked");
                return;
            }
        }

        Aether.LOGGER.debug("[aether] Aether IRC socket connect failed: {}",
                cause == null ? "unknown" : cause.getClass().getSimpleName());
        scheduleReconnect(gen);
    }

    private void scheduleReconnect(int gen) {
        long delay;
        synchronized (lock) {
            if (gen != generation || !running) {
                return;
            }
            delay = backoffSeconds;
            backoffSeconds = Math.min(backoffSeconds * 2L, RECONNECT_MAX_SECONDS);
            cancel(reconnectTask);
            try {
                reconnectTask = scheduler.schedule(() -> connect(gen), delay, TimeUnit.SECONDS);
            } catch (RuntimeException e) {
                Aether.LOGGER.debug("[aether] Aether IRC reconnect rejected: {}", e.getClass().getSimpleName());
            }
        }
    }

    // a dead tcp connection never fires onClose
    private void startWatchdog() {
        synchronized (lock) {
            cancel(watchdogTask);
            try {
                watchdogTask = scheduler.scheduleWithFixedDelay(
                        this::checkLiveness, WATCHDOG_SECONDS, WATCHDOG_SECONDS, TimeUnit.SECONDS);
            } catch (RuntimeException e) {
                Aether.LOGGER.debug("[aether] Aether IRC watchdog rejected: {}", e.getClass().getSimpleName());
            }
        }
    }

    private void checkLiveness() {
        if (!running) {
            return;
        }

        WebSocket current = socket;
        if (current == null) {
            return;
        }

        long idleMillis = System.currentTimeMillis() - lastActivity.get();
        if (idleMillis < WATCHDOG_SECONDS * 3L * 1000L) {
            return;
        }

        Aether.LOGGER.debug("[aether] Aether IRC socket went quiet; reconnecting");
        int gen = generation;
        synchronized (lock) {
            socket = null;
        }
        current.abort();
        scheduleReconnect(gen);
    }

    private void onDisconnected(int gen) {
        synchronized (lock) {
            if (gen != generation || !running) {
                return;
            }
            socket = null;
        }
        scheduleReconnect(gen);
    }

    private static void cancel(ScheduledFuture<?> task) {
        if (task != null) {
            task.cancel(false);
        }
    }

    private final class SocketListener implements WebSocket.Listener {
        private final int gen;
        private final StringBuilder buffer = new StringBuilder();

        private SocketListener(int gen) {
            this.gen = gen;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            lastActivity.set(System.currentTimeMillis());
            webSocket.request(1);

            // onOpen lands before buildAsync completes
            synchronized (lock) {
                if (gen != generation || !running) {
                    webSocket.abort();
                    return;
                }
                socket = webSocket;
            }

            String resumeFrom = cursor;
            if (!resumeFrom.isEmpty()) {
                JsonObject payload = new JsonObject();
                payload.addProperty("op", "resume");
                payload.addProperty("after", resumeFrom);
                try {
                    webSocket.sendText(payload.toString(), true);
                } catch (RuntimeException ignored) {
                    // a failed resume only costs backlog
                }
            }

            handler.onConnected();
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            lastActivity.set(System.currentTimeMillis());
            webSocket.request(1);

            if (buffer.length() + data.length() > MAX_BUFFERED_FRAME) {
                buffer.setLength(0);
                return null;
            }

            buffer.append(data);
            if (!last) {
                return null;
            }

            String raw = buffer.toString();
            buffer.setLength(0);

            try {
                handleFrame(raw);
            } catch (RuntimeException e) {
                Aether.LOGGER.debug("[aether] Aether IRC frame failed: {}", e.getClass().getSimpleName());
            }
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, java.nio.ByteBuffer message) {
            lastActivity.set(System.currentTimeMillis());
            webSocket.request(1);
            return WebSocket.Listener.super.onPing(webSocket, message);
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, java.nio.ByteBuffer message) {
            lastActivity.set(System.currentTimeMillis());
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (statusCode == CLOSE_UNAUTHORIZED) {
                handler.onUnauthorized();
                return null;
            }
            if (statusCode == CLOSE_TOO_MANY) {
                handler.onRejected("too_many_connections");
                return null;
            }

            Aether.LOGGER.debug("[aether] Aether IRC socket closed: {} {}", statusCode, reason);
            onDisconnected(gen);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            Aether.LOGGER.debug("[aether] Aether IRC socket error: {}", error.getClass().getSimpleName());
            onDisconnected(gen);
        }

        private void handleFrame(String raw) {
            JsonElement element = JsonParser.parseString(raw);
            if (element == null || !element.isJsonObject()) {
                return;
            }

            JsonObject frame = element.getAsJsonObject();
            switch (text(frame, "op")) {
                case "hello" -> {
                    // empty cursor means a fresh join, so skip backlog
                    if (cursor.isEmpty()) {
                        cursor = text(frame, "latest_id");
                    }
                }
                case "message" -> dispatch(frame.getAsJsonObject("message"));
                case "backlog" -> {
                    JsonElement messages = frame.get("messages");
                    if (messages != null && messages.isJsonArray()) {
                        for (JsonElement entry : messages.getAsJsonArray()) {
                            if (entry.isJsonObject()) {
                                dispatch(entry.getAsJsonObject());
                            }
                        }
                    }
                }
                case "error" -> handler.onSendFailed(
                        text(frame, "error"),
                        frame.has("retry_after") ? frame.get("retry_after").getAsLong() : 0L);
                default -> {
                }
            }
        }

        private void dispatch(JsonObject message) {
            if (message == null) {
                return;
            }

            String id = text(message, "id");
            String content = text(message, "content");
            if (content.isEmpty()) {
                return;
            }

            if (!id.isEmpty()) {
                cursor = id;
            }

            handler.onMessage(new AetherApiClient.IrcMessage(
                    id,
                    text(message, "source"),
                    text(message, "author"),
                    content));
        }

        private String text(JsonObject obj, String key) {
            if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
                return "";
            }
            try {
                return obj.get(key).getAsString();
            } catch (RuntimeException e) {
                return "";
            }
        }
    }
}
