package dev.aether.modules.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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

    private static final String[][] WARPS = {
            {"Hub", "/warp hub"},
            {"Garden", "/warp garden"},
            {"Desk", "/warp desk"},
            {"Island", "/warp island"},
            {"Forge", "/warpforge"},
            {"Skyblock", "/play skyblock"},
            {"Lobby", "/lobby"}
    };

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
    private volatile String applicationId = "";
    private volatile boolean slashRegistered;

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

    public static void sendFailsafeAlert(String reason, String actionDone) {
        DiscordRemoteControlManager current;
        synchronized (LOCK) {
            current = active;
        }
        if (current != null && !current.config.channelId().isBlank()) {
            current.scheduler.execute(() -> current.postFailsafeAlert(reason, actionDone));
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
                JsonObject d = root.has("d") && root.get("d").isJsonObject() ? root.getAsJsonObject("d") : null;
                switch (string(root, "t")) {
                    case "READY" -> onReady(d);
                    case "MESSAGE_CREATE" -> handleMessage(d);
                    case "INTERACTION_CREATE" -> handleInteraction(d);
                    default -> {
                    }
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
            case "panel" -> {
                postPanel();
                yield null;
            }
            case "start" -> sendMinecraftCommand("/aether farming", "Started Aether.");
            case "stop" -> sendMinecraftCommand("/aether stop", "Stopped Aether.");
            case "status" -> {
                runStatus();
                yield null;
            }
            case "connect" -> connectToHypixel();
            case "disconnect" -> disconnect();
            case "panic" -> panic();
            case "chat" -> {
                if (rest.isBlank()) {
                    postChatButton();
                    yield null;
                }
                yield sendChat(rest);
            }
            case "warp" -> rest.isBlank() ? "Usage: `" + config.prefix() + " warp <place>`" : sendMinecraftCommand("/warp " + rest, "Warp command sent.");
            default -> "Commands: `" + config.prefix() + " panel`, `start`, `stop`, `status`, `connect`, `disconnect`, `panic`, `chat <text>`, `warp <place>`";
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
        ClientUtils.sendCommand(command);
        ClientUtils.sendMessage("\u00A7a[Remote Control] " + feedback, false);
        return "`" + command + "` sent.";
    }

    private String sendChat(String message) {
        Minecraft client = Minecraft.getInstance();
        ClientUtils.sendCommand(message);
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
                .POST(multipartImage(boundary, payload.toString(), image, "status.png")));
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

    private HttpRequest.BodyPublisher multipartImage(String boundary, String payload, byte[] image, String filename) {
        String newline = "\r\n";
        byte[] start = ("--" + boundary + newline
                + "Content-Disposition: form-data; name=\"payload_json\"" + newline
                + newline
                + payload + newline
                + "--" + boundary + newline
                + "Content-Disposition: form-data; name=\"files[0]\"; filename=\"" + filename + "\"" + newline
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

    private void onReady(JsonObject data) {
        if (data == null) {
            return;
        }
        String id = "";
        if (data.has("application") && data.get("application").isJsonObject()) {
            id = string(data.getAsJsonObject("application"), "id");
        }
        if (id.isBlank() && data.has("user") && data.get("user").isJsonObject()) {
            id = string(data.getAsJsonObject("user"), "id");
        }
        applicationId = id;
        registerSlashCommands();
    }

    private void registerSlashCommands() {
        if (slashRegistered || applicationId.isBlank() || config.guildId().isBlank()) {
            return;
        }
        slashRegistered = true;

        JsonArray commands = new JsonArray();
        commands.add(buildSlashCommand());
        sendAsync(request("/applications/" + applicationId + "/guilds/" + config.guildId() + "/commands")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(commands.toString(), StandardCharsets.UTF_8)));
    }

    private JsonObject buildSlashCommand() {
        JsonObject command = new JsonObject();
        command.addProperty("name", "aether");
        command.addProperty("description", "Remote control your Aether client");

        JsonArray options = new JsonArray();
        options.add(subcommand("panel", "Open the control panel"));
        options.add(subcommand("start", "Start farming"));
        options.add(subcommand("stop", "Stop the macro"));
        options.add(subcommand("status", "Post a status screenshot"));
        options.add(subcommand("connect", "Connect to Hypixel"));
        options.add(subcommand("disconnect", "Disconnect to the title screen"));
        options.add(subcommand("panic", "Force-close the game"));

        options.add(subcommand("chat", "Open a box to send a chat message or command"));

        JsonObject warp = subcommand("warp", "Warp somewhere");
        warp.add("options", singleOption(option(3, "place", "Destination", true)));
        options.add(warp);

        JsonObject setping = subcommand("setping", "Set who gets @'d on failsafe in this channel");
        setping.add("options", singleOption(option(6, "user", "Who to @ on failsafe", true)));
        options.add(setping);

        options.add(subcommand("clearping", "Reset failsafe pings to @everyone"));

        command.add("options", options);
        return command;
    }

    private static JsonObject subcommand(String name, String description) {
        JsonObject sub = new JsonObject();
        sub.addProperty("type", 1);
        sub.addProperty("name", name);
        sub.addProperty("description", description);
        return sub;
    }

    private static JsonObject option(int type, String name, String description, boolean required) {
        JsonObject option = new JsonObject();
        option.addProperty("type", type);
        option.addProperty("name", name);
        option.addProperty("description", description);
        option.addProperty("required", required);
        return option;
    }

    private static JsonArray singleOption(JsonObject option) {
        JsonArray array = new JsonArray();
        array.add(option);
        return array;
    }

    private void handleInteraction(JsonObject interaction) {
        if (interaction == null) {
            return;
        }
        if (!Objects.equals(string(interaction, "guild_id"), config.guildId())) {
            return;
        }

        int type = interaction.has("type") ? interaction.get("type").getAsInt() : -1;
        String id = string(interaction, "id");
        String token = string(interaction, "token");
        String channelId = string(interaction, "channel_id");
        if (id.isBlank() || token.isBlank()) {
            return;
        }
        if (!Objects.equals(channelId, config.channelId())) {
            return;
        }

        JsonObject data = interaction.has("data") && interaction.get("data").isJsonObject()
                ? interaction.getAsJsonObject("data")
                : null;

        switch (type) {
            case 2 -> scheduler.execute(() -> handleSlashCommand(id, token, channelId, data));
            case 5 -> scheduler.execute(() -> handleChatModal(id, token, data));
            case 3 -> {
                if (data != null) {
                    JsonObject message = interaction.has("message") && interaction.get("message").isJsonObject()
                            ? interaction.getAsJsonObject("message")
                            : null;
                    String messageId = message != null ? string(message, "id") : "";
                    scheduler.execute(() -> handleComponent(id, token, channelId, messageId, string(data, "custom_id"), data));
                }
            }
            default -> {
            }
        }
    }

    private void handleSlashCommand(String id, String token, String channelId, JsonObject data) {
        JsonObject sub = firstSubcommand(data);
        String task = sub == null ? "panel" : string(sub, "name");
        if ("panel".equals(task)) {
            respondMessage(id, token, buildPanelEmbed("Choose an action."), buildPanelButtons(), false);
            return;
        }
        if ("status".equals(task)) {
            respondMessage(id, token, buildPanelEmbed("Status requested."), null, true);
            runStatus();
            return;
        }
        if ("chat".equals(task)) {
            openChatModal(id, token);
            return;
        }

        String reply = switch (task) {
            case "setping" -> {
                String userId = optionValue(sub, "user");
                if (userId.isBlank()) {
                    yield "Pick a `user` to receive failsafe pings in this channel.";
                }
                setPingTarget(channelId, userId);
                yield "Saved. Failsafe alerts in this channel will now ping that user.";
            }
            case "clearping" -> {
                setPingTarget(channelId, "");
                yield "Cleared. Failsafe alerts in this channel will ping @everyone.";
            }
            case "start" -> sendMinecraftCommand("/aether farming", "Started Aether.");
            case "stop" -> sendMinecraftCommand("/aether stop", "Stopped Aether.");
            case "connect" -> connectToHypixel();
            case "disconnect" -> disconnect();
            case "panic" -> panic();
            case "warp" -> {
                String place = optionValue(sub, "place");
                yield place.isBlank() ? "Provide a `place` for the warp task." : sendMinecraftCommand("/warp " + place, "Warp command sent.");
            }
            default -> "Unknown task.";
        };
        respondMessage(id, token, buildPanelEmbed(reply), null, true);
    }

    private static JsonObject firstSubcommand(JsonObject data) {
        if (data == null || !data.has("options") || !data.get("options").isJsonArray()) {
            return null;
        }
        JsonArray options = data.getAsJsonArray("options");
        if (options.size() == 0 || !options.get(0).isJsonObject()) {
            return null;
        }
        return options.get(0).getAsJsonObject();
    }

    private void handleComponent(String id, String token, String channelId, String messageId, String customId, JsonObject data) {
        switch (customId) {
            case "aether:start" -> updatePanel(id, token, sendMinecraftCommand("/aether farming", "Started Aether."));
            case "aether:stop" -> updatePanel(id, token, sendMinecraftCommand("/aether stop", "Stopped Aether."));
            case "aether:panic" -> updatePanel(id, token, panic());
            case "aether:warps" -> updatePanelComponents(id, token, "Choose a warp.", buildWarpComponents());
            case "aether:back" -> updatePanelComponents(id, token, "Choose an action.", buildPanelButtons());
            case "aether:chat" -> openChatModal(id, token);
            case "aether:status" -> {
                deferUpdate(id, token);
                editPanelWithScreenshot(channelId, messageId, "Status update.");
            }
            case "aether:connect" -> {
                String reply = connectToHypixel();
                deferUpdate(id, token);
                scheduler.schedule(() -> editPanelWithScreenshot(channelId, messageId, reply), 7L, TimeUnit.SECONDS);
            }
            case "aether:disconnect" -> {
                String reply = disconnect();
                deferUpdate(id, token);
                scheduler.schedule(() -> editPanelWithScreenshot(channelId, messageId, reply), 2L, TimeUnit.SECONDS);
            }
            case "aether:warp" -> {
                String place = firstSelected(data);
                String reply = place.isBlank() ? "No warp selected." : sendMinecraftCommand(place, "Warp command sent.");
                deferUpdate(id, token);
                scheduler.schedule(() -> editPanelWithScreenshot(channelId, messageId, reply), 7L, TimeUnit.SECONDS);
            }
            default -> {
            }
        }
    }

    private void handleChatModal(String id, String token, JsonObject data) {
        String message = modalValue(data, "aether:chat-message").trim();
        String reply = message.isBlank() ? "Chat message cannot be blank." : sendChat(message);
        respondMessage(id, token, buildPanelEmbed(reply), null, true);
    }

    private void postChatButton() {
        JsonArray buttons = new JsonArray();
        buttons.add(button("Chat", "aether:chat", 1));
        JsonArray rows = new JsonArray();
        rows.add(actionRow(buttons));

        JsonObject payload = new JsonObject();
        JsonArray embeds = new JsonArray();
        embeds.add(buildPanelEmbed("Press the button to type a message."));
        payload.add("embeds", embeds);
        payload.add("components", rows);
        sendAsync(request("/channels/" + config.channelId() + "/messages")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8)));
    }

    private void postPanel() {
        JsonObject payload = new JsonObject();
        JsonArray embeds = new JsonArray();
        embeds.add(buildPanelEmbed("Choose an action."));
        payload.add("embeds", embeds);
        payload.add("components", buildPanelButtons());
        sendAsync(request("/channels/" + config.channelId() + "/messages")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8)));
    }

    private JsonObject buildPanelEmbed(String statusLine) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", "Aether Remote Control");
        embed.addProperty("description", statusLine == null || statusLine.isBlank() ? "Choose an action." : statusLine);
        embed.addProperty("color", 5814783);
        return embed;
    }

    private JsonArray buildPanelButtons() {
        JsonArray topRow = new JsonArray();
        topRow.add(button("Start", "aether:start", 3));
        topRow.add(button("Stop", "aether:stop", 4));
        topRow.add(button("Status", "aether:status", 1));
        topRow.add(button("Warps", "aether:warps", 2));
        topRow.add(button("Chat", "aether:chat", 2));

        JsonArray bottomRow = new JsonArray();
        bottomRow.add(button("Connect", "aether:connect", 2));
        bottomRow.add(button("Disconnect", "aether:disconnect", 2));
        bottomRow.add(button("Panic", "aether:panic", 4));

        JsonArray rows = new JsonArray();
        rows.add(actionRow(topRow));
        rows.add(actionRow(bottomRow));
        return rows;
    }

    private JsonArray buildWarpComponents() {
        JsonObject select = new JsonObject();
        select.addProperty("type", 3);
        select.addProperty("custom_id", "aether:warp");
        select.addProperty("placeholder", "Choose a warp");
        select.addProperty("min_values", 1);
        select.addProperty("max_values", 1);
        JsonArray options = new JsonArray();
        for (String[] warp : WARPS) {
            JsonObject option = new JsonObject();
            option.addProperty("label", warp[0]);
            option.addProperty("value", warp[1]);
            options.add(option);
        }
        select.add("options", options);

        JsonArray selectComponents = new JsonArray();
        selectComponents.add(select);

        JsonArray backComponents = new JsonArray();
        backComponents.add(button("Back", "aether:back", 2));

        JsonArray rows = new JsonArray();
        rows.add(actionRow(selectComponents));
        rows.add(actionRow(backComponents));
        return rows;
    }

    private void openChatModal(String id, String token) {
        JsonObject input = new JsonObject();
        input.addProperty("type", 4);
        input.addProperty("custom_id", "aether:chat-message");
        input.addProperty("label", "Message");
        input.addProperty("style", 1);
        input.addProperty("min_length", 1);
        input.addProperty("max_length", 256);
        input.addProperty("required", true);

        JsonArray inputRow = new JsonArray();
        inputRow.add(input);
        JsonArray rows = new JsonArray();
        rows.add(actionRow(inputRow));

        JsonObject modal = new JsonObject();
        modal.addProperty("custom_id", "aether:chat-modal");
        modal.addProperty("title", "Send chat / command");
        modal.add("components", rows);

        JsonObject body = new JsonObject();
        body.addProperty("type", 9);
        body.add("data", modal);
        postCallback(id, token, body);
    }

    private void respondMessage(String id, String token, JsonObject embed, JsonArray components, boolean ephemeral) {
        JsonObject data = new JsonObject();
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        data.add("embeds", embeds);
        if (components != null) {
            data.add("components", components);
        }
        if (ephemeral) {
            data.addProperty("flags", 64);
        }

        JsonObject body = new JsonObject();
        body.addProperty("type", 4);
        body.add("data", data);
        postCallback(id, token, body);
    }

    private void updatePanel(String id, String token, String statusLine) {
        updatePanelComponents(id, token, statusLine, buildPanelButtons());
    }

    private void updatePanelComponents(String id, String token, String statusLine, JsonArray components) {
        JsonObject data = new JsonObject();
        JsonArray embeds = new JsonArray();
        embeds.add(buildPanelEmbed(statusLine));
        data.add("embeds", embeds);
        data.add("components", components);

        JsonObject body = new JsonObject();
        body.addProperty("type", 7);
        body.add("data", data);
        postCallback(id, token, body);
    }

    private void deferUpdate(String id, String token) {
        JsonObject body = new JsonObject();
        body.addProperty("type", 6);
        postCallback(id, token, body);
    }

    private void postCallback(String id, String token, JsonObject body) {
        sendAsync(HttpRequest.newBuilder(URI.create("https://discord.com/api/v10/interactions/" + id + "/" + token + "/callback"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)));
    }

    private void editPanelWithScreenshot(String channelId, String messageId, String statusLine) {
        if (channelId.isBlank() || messageId.isBlank()) {
            return;
        }

        JsonObject payload = new JsonObject();
        JsonArray embeds = new JsonArray();
        JsonObject embed = buildPanelEmbed(statusLine);
        JsonArray components = buildPanelButtons();

        byte[] image;
        try {
            image = screenshot();
        } catch (IOException e) {
            embeds.add(embed);
            payload.add("embeds", embeds);
            payload.add("components", components);
            sendAsync(request("/channels/" + channelId + "/messages/" + messageId)
                    .header("Content-Type", "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8)));
            return;
        }

        JsonObject img = new JsonObject();
        img.addProperty("url", "attachment://control.png");
        embed.add("image", img);
        embeds.add(embed);
        payload.add("embeds", embeds);
        payload.add("components", components);

        String boundary = "Aether" + System.currentTimeMillis();
        sendAsync(request("/channels/" + channelId + "/messages/" + messageId)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .method("PATCH", multipartImage(boundary, payload.toString(), image, "control.png")));
    }

    private void postFailsafeAlert(String reason, String actionDone) {
        String description = "Aether detected a failsafe.\n**Reason:** " + reason
                + (actionDone == null || actionDone.isBlank() ? "" : "\n**Action:** " + actionDone);

        String target = pingTargetFor(config.channelId());
        JsonObject payload = new JsonObject();
        JsonObject allowedMentions = new JsonObject();
        if (target.isBlank()) {
            payload.addProperty("content", "@everyone\n-# *Tip: run /aether setping and pick a user to send failsafe pings to just that person.*");
            JsonArray parse = new JsonArray();
            parse.add("everyone");
            allowedMentions.add("parse", parse);
        } else {
            payload.addProperty("content", "<@" + target + ">");
            JsonArray users = new JsonArray();
            users.add(target);
            allowedMentions.add("users", users);
        }
        payload.add("allowed_mentions", allowedMentions);

        JsonObject embed = new JsonObject();
        embed.addProperty("title", "Failsafe Alert");
        embed.addProperty("description", description);
        embed.addProperty("color", 15158332);

        byte[] image;
        try {
            image = screenshot();
        } catch (IOException e) {
            JsonArray embeds = new JsonArray();
            embeds.add(embed);
            payload.add("embeds", embeds);
            sendAsync(request("/channels/" + config.channelId() + "/messages")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8)));
            return;
        }

        JsonObject img = new JsonObject();
        img.addProperty("url", "attachment://failsafe.png");
        embed.add("image", img);
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        payload.add("embeds", embeds);

        String boundary = "Aether" + System.currentTimeMillis();
        sendAsync(request("/channels/" + config.channelId() + "/messages")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(multipartImage(boundary, payload.toString(), image, "failsafe.png")));
    }

    private String pingTargetFor(String channelId) {
        if (channelId == null || channelId.isBlank()) {
            return "";
        }
        JsonObject targets = loadPingTargets();
        return targets.has(channelId) && !targets.get(channelId).isJsonNull()
                ? targets.get(channelId).getAsString()
                : "";
    }

    private void setPingTarget(String channelId, String userId) {
        if (channelId == null || channelId.isBlank()) {
            return;
        }
        JsonObject targets = loadPingTargets();
        if (userId == null || userId.isBlank()) {
            targets.remove(channelId);
        } else {
            targets.addProperty(channelId, userId);
        }
        AetherConfig.REMOTE_CONTROL_PING_TARGETS.set(targets.toString());
        AetherConfig.save();
    }

    private static JsonObject loadPingTargets() {
        JsonObject parsed = parseObject(AetherConfig.REMOTE_CONTROL_PING_TARGETS.get());
        return parsed == null ? new JsonObject() : parsed;
    }

    private static JsonObject actionRow(JsonArray components) {
        JsonObject row = new JsonObject();
        row.addProperty("type", 1);
        row.add("components", components);
        return row;
    }

    private static JsonObject button(String label, String customId, int style) {
        JsonObject button = new JsonObject();
        button.addProperty("type", 2);
        button.addProperty("label", label);
        button.addProperty("style", style);
        button.addProperty("custom_id", customId);
        return button;
    }

    private static String optionValue(JsonObject data, String name) {
        if (data == null || !data.has("options") || !data.get("options").isJsonArray()) {
            return "";
        }
        for (JsonElement element : data.getAsJsonArray("options")) {
            JsonObject option = element.getAsJsonObject();
            if (name.equals(string(option, "name")) && option.has("value")) {
                return option.get("value").getAsString();
            }
        }
        return "";
    }

    private static String firstSelected(JsonObject data) {
        if (data == null || !data.has("values") || !data.get("values").isJsonArray()) {
            return "";
        }
        JsonArray values = data.getAsJsonArray("values");
        return values.size() == 0 ? "" : values.get(0).getAsString();
    }

    private static String modalValue(JsonObject data, String customId) {
        if (data == null || !data.has("components") || !data.get("components").isJsonArray()) {
            return "";
        }
        for (JsonElement rowElement : data.getAsJsonArray("components")) {
            JsonObject row = rowElement.getAsJsonObject();
            if (!row.has("components") || !row.get("components").isJsonArray()) {
                continue;
            }
            for (JsonElement componentElement : row.getAsJsonArray("components")) {
                JsonObject component = componentElement.getAsJsonObject();
                if (customId.equals(string(component, "custom_id"))) {
                    return string(component, "value");
                }
            }
        }
        return "";
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
