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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DiscordRemoteControlManager implements WebSocket.Listener {
    private static final Object LOCK = new Object();
    private static final int GATEWAY_INTENTS = 1 | 512 | 32768;
    private static final int BUTTON_STYLE_GRAY = 2;
    private static final int BUTTON_STYLE_DANGER = 4;
    private static final String PANEL_COMMAND = "!control";
    private static final String[] WARP_DESTINATIONS = {
            "hub",
            "garden",
            "desk",
            "island",
            "elizabeth",
            "forge",
            "skyblock",
            "lobby"
    };
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
    private final Map<String, String> panelMessageByChannel = new ConcurrentHashMap<>();

    private WebSocket socket;
    private ScheduledFuture<?> heartbeat;
    private int sequence = -1;
    private volatile boolean stopping;
    private volatile boolean slashCommandsRegistered;
    private volatile String applicationId = "";

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
                if (Objects.equals(string(root, "t"), "READY")) {
                    handleReady(root.getAsJsonObject("d"));
                } else if (Objects.equals(string(root, "t"), "MESSAGE_CREATE")) {
                    handleMessage(root.getAsJsonObject("d"));
                } else if (Objects.equals(string(root, "t"), "INTERACTION_CREATE")) {
                    handleInteraction(root.getAsJsonObject("d"));
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
        data.addProperty("intents", GATEWAY_INTENTS);

        JsonObject properties = new JsonObject();
        properties.addProperty("os", System.getProperty("os.name", "unknown"));
        properties.addProperty("browser", "aether");
        properties.addProperty("device", "aether");
        data.add("properties", properties);
        body.add("d", data);

        send(body.toString());
    }

    private void handleReady(JsonObject data) {
        JsonObject user = object(data, "user");
        if (user != null) {
            String id = string(user, "id");
            if (!id.isBlank()) {
                applicationId = id;
            }
        }

        registerSlashCommands();
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
        if (content.equalsIgnoreCase(PANEL_COMMAND)) {
            scheduler.execute(() -> sendControlPanel(config.channelId(), "Ready."));
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
            case "panel", "control" -> {
                sendControlPanel(config.channelId(), "Ready.");
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
            case "chat" -> rest.isBlank() ? "Usage: `" + config.prefix() + " chat <message>`" : sendChat(rest);
            case "warp" -> rest.isBlank() ? "Usage: `" + config.prefix() + " warp <place>`" : sendMinecraftCommand("/warp " + rest, "Warp command sent.");
            default -> "Commands: `" + config.prefix() + " panel`, `start`, `stop`, `status`, `connect`, `disconnect`, `panic`, `chat <text>`, `warp <place>`";
        };

        if (reply != null && !reply.isBlank()) {
            postText(reply);
        }
    }

    private void handleInteraction(JsonObject interaction) {
        if (interaction == null) {
            return;
        }
        String interactionId = string(interaction, "id");
        String interactionToken = string(interaction, "token");
        if (interactionId.isBlank() || interactionToken.isBlank()) {
            return;
        }
        if (!Objects.equals(string(interaction, "guild_id"), config.guildId())) {
            return;
        }

        String channelId = string(interaction, "channel_id");
        if (!Objects.equals(channelId, config.channelId())) {
            respondInteractionMessage(interactionId, interactionToken,
                    buildPanelEmbed("Wrong Channel", "Use the configured channel: <#" + config.channelId() + ">."),
                    new JsonArray());
            return;
        }

        int type = intValue(interaction, "type", -1);
        JsonObject data = object(interaction, "data");
        if (type == 2) {
            handleSlashCommand(interactionId, interactionToken, channelId, data);
        } else if (type == 3) {
            JsonObject message = object(interaction, "message");
            String messageId = message == null ? "" : string(message, "id");
            handleComponentInteraction(interactionId, interactionToken, channelId, messageId, data);
        } else if (type == 5) {
            handleChatModal(interactionId, interactionToken, data);
        }
    }

    private void handleSlashCommand(String interactionId, String interactionToken, String channelId, JsonObject data) {
        if (data == null || !"aether".equals(string(data, "name"))) {
            return;
        }

        String task = readSlashOptionValue(data, "task");
        String message = readSlashOptionValue(data, "message");
        if (task.isBlank() || "panel".equals(task)) {
            sendControlPanel(channelId, "Ready.");
            respondInteractionMessage(interactionId, interactionToken,
                    buildPanelEmbed("Remote Control", "Control panel sent."), new JsonArray());
            return;
        }

        if ("status".equals(task)) {
            respondInteractionMessage(interactionId, interactionToken,
                    buildPanelEmbed("Remote Control", "Status requested. Screenshot will post below."), new JsonArray());
            scheduler.execute(this::runStatus);
            return;
        }

        String result = executeSlashTask(task, message);
        respondInteractionMessage(interactionId, interactionToken,
                buildPanelEmbed("Remote Control", result), new JsonArray());
    }

    private String executeSlashTask(String task, String message) {
        return switch (task) {
            case "start" -> sendMinecraftCommand("/aether farming", "Started Aether.");
            case "stop" -> sendMinecraftCommand("/aether stop", "Stopped Aether.");
            case "connect" -> connectToHypixel();
            case "disconnect" -> disconnect();
            case "panic" -> panic();
            case "chat" -> message == null || message.isBlank()
                    ? "Chat needs a message."
                    : sendChat(message.trim());
            case "warp" -> message == null || message.isBlank()
                    ? "Warp needs a place."
                    : sendMinecraftCommand("/warp " + message.trim(), "Warp command sent.");
            case "warp_hub" -> sendMinecraftCommand("/warp hub", "Warp command sent.");
            case "warp_garden" -> sendMinecraftCommand("/warp garden", "Warp command sent.");
            case "warp_desk" -> sendMinecraftCommand("/warp desk", "Warp command sent.");
            case "warp_island" -> sendMinecraftCommand("/warp island", "Warp command sent.");
            case "warp_elizabeth" -> sendMinecraftCommand("/warp elizabeth", "Warp command sent.");
            case "warp_forge" -> sendMinecraftCommand("/warpforge", "Warp command sent.");
            case "play_skyblock" -> sendMinecraftCommand("/play skyblock", "SkyBlock command sent.");
            case "lobby" -> sendMinecraftCommand("/lobby", "Lobby command sent.");
            default -> "Unsupported slash task.";
        };
    }

    private void handleComponentInteraction(String interactionId, String interactionToken, String channelId, String messageId, JsonObject data) {
        String customId = data == null ? "" : string(data, "custom_id");
        if ("aether:panel:refresh".equals(customId) || "aether:command:back".equals(customId)) {
            updateInteractionMessage(interactionId, interactionToken, buildControlPanelEmbed("Ready."), buildControlPanelComponents());
            return;
        }
        if ("aether:command:chat".equals(customId)) {
            openChatModal(interactionId, interactionToken);
            return;
        }
        if ("aether:command:warps".equals(customId)) {
            updateInteractionMessage(interactionId, interactionToken, buildControlPanelEmbed("Choose a warp."), buildWarpComponents());
            return;
        }
        if ("aether:warp".equals(customId)) {
            String value = readFirstSelectedValue(data);
            String result = executePanelCommand("warp_" + value);
            updateInteractionMessage(interactionId, interactionToken, buildControlPanelEmbed(result), buildControlPanelComponents());
            return;
        }
        if (!customId.startsWith("aether:command:")) {
            respondInteractionMessage(interactionId, interactionToken,
                    buildPanelEmbed("Remote Control", "That panel button is no longer valid."), new JsonArray());
            return;
        }

        String command = customId.substring("aether:command:".length());
        if ("status".equals(command)) {
            deferInteractionUpdate(interactionId, interactionToken);
            scheduler.execute(() -> {
                runStatus();
                editControlMessage(channelId, messageId,
                        buildControlPanelEmbed("Status requested. Screenshot posted below."),
                        buildControlPanelComponents());
            });
            return;
        }

        String result = executePanelCommand(command);
        updateInteractionMessage(interactionId, interactionToken, buildControlPanelEmbed(result), buildControlPanelComponents());
    }

    private String executePanelCommand(String command) {
        return switch (command) {
            case "start" -> sendMinecraftCommand("/aether farming", "Started Aether.");
            case "stop" -> sendMinecraftCommand("/aether stop", "Stopped Aether.");
            case "connect" -> connectToHypixel();
            case "disconnect" -> disconnect();
            case "panic" -> panic();
            case "warp_hub" -> sendMinecraftCommand("/warp hub", "Warp command sent.");
            case "warp_garden" -> sendMinecraftCommand("/warp garden", "Warp command sent.");
            case "warp_desk" -> sendMinecraftCommand("/warp desk", "Warp command sent.");
            case "warp_island" -> sendMinecraftCommand("/warp island", "Warp command sent.");
            case "warp_elizabeth" -> sendMinecraftCommand("/warp elizabeth", "Warp command sent.");
            case "warp_forge" -> sendMinecraftCommand("/warpforge", "Warp command sent.");
            case "warp_skyblock" -> sendMinecraftCommand("/play skyblock", "SkyBlock command sent.");
            case "warp_lobby" -> sendMinecraftCommand("/lobby", "Lobby command sent.");
            default -> "Unsupported panel action.";
        };
    }

    private void handleChatModal(String interactionId, String interactionToken, JsonObject data) {
        String message = readModalInputValue(data, "aether:chat-input");
        if (message.isBlank()) {
            respondInteractionMessage(interactionId, interactionToken,
                    buildPanelEmbed("Remote Control", "Chat message was blank."), new JsonArray());
            return;
        }

        String result = sendChat(message);
        respondInteractionMessage(interactionId, interactionToken,
                buildPanelEmbed("Remote Control", result), new JsonArray());
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

    private void sendControlPanel(String channelId, String statusLine) {
        JsonObject body = new JsonObject();
        JsonArray embeds = new JsonArray();
        embeds.add(buildControlPanelEmbed(statusLine));
        body.add("embeds", embeds);
        body.add("components", buildControlPanelComponents());

        http.sendAsync(request("/channels/" + channelId + "/messages")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build(), HttpResponse.BodyHandlers.ofString()).thenAccept(response -> {
                    if (response.statusCode() < 200 || response.statusCode() > 299) {
                        Aether.LOGGER.warn("Discord panel send failed: HTTP {} {}", response.statusCode(), response.body());
                        return;
                    }

                    JsonObject message = parseObject(response.body());
                    String messageId = string(message, "id");
                    if (!messageId.isBlank()) {
                        panelMessageByChannel.put(channelId, messageId);
                    }
                });
    }

    private JsonObject buildControlPanelEmbed(String statusLine) {
        String username = Minecraft.getInstance().getUser().getName();
        return buildPanelEmbed("Remote Control: " + username,
                (statusLine == null || statusLine.isBlank() ? "Ready." : statusLine)
                        + "\n\nThis panel controls the account tied to this Discord channel.");
    }

    private JsonObject buildPanelEmbed(String title, String description) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", title);
        embed.addProperty("description", description == null ? "" : description);
        embed.addProperty("color", 5814783);
        return embed;
    }

    private JsonArray buildControlPanelComponents() {
        JsonArray rows = new JsonArray();

        JsonObject mainRow = new JsonObject();
        mainRow.addProperty("type", 1);
        JsonArray mainButtons = new JsonArray();
        mainButtons.add(buildButton("Start", "aether:command:start", BUTTON_STYLE_GRAY, false));
        mainButtons.add(buildButton("Stop", "aether:command:stop", BUTTON_STYLE_GRAY, false));
        mainButtons.add(buildButton("Status", "aether:command:status", BUTTON_STYLE_GRAY, false));
        mainButtons.add(buildButton("Warps", "aether:command:warps", BUTTON_STYLE_GRAY, false));
        mainButtons.add(buildButton("Chat", "aether:command:chat", BUTTON_STYLE_GRAY, false));
        mainRow.add("components", mainButtons);
        rows.add(mainRow);

        JsonObject safetyRow = new JsonObject();
        safetyRow.addProperty("type", 1);
        JsonArray safetyButtons = new JsonArray();
        safetyButtons.add(buildButton("Refresh", "aether:panel:refresh", BUTTON_STYLE_GRAY, false));
        safetyButtons.add(buildButton("Connect", "aether:command:connect", BUTTON_STYLE_GRAY, false));
        safetyButtons.add(buildButton("Disconnect", "aether:command:disconnect", BUTTON_STYLE_GRAY, false));
        safetyButtons.add(buildButton("Panic", "aether:command:panic", BUTTON_STYLE_DANGER, false));
        safetyRow.add("components", safetyButtons);
        rows.add(safetyRow);

        return rows;
    }

    private JsonArray buildWarpComponents() {
        JsonArray rows = new JsonArray();

        JsonObject selectRow = new JsonObject();
        selectRow.addProperty("type", 1);
        JsonArray selectComponents = new JsonArray();
        JsonObject select = new JsonObject();
        select.addProperty("type", 3);
        select.addProperty("custom_id", "aether:warp");
        select.addProperty("placeholder", "Choose a warp");
        select.addProperty("min_values", 1);
        select.addProperty("max_values", 1);

        JsonArray options = new JsonArray();
        for (String destination : WARP_DESTINATIONS) {
            JsonObject option = new JsonObject();
            option.addProperty("label", switch (destination) {
                case "skyblock" -> "SkyBlock";
                default -> destination.substring(0, 1).toUpperCase(Locale.ROOT) + destination.substring(1);
            });
            option.addProperty("value", destination);
            options.add(option);
        }
        select.add("options", options);
        selectComponents.add(select);
        selectRow.add("components", selectComponents);
        rows.add(selectRow);

        JsonObject backRow = new JsonObject();
        backRow.addProperty("type", 1);
        JsonArray backButtons = new JsonArray();
        backButtons.add(buildButton("Back", "aether:command:back", BUTTON_STYLE_GRAY, false));
        backRow.add("components", backButtons);
        rows.add(backRow);

        return rows;
    }

    private JsonObject buildButton(String label, String customId, int style, boolean disabled) {
        JsonObject button = new JsonObject();
        button.addProperty("type", 2);
        button.addProperty("label", label);
        button.addProperty("style", style);
        button.addProperty("custom_id", customId);
        button.addProperty("disabled", disabled);
        return button;
    }

    private void updateInteractionMessage(String interactionId, String interactionToken, JsonObject embed, JsonArray components) {
        JsonObject body = new JsonObject();
        body.addProperty("type", 7);

        JsonObject data = new JsonObject();
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        data.add("embeds", embeds);
        data.add("components", components);
        body.add("data", data);

        postInteractionCallback(interactionId, interactionToken, body);
    }

    private void respondInteractionMessage(String interactionId, String interactionToken, JsonObject embed, JsonArray components) {
        JsonObject body = new JsonObject();
        body.addProperty("type", 4);

        JsonObject data = new JsonObject();
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        data.add("embeds", embeds);
        data.add("components", components);
        body.add("data", data);

        postInteractionCallback(interactionId, interactionToken, body);
    }

    private void deferInteractionUpdate(String interactionId, String interactionToken) {
        JsonObject body = new JsonObject();
        body.addProperty("type", 6);
        postInteractionCallback(interactionId, interactionToken, body);
    }

    private void openChatModal(String interactionId, String interactionToken) {
        JsonObject body = new JsonObject();
        body.addProperty("type", 9);

        JsonObject data = new JsonObject();
        data.addProperty("custom_id", "aether:chat");
        data.addProperty("title", "Send Minecraft Chat");

        JsonArray rows = new JsonArray();
        JsonObject row = new JsonObject();
        row.addProperty("type", 1);
        JsonArray components = new JsonArray();
        JsonObject input = new JsonObject();
        input.addProperty("type", 4);
        input.addProperty("custom_id", "aether:chat-input");
        input.addProperty("style", 2);
        input.addProperty("label", "Message");
        input.addProperty("placeholder", "Message or command to send");
        input.addProperty("required", true);
        input.addProperty("min_length", 1);
        input.addProperty("max_length", 500);
        components.add(input);
        row.add("components", components);
        rows.add(row);
        data.add("components", rows);
        body.add("data", data);

        postInteractionCallback(interactionId, interactionToken, body);
    }

    private void postInteractionCallback(String interactionId, String interactionToken, JsonObject body) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://discord.com/api/v10/interactions/" + interactionId + "/" + interactionToken + "/callback"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenAccept(response -> {
            if (response.statusCode() < 200 || response.statusCode() > 299) {
                Aether.LOGGER.warn("Discord interaction response failed: HTTP {} {}", response.statusCode(), response.body());
            }
        });
    }

    private void editControlMessage(String channelId, String messageId, JsonObject embed, JsonArray components) {
        if (channelId == null || channelId.isBlank() || messageId == null || messageId.isBlank()) {
            return;
        }

        JsonObject body = new JsonObject();
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        body.add("embeds", embeds);
        body.add("components", components);

        sendAsync(request("/channels/" + channelId + "/messages/" + messageId)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body.toString())));
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

    private void registerSlashCommands() {
        if (slashCommandsRegistered || applicationId.isBlank() || config.guildId().isBlank()) {
            return;
        }

        slashCommandsRegistered = true;
        JsonArray commands = new JsonArray();
        commands.add(buildAetherSlashCommand());

        sendAsync(request("/applications/" + applicationId + "/guilds/" + config.guildId() + "/commands")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(commands.toString())));
    }

    private JsonObject buildAetherSlashCommand() {
        JsonObject command = new JsonObject();
        command.addProperty("name", "aether");
        command.addProperty("description", "Remote control this Aether client");

        JsonArray options = new JsonArray();
        JsonObject task = new JsonObject();
        task.addProperty("type", 3);
        task.addProperty("name", "task");
        task.addProperty("description", "Task to run");
        task.addProperty("required", true);

        JsonArray choices = new JsonArray();
        addSlashChoice(choices, "panel", "panel");
        addSlashChoice(choices, "start", "start");
        addSlashChoice(choices, "stop", "stop");
        addSlashChoice(choices, "status", "status");
        addSlashChoice(choices, "connect", "connect");
        addSlashChoice(choices, "disconnect", "disconnect");
        addSlashChoice(choices, "panic", "panic");
        addSlashChoice(choices, "chat", "chat");
        addSlashChoice(choices, "warp", "warp");
        addSlashChoice(choices, "warp hub", "warp_hub");
        addSlashChoice(choices, "warp garden", "warp_garden");
        addSlashChoice(choices, "warp desk", "warp_desk");
        addSlashChoice(choices, "warp island", "warp_island");
        addSlashChoice(choices, "warp elizabeth", "warp_elizabeth");
        addSlashChoice(choices, "warp forge", "warp_forge");
        addSlashChoice(choices, "play skyblock", "play_skyblock");
        addSlashChoice(choices, "lobby", "lobby");
        task.add("choices", choices);
        options.add(task);

        JsonObject message = new JsonObject();
        message.addProperty("type", 3);
        message.addProperty("name", "message");
        message.addProperty("description", "Message for chat, or place for warp");
        message.addProperty("required", false);
        options.add(message);

        command.add("options", options);
        return command;
    }

    private void addSlashChoice(JsonArray choices, String name, String value) {
        JsonObject choice = new JsonObject();
        choice.addProperty("name", name);
        choice.addProperty("value", value);
        choices.add(choice);
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

    private static JsonObject object(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) {
            return null;
        }
        return parent.getAsJsonObject(key);
    }

    private static JsonArray array(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonArray()) {
            return new JsonArray();
        }
        return parent.getAsJsonArray(key);
    }

    private static String string(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString();
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(key).getAsInt();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String readSlashOptionValue(JsonObject data, String optionName) {
        for (JsonElement element : array(data, "options")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject option = element.getAsJsonObject();
            if (optionName.equals(string(option, "name")) && option.has("value")) {
                return option.get("value").getAsString();
            }
        }
        return "";
    }

    private static String readFirstSelectedValue(JsonObject data) {
        JsonArray values = array(data, "values");
        if (values.size() == 0) {
            return "";
        }
        return values.get(0).getAsString();
    }

    private static String readModalInputValue(JsonObject data, String targetCustomId) {
        for (JsonElement rowElement : array(data, "components")) {
            if (!rowElement.isJsonObject()) {
                continue;
            }
            for (JsonElement componentElement : array(rowElement.getAsJsonObject(), "components")) {
                if (!componentElement.isJsonObject()) {
                    continue;
                }
                JsonObject component = componentElement.getAsJsonObject();
                if (targetCustomId.equals(string(component, "custom_id"))) {
                    return string(component, "value").trim();
                }
            }
        }
        return "";
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
