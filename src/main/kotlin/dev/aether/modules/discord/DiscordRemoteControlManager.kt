package dev.aether.modules.discord

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.blaze3d.platform.NativeImage
import dev.aether.Aether
import dev.aether.config.AetherConfig
import dev.aether.util.ClientUtils.Companion.disconnectWithScreen
import dev.aether.util.ClientUtils.Companion.sendCommand
import dev.aether.util.ClientUtils.Companion.sendMessage
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.network.chat.Component
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Locale
import java.util.Objects
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DiscordRemoteControlManager private constructor(private val config: RemoteControlConfig) : WebSocket.Listener {
    private val http: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        val thread = Thread(task, "Aether Discord Remote Control")
        thread.isDaemon = true
        thread
    }
    private val buffer = StringBuilder()
    private val reconnecting = AtomicBoolean(false)
    private var socket: WebSocket? = null
    private var heartbeat: ScheduledFuture<*>? = null
    private var sequence = -1
    @Volatile private var stopping = false
    @Volatile private var applicationId = ""
    @Volatile private var slashRegistered = false

    private fun start() {
        scheduler.execute { connect() }
    }

    private fun stop() {
        stopping = true
        heartbeat?.cancel(false)
        socket?.sendClose(WebSocket.NORMAL_CLOSURE, "Stopping")
        scheduler.shutdownNow()
    }

    override fun onOpen(webSocket: WebSocket) {
        socket = webSocket
        webSocket.request(1)
    }

    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
        try {
            buffer.append(data)
            if (last) {
                handleGateway(buffer.toString())
                buffer.setLength(0)
            }
        } finally {
            webSocket.request(1)
        }
        return CompletableFuture.completedFuture(null)
    }

    override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*> {
        heartbeat?.cancel(false)
        if (!stopping) {
            reconnect("gateway closed")
        }
        return CompletableFuture.completedFuture(null)
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
        Aether.LOGGER.warn("Discord remote control gateway error", error)
        heartbeat?.cancel(false)
        if (!stopping) {
            reconnect("gateway error")
        }
    }

    private fun connect() {
        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(URI.create("wss://gateway.discord.gg/?v=10&encoding=json"), this)
                .exceptionally { error ->
                    Aether.LOGGER.warn("Discord remote control connection failed", error)
                    reconnect("connect failed")
                    null
                }
    }

    private fun handleGateway(payload: String) {
        val root = parseObject(payload)
        if (root == null) {
            return
        }
        if (root.has("s") && !root.get("s").isJsonNull) {
            sequence = root.get("s").asInt
        }
        val op = if (root.has("op")) root.get("op").asInt else -1
        when (op) {
            10 -> {
                identify()
                val interval = root.getAsJsonObject("d").get("heartbeat_interval").asLong
                heartbeat = scheduler.scheduleAtFixedRate(
                        { send("{\"op\":1,\"d\":" + (if (sequence < 0) "null" else sequence) + "}") },
                        interval,
                        interval,
                        TimeUnit.MILLISECONDS)
            }
            0 -> {
                val d = if (root.has("d") && root.get("d").isJsonObject) root.getAsJsonObject("d") else null
                when (string(root, "t")) {
                    "READY" -> onReady(d)
                    "MESSAGE_CREATE" -> handleMessage(d)
                    "INTERACTION_CREATE" -> handleInteraction(d)
                    else -> {
                    }
                }
            }
            1 -> send("{\"op\":1,\"d\":" + (if (sequence < 0) "null" else sequence) + "}")
            7, 9 -> reconnect("Discord requested reconnect")
            else -> {
            }
        }
    }

    private fun identify() {
        val body = JsonObject()
        body.addProperty("op", 2)
        val data = JsonObject()
        data.addProperty("token", config.botToken())
        data.addProperty("intents", 1 or 512 or 32768)
        val properties = JsonObject()
        properties.addProperty("os", System.getProperty("os.name", "unknown"))
        properties.addProperty("browser", "aether")
        properties.addProperty("device", "aether")
        data.add("properties", properties)
        body.add("d", data)
        send(body.toString())
    }

    private fun handleMessage(message: JsonObject?) {
        if (message == null) {
            return
        }
        if (!Objects.equals(string(message, "guild_id"), config.guildId())) {
            return
        }
        if (!Objects.equals(string(message, "channel_id"), config.channelId())) {
            return
        }
        val author = if (message.has("author") && message.get("author").isJsonObject) message.getAsJsonObject("author") else null
        if (author != null && author.has("bot") && author.get("bot").asBoolean) {
            return
        }
        val content = string(message, "content").trim()
        if (content.equals("!status", ignoreCase = true)) {
            scheduler.execute { runStatus() }
            return
        }
        val prefix = config.prefix()
        if (!content.regionMatches(0, prefix, 0, prefix.length, true)) {
            return
        }
        val args = content.substring(prefix.length).trim()
        scheduler.execute { runCommand(if (args.isBlank()) "help" else args) }
    }

    private fun runCommand(args: String) {
        val command = args.substring(0, firstSpaceOrEnd(args)).lowercase(Locale.ROOT)
        val rest = if (args.length > command.length) args.substring(command.length).trim() else ""
        val reply: String? = when (command) {
            "panel" -> {
                postPanel()
                null
            }
            "start" -> sendMinecraftCommand("/aether farming", "Started Aether.")
            "stop" -> sendMinecraftCommand("/aether stop", "Stopped Aether.")
            "status" -> {
                runStatus()
                null
            }
            "connect" -> connectToHypixel()
            "disconnect" -> disconnect()
            "panic" -> panic()
            "chat" -> {
                if (rest.isBlank()) {
                    postChatButton()
                    null
                } else {
                    sendChat(rest)
                }
            }
            "warp" -> if (rest.isBlank()) "Usage: `" + config.prefix() + " warp <place>`" else sendMinecraftCommand("/warp " + rest, "Warp command sent.")
            else -> "Commands: `" + config.prefix() + " panel`, `start`, `stop`, `status`, `connect`, `disconnect`, `panic`, `chat <text>`, `warp <place>`"
        }
        if (reply != null && !reply.isBlank()) {
            postText(reply)
        }
    }

    private fun runStatus() {
        sendMinecraftCommand("/aether status", "Status requested.")
        sleep(750L)
        val username = Minecraft.getInstance().user.name
        postScreenshot(config.channelId(), "", "Status for `" + username + "`")
    }

    private fun sendMinecraftCommand(command: String, feedback: String): String {
        Minecraft.getInstance()
        sendCommand(command)
        sendMessage("\u00A7a[Remote Control] " + feedback, false)
        return "`" + command + "` sent."
    }

    private fun sendChat(message: String): String {
        Minecraft.getInstance()
        sendCommand(message)
        sendMessage("\u00A7a[Remote Control] Sent " + message, false)
        return "`" + message + "` sent."
    }

    private fun connectToHypixel(): String {
        val client = Minecraft.getInstance()
        client.execute {
            val server = ServerData("Hypixel", "mc.hypixel.net", ServerData.Type.OTHER)
            ConnectScreen.startConnecting(
                    TitleScreen(),
                    client,
                    ServerAddress.parseString("mc.hypixel.net"),
                    server,
                    false,
                    null)
        }
        return "Connecting to `mc.hypixel.net`."
    }

    private fun disconnect(): String {
        disconnectWithScreen(
                TitleScreen(),
                Component.literal("Remote control disconnect requested"))
        return "Disconnect requested."
    }

    private fun panic(): String {
        CompletableFuture.delayedExecutor(250, TimeUnit.MILLISECONDS)
                .execute { Runtime.getRuntime().halt(1) }
        return "Panic crash requested."
    }

    private fun postText(text: String) {
        postEmbed("Remote Control", text, 5814783)
    }

    private fun postEmbed(title: String, description: String, color: Int) {
        val embed = JsonObject()
        embed.addProperty("title", title)
        embed.addProperty("description", description)
        embed.addProperty("color", color)
        val embeds = JsonArray()
        embeds.add(embed)
        val body = JsonObject()
        body.add("embeds", embeds)
        sendAsync(request("/channels/" + config.channelId() + "/messages")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())))
    }

    private fun postScreenshot(channelId: String, content: String, title: String?) {
        val image: ByteArray
        try {
            image = screenshot()
        } catch (e: IOException) {
            Aether.LOGGER.warn("Could not capture Discord remote control screenshot", e)
            postText(title + "\nCould not capture screenshot.")
            return
        }
        val boundary = "Aether" + System.currentTimeMillis()
        val payload = JsonObject()
        if (content != null && !content.isBlank()) {
            payload.addProperty("content", content)
        }
        val embedImage = JsonObject()
        embedImage.addProperty("url", "attachment://status.png")
        val embed = JsonObject()
        embed.addProperty("title", "Status Update")
        embed.addProperty("description", title ?: "Remote control status update.")
        embed.addProperty("color", 5814783)
        embed.add("image", embedImage)
        val embeds = JsonArray()
        embeds.add(embed)
        payload.add("embeds", embeds)
        sendAsync(request("/channels/" + channelId + "/messages")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(multipartImage(boundary, payload.toString(), image, "status.png")))
    }

    @Throws(IOException::class)
    private fun screenshot(): ByteArray {
        val future = CompletableFuture<ByteArray>()
        val client = Minecraft.getInstance()
        client.execute {
            val tempRef = arrayOfNulls<Path>(1)
            Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget()) { image ->
                try {
                    image.use { captured ->
                        tempRef[0] = Files.createTempFile("aether-status-", ".png")
                        captured.writeToFile(tempRef[0]!!)
                        future.complete(Files.readAllBytes(tempRef[0]!!))
                    }
                } catch (e: IOException) {
                    future.completeExceptionally(e)
                } finally {
                    if (tempRef[0] != null) {
                        try {
                            Files.deleteIfExists(tempRef[0]!!)
                        } catch (_: IOException) {
                        }
                    }
                }
            }
        }
        try {
            return future.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            throw IOException("Timed out while capturing screenshot", e)
        }
    }

    private fun request(path: String): HttpRequest.Builder {
        return HttpRequest.newBuilder(URI.create("https://discord.com/api/v10" + path))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bot " + config.botToken())
    }

    private fun sendAsync(builder: HttpRequest.Builder) {
        http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).thenAccept { response ->
            if (response.statusCode() < 200 || response.statusCode() > 299) {
                Aether.LOGGER.warn("Discord remote control request failed: HTTP {} {}", response.statusCode(), response.body())
            }
        }
    }

    private fun send(text: String) {
        val current = socket
        if (current != null) {
            current.sendText(text, true)
        }
    }

    private fun reconnect(reason: String) {
        if (!reconnecting.compareAndSet(false, true)) {
            return
        }
        Aether.LOGGER.info("Reconnecting Discord remote control: {}", reason)
        scheduler.schedule({
            reconnecting.set(false)
            connect()
        }, 5, TimeUnit.SECONDS)
    }

    private fun multipartImage(boundary: String, payload: String, image: ByteArray, filename: String): HttpRequest.BodyPublisher {
        val newline = "\r\n"
        val start = ("--" + boundary + newline
                + "Content-Disposition: form-data; name=\"payload_json\"" + newline
                + newline
                + payload + newline
                + "--" + boundary + newline
                + "Content-Disposition: form-data; name=\"files[0]\"; filename=\"" + filename + "\"" + newline
                + "Content-Type: image/png" + newline
                + newline).toByteArray(StandardCharsets.UTF_8)
        val end = (newline + "--" + boundary + "--" + newline).toByteArray(StandardCharsets.UTF_8)
        return HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofByteArray(start),
                HttpRequest.BodyPublishers.ofByteArray(image),
                HttpRequest.BodyPublishers.ofByteArray(end))
    }

    private fun onReady(data: JsonObject?) {
        if (data == null) {
            return
        }
        var id = ""
        if (data.has("application") && data.get("application").isJsonObject) {
            id = string(data.getAsJsonObject("application"), "id")
        }
        if (id.isBlank() && data.has("user") && data.get("user").isJsonObject) {
            id = string(data.getAsJsonObject("user"), "id")
        }
        applicationId = id
        registerSlashCommands()
    }

    private fun registerSlashCommands() {
        if (slashRegistered || applicationId.isBlank() || config.guildId().isBlank()) {
            return
        }
        slashRegistered = true
        val commands = JsonArray()
        commands.add(buildSlashCommand())
        sendAsync(request("/applications/" + applicationId + "/guilds/" + config.guildId() + "/commands")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(commands.toString(), StandardCharsets.UTF_8)))
    }

    private fun buildSlashCommand(): JsonObject {
        val command = JsonObject()
        command.addProperty("name", "aether")
        command.addProperty("description", "Remote control your Aether client")
        val options = JsonArray()
        options.add(subcommand("panel", "Open the control panel"))
        options.add(subcommand("start", "Start farming"))
        options.add(subcommand("stop", "Stop the macro"))
        options.add(subcommand("status", "Post a status screenshot"))
        options.add(subcommand("connect", "Connect to Hypixel"))
        options.add(subcommand("disconnect", "Disconnect to the title screen"))
        options.add(subcommand("panic", "Force-close the game"))
        options.add(subcommand("chat", "Open a box to send a chat message or command"))
        val warp = subcommand("warp", "Warp somewhere")
        warp.add("options", singleOption(option(3, "place", "Destination", true)))
        options.add(warp)
        val setping = subcommand("setping", "Set who gets @'d on failsafe in this channel")
        setping.add("options", singleOption(option(6, "user", "Who to @ on failsafe", true)))
        options.add(setping)
        options.add(subcommand("clearping", "Reset failsafe pings to @everyone"))
        command.add("options", options)
        return command
    }

    private fun handleInteraction(interaction: JsonObject?) {
        if (interaction == null) {
            return
        }
        if (!Objects.equals(string(interaction, "guild_id"), config.guildId())) {
            return
        }
        val type = if (interaction.has("type")) interaction.get("type").asInt else -1
        val id = string(interaction, "id")
        val token = string(interaction, "token")
        val channelId = string(interaction, "channel_id")
        if (id.isBlank() || token.isBlank()) {
            return
        }
        if (!Objects.equals(channelId, config.channelId())) {
            return
        }
        val data = if (interaction.has("data") && interaction.get("data").isJsonObject) interaction.getAsJsonObject("data") else null
        when (type) {
            2 -> scheduler.execute { handleSlashCommand(id, token, channelId, data) }
            5 -> scheduler.execute { handleChatModal(id, token, data) }
            3 -> {
                if (data != null) {
                    val message = if (interaction.has("message") && interaction.get("message").isJsonObject) interaction.getAsJsonObject("message") else null
                    val messageId = if (message != null) string(message, "id") else ""
                    scheduler.execute { handleComponent(id, token, channelId, messageId, string(data, "custom_id"), data) }
                }
            }
            else -> {
            }
        }
    }

    private fun handleSlashCommand(id: String, token: String, channelId: String, data: JsonObject?) {
        val sub = firstSubcommand(data)
        val task = if (sub == null) "panel" else string(sub, "name")
        if ("panel" == task) {
            respondMessage(id, token, buildPanelEmbed("Choose an action."), buildPanelButtons(), false)
            return
        }
        if ("status" == task) {
            respondMessage(id, token, buildPanelEmbed("Status requested."), null, true)
            runStatus()
            return
        }
        if ("chat" == task) {
            openChatModal(id, token)
            return
        }
        val reply = when (task) {
            "setping" -> {
                val userId = optionValue(sub, "user")
                if (userId.isBlank()) {
                    "Pick a `user` to receive failsafe pings in this channel."
                } else {
                    setPingTarget(channelId, userId)
                    "Saved. Failsafe alerts in this channel will now ping that user."
                }
            }
            "clearping" -> {
                setPingTarget(channelId, "")
                "Cleared. Failsafe alerts in this channel will ping @everyone."
            }
            "start" -> sendMinecraftCommand("/aether farming", "Started Aether.")
            "stop" -> sendMinecraftCommand("/aether stop", "Stopped Aether.")
            "connect" -> connectToHypixel()
            "disconnect" -> disconnect()
            "panic" -> panic()
            "warp" -> {
                val place = optionValue(sub, "place")
                if (place.isBlank()) "Provide a `place` for the warp task." else sendMinecraftCommand("/warp " + place, "Warp command sent.")
            }
            else -> "Unknown task."
        }
        respondMessage(id, token, buildPanelEmbed(reply), null, true)
    }

    private fun handleComponent(id: String, token: String, channelId: String, messageId: String, customId: String, data: JsonObject) {
        when (customId) {
            "aether:start" -> updatePanel(id, token, sendMinecraftCommand("/aether farming", "Started Aether."))
            "aether:stop" -> updatePanel(id, token, sendMinecraftCommand("/aether stop", "Stopped Aether."))
            "aether:panic" -> updatePanel(id, token, panic())
            "aether:warps" -> updatePanelComponents(id, token, "Choose a warp.", buildWarpComponents())
            "aether:back" -> updatePanelComponents(id, token, "Choose an action.", buildPanelButtons())
            "aether:chat" -> openChatModal(id, token)
            "aether:status" -> {
                deferUpdate(id, token)
                editPanelWithScreenshot(channelId, messageId, "Status update.")
            }
            "aether:connect" -> {
                val reply = connectToHypixel()
                deferUpdate(id, token)
                scheduler.schedule({ editPanelWithScreenshot(channelId, messageId, reply) }, 7L, TimeUnit.SECONDS)
            }
            "aether:disconnect" -> {
                val reply = disconnect()
                deferUpdate(id, token)
                scheduler.schedule({ editPanelWithScreenshot(channelId, messageId, reply) }, 2L, TimeUnit.SECONDS)
            }
            "aether:warp" -> {
                val place = firstSelected(data)
                val reply = if (place.isBlank()) "No warp selected." else sendMinecraftCommand(place, "Warp command sent.")
                deferUpdate(id, token)
                scheduler.schedule({ editPanelWithScreenshot(channelId, messageId, reply) }, 7L, TimeUnit.SECONDS)
            }
        }
    }

    private fun handleChatModal(id: String, token: String, data: JsonObject?) {
        val message = modalValue(data, "aether:chat-message").trim()
        val reply = if (message.isBlank()) "Chat message cannot be blank." else sendChat(message)
        respondMessage(id, token, buildPanelEmbed(reply), null, true)
    }

    private fun postChatButton() {
        val buttons = JsonArray()
        buttons.add(button("Chat", "aether:chat", 1))
        val embeds = JsonArray()
        embeds.add(buildPanelEmbed("Press the button to type a message."))
        val components = JsonArray()
        components.add(actionRow(buttons))
        val payload = JsonObject()
        payload.add("embeds", embeds)
        payload.add("components", components)
        sendAsync(request("/channels/" + config.channelId() + "/messages")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8)))
    }

    private fun postPanel() {
        val embeds = JsonArray()
        embeds.add(buildPanelEmbed("Choose an action."))
        val payload = JsonObject()
        payload.add("embeds", embeds)
        payload.add("components", buildPanelButtons())
        sendAsync(request("/channels/" + config.channelId() + "/messages")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8)))
    }

    private fun buildPanelEmbed(statusLine: String?): JsonObject {
        val embed = JsonObject()
        embed.addProperty("title", "Aether Remote Control")
        embed.addProperty("description", if (statusLine == null || statusLine.isBlank()) "Choose an action." else statusLine)
        embed.addProperty("color", 5814783)
        return embed
    }

    private fun buildPanelButtons(): JsonArray {
        val topRow = JsonArray()
        topRow.add(button("Start", "aether:start", 3))
        topRow.add(button("Stop", "aether:stop", 4))
        topRow.add(button("Status", "aether:status", 1))
        topRow.add(button("Warps", "aether:warps", 2))
        topRow.add(button("Chat", "aether:chat", 2))
        val bottomRow = JsonArray()
        bottomRow.add(button("Connect", "aether:connect", 2))
        bottomRow.add(button("Disconnect", "aether:disconnect", 2))
        bottomRow.add(button("Panic", "aether:panic", 4))
        val rows = JsonArray()
        rows.add(actionRow(topRow))
        rows.add(actionRow(bottomRow))
        return rows
    }

    private fun buildWarpComponents(): JsonArray {
        val select = JsonObject()
        select.addProperty("type", 3)
        select.addProperty("custom_id", "aether:warp")
        select.addProperty("placeholder", "Choose a warp")
        select.addProperty("min_values", 1)
        select.addProperty("max_values", 1)
        val options = JsonArray()
        for (warp in WARPS) {
            val option = JsonObject()
            option.addProperty("label", warp[0])
            option.addProperty("value", warp[1])
            options.add(option)
        }
        select.add("options", options)
        val selectComponents = JsonArray()
        selectComponents.add(select)
        val backComponents = JsonArray()
        backComponents.add(button("Back", "aether:back", 2))
        val rows = JsonArray()
        rows.add(actionRow(selectComponents))
        rows.add(actionRow(backComponents))
        return rows
    }

    private fun openChatModal(id: String, token: String) {
        val input = JsonObject()
        input.addProperty("type", 4)
        input.addProperty("custom_id", "aether:chat-message")
        input.addProperty("label", "Message")
        input.addProperty("style", 1)
        input.addProperty("min_length", 1)
        input.addProperty("max_length", 256)
        input.addProperty("required", true)
        val inputRow = JsonArray()
        inputRow.add(input)
        val rows = JsonArray()
        rows.add(actionRow(inputRow))
        val modal = JsonObject()
        modal.addProperty("custom_id", "aether:chat-modal")
        modal.addProperty("title", "Send chat / command")
        modal.add("components", rows)
        val body = JsonObject()
        body.addProperty("type", 9)
        body.add("data", modal)
        postCallback(id, token, body)
    }

    private fun respondMessage(id: String, token: String, embed: JsonObject, components: JsonArray?, ephemeral: Boolean) {
        val data = JsonObject()
        val embeds = JsonArray()
        embeds.add(embed)
        data.add("embeds", embeds)
        if (components != null) {
            data.add("components", components)
        }
        if (ephemeral) {
            data.addProperty("flags", 64)
        }
        val body = JsonObject()
        body.addProperty("type", 4)
        body.add("data", data)
        postCallback(id, token, body)
    }

    private fun updatePanel(id: String, token: String, statusLine: String) {
        updatePanelComponents(id, token, statusLine, buildPanelButtons())
    }

    private fun updatePanelComponents(id: String, token: String, statusLine: String, components: JsonArray) {
        val embeds = JsonArray()
        embeds.add(buildPanelEmbed(statusLine))
        val data = JsonObject()
        data.add("embeds", embeds)
        data.add("components", components)
        val body = JsonObject()
        body.addProperty("type", 7)
        body.add("data", data)
        postCallback(id, token, body)
    }

    private fun deferUpdate(id: String, token: String) {
        val body = JsonObject()
        body.addProperty("type", 6)
        postCallback(id, token, body)
    }

    private fun postCallback(id: String, token: String, body: JsonObject) {
        sendAsync(HttpRequest.newBuilder(URI.create("https://discord.com/api/v10/interactions/" + id + "/" + token + "/callback"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)))
    }

    private fun editPanelWithScreenshot(channelId: String, messageId: String, statusLine: String) {
        if (channelId.isBlank() || messageId.isBlank()) {
            return
        }
        val payload = JsonObject()
        val embeds = JsonArray()
        val embed = buildPanelEmbed(statusLine)
        val components = buildPanelButtons()
        val image: ByteArray
        try {
            image = screenshot()
        } catch (e: IOException) {
            embeds.add(embed)
            payload.add("embeds", embeds)
            payload.add("components", components)
            sendAsync(request("/channels/" + channelId + "/messages/" + messageId)
                    .header("Content-Type", "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8)))
            return
        }
        val img = JsonObject()
        img.addProperty("url", "attachment://control.png")
        embed.add("image", img)
        embeds.add(embed)
        payload.add("embeds", embeds)
        payload.add("components", components)
        val boundary = "Aether" + System.currentTimeMillis()
        sendAsync(request("/channels/" + channelId + "/messages/" + messageId)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .method("PATCH", multipartImage(boundary, payload.toString(), image, "control.png")))
    }

    private fun postFailsafeAlert(reason: String, actionDone: String) {
        val description = "Aether detected a failsafe.\n**Reason:** " + reason +
                (if (actionDone.isBlank()) "" else "\n**Action:** " + actionDone)
        val target = pingTargetFor(config.channelId())
        val payload = JsonObject()
        val allowedMentions = JsonObject()
        if (target.isBlank()) {
            payload.addProperty("content", "@everyone\n-# *Tip: run /aether setping and pick a user to send failsafe pings to just that person.*")
            val parse = JsonArray()
            parse.add("everyone")
            allowedMentions.add("parse", parse)
        } else {
            payload.addProperty("content", "<@" + target + ">")
            val users = JsonArray()
            users.add(target)
            allowedMentions.add("users", users)
        }
        payload.add("allowed_mentions", allowedMentions)
        val embed = JsonObject()
        embed.addProperty("title", "Failsafe Alert")
        embed.addProperty("description", description)
        embed.addProperty("color", 15158332)
        val image: ByteArray
        try {
            image = screenshot()
        } catch (e: IOException) {
            val embeds = JsonArray()
            embeds.add(embed)
            payload.add("embeds", embeds)
            sendAsync(request("/channels/" + config.channelId() + "/messages")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8)))
            return
        }
        val img = JsonObject()
        img.addProperty("url", "attachment://failsafe.png")
        embed.add("image", img)
        val embeds = JsonArray()
        embeds.add(embed)
        payload.add("embeds", embeds)
        val boundary = "Aether" + System.currentTimeMillis()
        sendAsync(request("/channels/" + config.channelId() + "/messages")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(multipartImage(boundary, payload.toString(), image, "failsafe.png")))
    }

    private fun pingTargetFor(channelId: String?): String {
        if (channelId == null || channelId.isBlank()) {
            return ""
        }
        val targets = loadPingTargets()
        return if (targets.has(channelId) && !targets.get(channelId).isJsonNull) targets.get(channelId).asString else ""
    }

    private fun setPingTarget(channelId: String?, userId: String?) {
        if (channelId == null || channelId.isBlank()) {
            return
        }
        val targets = loadPingTargets()
        if (userId == null || userId.isBlank()) {
            targets.remove(channelId)
        } else {
            targets.addProperty(channelId, userId)
        }
        AetherConfig.REMOTE_CONTROL_PING_TARGETS.set(targets.toString())
        AetherConfig.save()
    }

    companion object {
        private val LOCK = Any()
        private var active: DiscordRemoteControlManager? = null
        private val WARPS = arrayOf(
                arrayOf("Hub", "/warp hub"),
                arrayOf("Garden", "/warp garden"),
                arrayOf("Desk", "/warp desk"),
                arrayOf("Island", "/warp island"),
                arrayOf("Forge", "/warpforge"),
                arrayOf("Skyblock", "/play skyblock"),
                arrayOf("Lobby", "/lobby"))

        @JvmStatic fun restartFromConfig() {
            synchronized(LOCK) {
                val previous = active
                if (previous != null) {
                    previous.stop()
                    active = null
                }
                val config = RemoteControlConfig.load()
                if (!config.configured()) {
                    return
                }
                val manager = DiscordRemoteControlManager(config)
                active = manager
                manager.start()
            }
        }

        @JvmStatic fun shutdown() {
            synchronized(LOCK) {
                val manager = active
                if (manager != null) {
                    manager.stop()
                    active = null
                }
            }
        }

        @JvmStatic fun sendFailsafeAlert(reason: String, actionDone: String) {
            val current: DiscordRemoteControlManager?
            synchronized(LOCK) {
                current = active
            }
            if (current != null && !current.config.channelId().isBlank()) {
                current.scheduler.execute { current.postFailsafeAlert(reason, actionDone) }
            }
        }

        private fun parseObject(payload: String): JsonObject? {
            try {
                return JsonParser.parseString(payload).asJsonObject
            } catch (_: Exception) {
                return null
            }
        }

        private fun string(obj: JsonObject?, key: String): String {
            if (obj == null || !obj.has(key) || obj.get(key).isJsonNull) {
                return ""
            }
            return obj.get(key).asString
        }

        private fun firstSpaceOrEnd(value: String): Int {
            val index = value.indexOf(' ')
            return if (index < 0) value.length else index
        }

        private fun sleep(millis: Long) {
            try {
                Thread.sleep(millis)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        private fun subcommand(name: String, description: String): JsonObject {
            val sub = JsonObject()
            sub.addProperty("type", 1)
            sub.addProperty("name", name)
            sub.addProperty("description", description)
            return sub
        }

        private fun option(type: Int, name: String, description: String, required: Boolean): JsonObject {
            val option = JsonObject()
            option.addProperty("type", type)
            option.addProperty("name", name)
            option.addProperty("description", description)
            option.addProperty("required", required)
            return option
        }

        private fun singleOption(option: JsonObject): JsonArray {
            val array = JsonArray()
            array.add(option)
            return array
        }

        private fun firstSubcommand(data: JsonObject?): JsonObject? {
            if (data == null || !data.has("options") || !data.get("options").isJsonArray) {
                return null
            }
            val options = data.getAsJsonArray("options")
            if (options.size() == 0 || !options[0].isJsonObject) {
                return null
            }
            return options[0].asJsonObject
        }

        private fun loadPingTargets(): JsonObject {
            val parsed = parseObject(AetherConfig.REMOTE_CONTROL_PING_TARGETS.get())
            return parsed ?: JsonObject()
        }

        private fun actionRow(components: JsonArray): JsonObject {
            val row = JsonObject()
            row.addProperty("type", 1)
            row.add("components", components)
            return row
        }

        private fun button(label: String, customId: String, style: Int): JsonObject {
            val button = JsonObject()
            button.addProperty("type", 2)
            button.addProperty("label", label)
            button.addProperty("style", style)
            button.addProperty("custom_id", customId)
            return button
        }

        private fun optionValue(data: JsonObject?, name: String): String {
            if (data == null || !data.has("options") || !data.get("options").isJsonArray) {
                return ""
            }
            for (element in data.getAsJsonArray("options")) {
                val option = element.asJsonObject
                if (name == string(option, "name") && option.has("value")) {
                    return option.get("value").asString
                }
            }
            return ""
        }

        private fun firstSelected(data: JsonObject?): String {
            if (data == null || !data.has("values") || !data.get("values").isJsonArray) {
                return ""
            }
            val values = data.getAsJsonArray("values")
            return if (values.size() == 0) "" else values[0].asString
        }

        private fun modalValue(data: JsonObject?, customId: String): String {
            if (data == null || !data.has("components") || !data.get("components").isJsonArray) {
                return ""
            }
            for (rowElement in data.getAsJsonArray("components")) {
                val row = rowElement.asJsonObject
                if (!row.has("components") || !row.get("components").isJsonArray) {
                    continue
                }
                for (componentElement in row.getAsJsonArray("components")) {
                    val component = componentElement.asJsonObject
                    if (customId == string(component, "custom_id")) {
                        return string(component, "value")
                    }
                }
            }
            return ""
        }
    }
}

private class RemoteControlConfig(
        private val enabled: Boolean,
        private val botToken: String,
        private val guildId: String,
        private val channelId: String,
        private val prefix: String) {
    fun configured(): Boolean {
        return enabled
                && !botToken.isBlank()
                && !guildId.isBlank()
                && !channelId.isBlank()
                && !prefix.isBlank()
    }

    fun botToken(): String {
        return botToken
    }

    fun guildId(): String {
        return guildId
    }

    fun channelId(): String {
        return channelId
    }

    fun prefix(): String {
        return prefix
    }

    companion object {
        fun load(): RemoteControlConfig {
            var prefix = AetherConfig.REMOTE_CONTROL_COMMAND_PREFIX.get()
            if (prefix == null || prefix.isBlank()) {
                prefix = "!aether"
            }
            return RemoteControlConfig(
                    AetherConfig.REMOTE_CONTROL_ENABLED.get(),
                    safe(AetherConfig.REMOTE_CONTROL_BOT_TOKEN.get()),
                    safe(AetherConfig.REMOTE_CONTROL_GUILD_ID.get()),
                    safe(AetherConfig.REMOTE_CONTROL_CHANNEL_ID.get()),
                    prefix.trim())
        }

        private fun safe(value: String?): String {
            return if (value == null) "" else value.trim()
        }
    }
}
