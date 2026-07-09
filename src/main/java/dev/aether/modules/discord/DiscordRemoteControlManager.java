package dev.aether.modules.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import dev.aether.Aether;
import dev.aether.config.AetherConfig;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DiscordRemoteControlManager implements WebSocket.Listener {
    private static final Object LOCK = new Object();
    private static DiscordRemoteControlManager active;

    private final RemoteControlConfig config;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "Aether Discord Remote Control");
        thread.setDaemon(true);
        return thread;
    });
    private final StringBuilder buffer = new StringBuilder();
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);

    private WebSocket socket;
    private ScheduledFuture<?> heartbeat;
    private int sequence = -1;
    private volatile boolean stopping;

    private DiscordRemoteControlManager(RemoteControlConfig config) {
        this.config = config;
    }

    public static void restartFromConfig() {
        synchronized (LOCK) {
            if (active != null) {
                active.stop();
                active = null;
            }

            RemoteControlConfig config = RemoteControlConfig.load();
            if (!config.configured()) {
                return;
            }

            active = new DiscordRemoteControlManager(config);
            active.start();
        }
    }

    public static void shutdown() {
        synchronized (LOCK) {
            if (active != null) {
                active.stop();
                active = null;
            }
        }
    }

    private void start() {
        scheduler.execute(this::connect);
    }

    private void stop() {
        stopping = true;
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
        if (socket != null) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "Stopping");
        }
        scheduler.shutdownNow();
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        socket = webSocket;
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        try {
            buffer.append(data);
            if (last) {
                handleGateway(buffer.toString());
                buffer.setLength(0);
            }
        } finally {
            webSocket.request(1);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
        if (!stopping) {
            reconnect("gateway closed");
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        Aether.LOGGER.warn("Discord remote control gateway error", error);
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
        if (!stopping) {
            reconnect("gateway error");
        }
    }

    private void connect() {
        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(URI.create("wss://gateway.discord.gg/?v=10&encoding=json"), this)
                .exceptionally(error -> {
                    Aether.LOGGER.warn("Discord remote control connection failed", error);
                    reconnect("connect failed");
                    return null;
                });
    }

    private void handleGateway(String payload) {
        JsonObject root = parseObject(payload);
        if (root == null) {
            return;
        }

        if (root.has("s") && !root.get("s").isJsonNull()) {
            sequence = root.get("s").getAsInt();
        }

        int op = root.has("op") ? root.get("op").getAsInt() : -1;
        switch (op) {
            case 10 -> {
                identify();
                long interval = root.getAsJsonObject("d").get("heartbeat_interval").getAsLong();
                heartbeat = scheduler.scheduleAtFixedRate(
                        () -> send("{\"op\":1,\"d\":" + (sequence < 0 ? "null" : sequence) + "}"),
                        interval,
                        interval,
                        TimeUnit.MILLISECONDS);
            }
            case 0 -> {
                if (Objects.equals(string(root, "t"), "MESSAGE_CREATE")) {
                    handleMessage(root.getAsJsonObject("d"));
                }
            }
            case 1 -> send("{\"op\":1,\"d\":" + (sequence < 0 ? "null" : sequence) + "}");
            case 7, 9 -> reconnect("Discord requested reconnect");
            default -> {
            }
        }
    }

    private void identify() {
        JsonObject body = new JsonObject();
        body.addProperty("op", 2);

        JsonObject data = new JsonObject();
        data.addProperty("token", config.botToken());
        data.addProperty("intents", 1 | 512 | 32768);

        JsonObject properties = new JsonObject();
        properties.addProperty("os", System.getProperty("os.name", "unknown"));
        properties.addProperty("browser", "aether");
        properties.addProperty("device", "aether");
        data.add("properties", properties);
        body.add("d", data);

        send(body.toString());
    }

    private void handleMessage(JsonObject message) {
        if (message == null) {
            return;
        }
        if (!Objects.equals(string(message, "guild_id"), config.guildId())) {
            return;
        }
        if (!Objects.equals(string(message, "channel_id"), config.channelId())) {
            return;
        }

        JsonObject author = message.has("author") && message.get("author").isJsonObject()
                ? message.getAsJsonObject("author")
                : null;
        if (author != null && author.has("bot") && author.get("bot").getAsBoolean()) {
            return;
        }

        String content = string(message, "content").trim();
        if (content.equalsIgnoreCase("!status")) {
            scheduler.execute(this::runStatus);
            return;
        }

        String prefix = config.prefix();
        if (!content.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return;
        }

        String args = content.substring(prefix.length()).trim();
        scheduler.execute(() -> runCommand(args.isBlank() ? "help" : args));
    }

    private void runCommand(String args) {
        String command = args.substring(0, firstSpaceOrEnd(args)).toLowerCase(Locale.ROOT);
        String rest = args.length() > command.length() ? args.substring(command.length()).trim() : "";
        String reply = switch (command) {
            case "start" -> sendMinecraftCommand("/aether farming", "Started Aether.");
            case "stop" -> sendMinecraftCommand("/aether stop", "Stopped Aether.");
            case "status" -> {
                runStatus();
                yield null;
            }
            case "connect" -> connectToHypixel();
            case "disconnect" -> disconnect();
            case "panic" -> panic();
            case "chat" -> rest.isBlank() ? "Usage: `" + config.prefix() + " chat <message>`" : sendChat(rest);
            case "warp" -> rest.isBlank() ? "Usage: `" + config.prefix() + " warp <place>`" : sendMinecraftCommand("/warp " + rest, "Warp command sent.");
            default -> "Commands: `" + config.prefix() + " start`, `stop`, `status`, `connect`, `disconnect`, `panic`, `chat <text>`, `warp <place>`";
        };

        if (reply != null && !reply.isBlank()) {
            postText(reply);
        }
    }

    private void runStatus() {
        sendMinecraftCommand("/aether status", "Status requested.");
        sleep(750L);
        String username = Minecraft.getInstance().getUser().getName();
        postScreenshot(config.channelId(), "", "Status for `" + username + "`");
    }

    private String sendMinecraftCommand(String command, String feedback) {
        Minecraft client = Minecraft.getInstance();
        ClientUtils.sendCommand(client, command);
        ClientUtils.sendMessage("\u00A7a[Remote Control] " + feedback, false);
        return "`" + command + "` sent.";
    }

    private String sendChat(String message) {
        Minecraft client = Minecraft.getInstance();
        ClientUtils.sendCommand(client, message);
        ClientUtils.sendMessage("\u00A7a[Remote Control] Sent " + message, false);
        return "`" + message + "` sent.";
    }

    private String connectToHypixel() {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            ServerData server = new ServerData("Hypixel", "mc.hypixel.net", ServerData.Type.OTHER);
            ConnectScreen.startConnecting(
                    new TitleScreen(),
                    client,
                    ServerAddress.parseString("mc.hypixel.net"),
                    server,
                    false,
                    null);
        });
        return "Connecting to `mc.hypixel.net`.";
    }

    private String disconnect() {
        ClientUtils.disconnectWithScreen(
                Minecraft.getInstance(),
                new TitleScreen(),
                Component.literal("Remote control disconnect requested"));
        return "Disconnect requested.";
    }

    private String panic() {
        CompletableFuture.delayedExecutor(250, TimeUnit.MILLISECONDS).execute(() -> Runtime.getRuntime().halt(1));
        return "Panic crash requested.";
    }

    private void postText(String text) {
        postEmbed("Remote Control", text, 5814783);
    }

    private void postEmbed(String title, String description, int color) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", title);
        embed.addProperty("description", description);
        embed.addProperty("color", color);

        JsonArray embeds = new JsonArray();
        embeds.add(embed);

        JsonObject body = new JsonObject();
        body.add("embeds", embeds);
        sendAsync(request("/channels/" + config.channelId() + "/messages")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())));
    }

    private void postScreenshot(String channelId, String content, String title) {
        byte[] image;
        try {
            image = screenshot();
        } catch (IOException e) {
            Aether.LOGGER.warn("Could not capture Discord remote control screenshot", e);
            postText(title + "\nCould not capture screenshot.");
            return;
        }

        String boundary = "Aether" + System.currentTimeMillis();
        JsonObject payload = new JsonObject();
        if (content != null && !content.isBlank()) {
            payload.addProperty("content", content);
        }

        JsonObject embedImage = new JsonObject();
        embedImage.addProperty("url", "attachment://status.png");

        JsonObject embed = new JsonObject();
        embed.addProperty("title", "Status Update");
        embed.addProperty("description", title == null ? "Remote control status update." : title);
        embed.addProperty("color", 5814783);
        embed.add("image", embedImage);

        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        payload.add("embeds", embeds);

        sendAsync(request("/channels/" + channelId + "/messages")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(multipart(boundary, payload.toString(), image)));
    }

    private byte[] screenshot() throws IOException {
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            final Path[] tempRef = new Path[1];
            Screenshot.takeScreenshot(client.getMainRenderTarget(), image -> {
                try (NativeImage captured = image) {
                    tempRef[0] = Files.createTempFile("aether-status-", ".png");
                    captured.writeToFile(tempRef[0]);
                    future.complete(Files.readAllBytes(tempRef[0]));
                } catch (IOException e) {
                    future.completeExceptionally(e);
                } finally {
                    if (tempRef[0] != null) {
                        try {
                            Files.deleteIfExists(tempRef[0]);
                        } catch (IOException ignored) {
                        }
                    }
                }
            });
        });

        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IOException("Timed out while capturing screenshot", e);
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("https://discord.com/api/v10" + path))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bot " + config.botToken());
    }

    private void sendAsync(HttpRequest.Builder builder) {
        http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).thenAccept(response -> {
            if (response.statusCode() < 200 || response.statusCode() > 299) {
                Aether.LOGGER.warn("Discord remote control request failed: HTTP {} {}", response.statusCode(), response.body());
            }
        });
    }

    private void send(String text) {
        if (socket != null) {
            socket.sendText(text, true);
        }
    }

    private void reconnect(String reason) {
        if (!reconnecting.compareAndSet(false, true)) {
            return;
        }

        Aether.LOGGER.info("Reconnecting Discord remote control: {}", reason);
        scheduler.schedule(() -> {
            reconnecting.set(false);
            connect();
        }, 5, TimeUnit.SECONDS);
    }

    private HttpRequest.BodyPublisher multipart(String boundary, String payload, byte[] image) {
        String newline = "\r\n";
        byte[] start = ("--" + boundary + newline
                + "Content-Disposition: form-data; name=\"payload_json\"" + newline
                + newline
                + payload + newline
                + "--" + boundary + newline
                + "Content-Disposition: form-data; name=\"files[0]\"; filename=\"status.png\"" + newline
                + "Content-Type: image/png" + newline
                + newline).getBytes(StandardCharsets.UTF_8);
        byte[] end = (newline + "--" + boundary + "--" + newline).getBytes(StandardCharsets.UTF_8);

        return HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofByteArray(start),
                HttpRequest.BodyPublishers.ofByteArray(image),
                HttpRequest.BodyPublishers.ofByteArray(end));
    }

    private static JsonObject parseObject(String payload) {
        try {
            return JsonParser.parseString(payload).getAsJsonObject();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String string(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString();
    }

    private static int firstSpaceOrEnd(String value) {
        int index = value.indexOf(' ');
        return index < 0 ? value.length() : index;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record RemoteControlConfig(
            boolean enabled,
            String botToken,
            String guildId,
            String channelId,
            String prefix
    ) {
        private boolean configured() {
            return enabled
                    && !botToken.isBlank()
                    && !guildId.isBlank()
                    && !channelId.isBlank()
                    && !prefix.isBlank();
        }

        private static RemoteControlConfig load() {
            String prefix = AetherConfig.REMOTE_CONTROL_COMMAND_PREFIX.get();
            if (prefix == null || prefix.isBlank()) {
                prefix = "!aether";
            }
            return new RemoteControlConfig(
                    AetherConfig.REMOTE_CONTROL_ENABLED.get(),
                    safe(AetherConfig.REMOTE_CONTROL_BOT_TOKEN.get()),
                    safe(AetherConfig.REMOTE_CONTROL_GUILD_ID.get()),
                    safe(AetherConfig.REMOTE_CONTROL_CHANNEL_ID.get()),
                    prefix.trim());
        }

        private static String safe(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
