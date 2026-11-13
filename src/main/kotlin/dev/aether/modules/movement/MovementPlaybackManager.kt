package dev.aether.modules.movement

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aether.mixin.AccessorInventory
import dev.aether.modules.failsafe.FailsafeManager
import dev.aether.modules.rotation.RotationManager
import dev.aether.macro.MacroStateManager
import dev.aether.util.AetherLang
import dev.aether.util.ClientUtils
import dev.aether.util.ProgrammaticAttackTracker
import dev.aether.util.ProgrammaticMovementTracker
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.Options
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemStack
import java.awt.Desktop
import java.io.IOException
import java.io.Reader
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayList
import java.util.Comparator
import java.util.Locale
import java.util.stream.Stream

class MovementPlaybackManager private constructor() {
    companion object {
        private val GSON: Gson = GsonBuilder().setPrettyPrinting().create()
        private val FILE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        private const val ROTATION_EPSILON = 0.01f
        private val MOVEMENT_DIR: Path = FabricLoader.getInstance().configDir.resolve("aether").resolve("movement")

        private var registered = false
        private var recording = false
        private var playing = false
        private var recordingTick = 0
        private var playbackTick = 0
        private var playbackIndex = 0
        private var playbackDurationTicks = 0
        private var recordingFile: Path? = null
        private var lastRecordedInput: InputSnapshot? = null
        private val recordingEvents: MutableList<MovementEvent> = ArrayList()
        private val playbackEvents: MutableList<MovementEvent> = ArrayList()

        @JvmStatic @Synchronized fun register() {
            if (registered) return
            registered = true
            ClientTickEvents.END_CLIENT_TICK.register(::onClientTick)
        }

        @JvmStatic @Synchronized fun startRecording() {
            val client = Minecraft.getInstance()
            if (!isClientReady(client)) {
                ClientUtils.sendMessage("\u00A7cJoin a world before recording movement.", false)
                return
            }
            if (playing) stopPlayback(client, true)
            if (recording) {
                ClientUtils.sendMessage("\u00A7eMovement recording is already running.", false)
                return
            }
            recording = true
            recordingTick = 0
            recordingFile = MOVEMENT_DIR.resolve("movement_" + FILE_TIME_FORMAT.format(LocalDateTime.now()) + ".json")
            lastRecordedInput = null
            recordingEvents.clear()
            ClientUtils.sendMessage("\u00A7aStarted movement recording.", false)
        }

        @JvmStatic @Synchronized fun stop() {
            val client = Minecraft.getInstance()
            if (recording) {
                stopRecording(client)
                return
            }
            if (playing) {
                stopPlayback(client, true)
                return
            }
            ClientUtils.sendMessage("\u00A7eNo movement recording or playback is running.", false)
        }

        @JvmStatic @Synchronized fun play(replayFile: String) {
            playFromDirectory(Minecraft.getInstance(), MOVEMENT_DIR, replayFile)
        }

        @JvmStatic @Synchronized fun playFromDirectory(client: Minecraft, replayDirectory: Path, replayFile: String?) {
            if (!isClientReady(client)) {
                ClientUtils.sendMessage("\u00A7cJoin a world before playing movement.", false)
                return
            }
            if (replayFile == null || replayFile.isBlank()) {
                ClientUtils.sendMessage("\u00A7eUsage: /aether movement play <replay_file>", false)
                return
            }
            if (recording) stopRecording(client)
            if (MacroStateManager.isMacroRunning()) MacroStateManager.stopMacro(client, "Movement playback started", false)
            if (playing) stopPlayback(client, true)
            try {
                val path = resolveReplayPath(replayDirectory, replayFile)
                val loadedEvents = loadEvents(path)
                playbackEvents.clear()
                playbackEvents.addAll(loadedEvents)
                playbackEvents.sortWith(Comparator.comparingInt { event -> event.tick })
                playbackTick = 0
                playbackIndex = 0
                playbackDurationTicks = loadDurationTicks(path, loadedEvents)
                playing = true
                ClientUtils.sendMessage("\u00A7aPlaying movement replay: " + path.fileName, false)
            } catch (ex: Exception) {
                ClientUtils.sendMessage("\u00A7cFailed to load movement replay: " + ex.message, false)
            }
        }

        @JvmStatic @Synchronized fun recordOutgoingChat(message: String?, command: Boolean) {
            if (!recording || message == null || message.isBlank()) return
            if (command && isMovementCommand(message)) return
            addRecordingActions(Math.max(1, recordingTick), listOf(MovementAction.chat(command, message)))
        }

        @JvmStatic fun isRecording(): Boolean = recording
        @JvmStatic fun isPlaying(): Boolean = playing
        @JvmStatic fun listReplayFiles(): List<String> = listReplayFiles(MOVEMENT_DIR)

        @JvmStatic fun listReplayFiles(replayDirectory: Path): List<String> {
            if (!Files.isDirectory(replayDirectory)) return emptyList()
            try {
                Files.list(replayDirectory).use { files ->
                    return files.filter { Files.isRegularFile(it) }
                        .map { it.fileName.toString() }
                        .filter { it.endsWith(".json") }
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .toList()
                }
            } catch (_: IOException) {
                return emptyList()
            }
        }

        @JvmStatic fun openMovementFolder() {
            try {
                Files.createDirectories(MOVEMENT_DIR)
                if (isWindows()) {
                    ProcessBuilder("explorer.exe", MOVEMENT_DIR.toAbsolutePath().toString()).start()
                } else if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(MOVEMENT_DIR.toFile())
                } else {
                    throw IOException("desktop open is not supported on this platform")
                }
                ClientUtils.sendMessage("\u00A7a" + String.format(AetherLang.localize("Opened movement folder: %s"), MOVEMENT_DIR.toAbsolutePath()), false)
            } catch (ex: IOException) {
                ClientUtils.sendMessage("\u00A7c" + String.format(AetherLang.localize("Failed to open movement folder: %s"), ex.message), false)
            }
        }

        @Synchronized private fun onClientTick(client: Minecraft?) {
            if (client == null) return
            if (recording) tickRecording(client)
            if (playing) tickPlayback(client)
        }

        private fun tickRecording(client: Minecraft) {
            if (!isClientReady(client)) { stopRecording(client); return }
            recordingTick++
            val snapshot = InputSnapshot.capture(client)
            val actions = snapshot.diff(lastRecordedInput)
            if (actions.isNotEmpty()) addRecordingActions(recordingTick, actions)
            lastRecordedInput = snapshot
        }

        private fun tickPlayback(client: Minecraft) {
            if (!isClientReady(client)) { stopPlayback(client, true); return }
            playbackTick++
            while (playbackIndex < playbackEvents.size && playbackEvents[playbackIndex].tick <= playbackTick) {
                val event = playbackEvents[playbackIndex++]
                for (action in event.actions) applyAction(client, action)
            }
            if (playbackIndex >= playbackEvents.size && playbackTick >= playbackDurationTicks) {
                stopPlayback(client, false)
                ClientUtils.sendMessage("\u00A7aMovement playback finished.", false)
            }
        }

        private fun stopRecording(client: Minecraft) {
            recording = false
            lastRecordedInput = null
            try {
                saveRecording()
                ClientUtils.sendMessage("\u00A7aSaved movement recording: " + recordingFile!!.fileName, false)
            } catch (ex: IOException) {
                ClientUtils.sendMessage("\u00A7cFailed to save movement recording: " + ex.message, false)
            }
        }

        private fun stopPlayback(client: Minecraft?, notify: Boolean) {
            playing = false
            playbackTick = 0
            playbackIndex = 0
            playbackDurationTicks = 0
            playbackEvents.clear()
            releasePlaybackInputs(client)
            if (notify) ClientUtils.sendMessage("\u00A7eMovement playback stopped.", false)
        }

        @Throws(IOException::class)
        private fun saveRecording() {
            Files.createDirectories(MOVEMENT_DIR)
            val root = JsonObject()
            root.addProperty("version", 1)
            root.addProperty("durationTicks", recordingTick)
            root.addProperty("encoding", "aether.event_stream")
            val events = JsonArray()
            for (event in recordingEvents) events.add(event.toJson())
            root.add("events", events)
            Files.newBufferedWriter(recordingFile!!, StandardCharsets.UTF_8).use { writer -> GSON.toJson(root, writer) }
        }

        @Throws(IOException::class)
        private fun loadEvents(path: Path): List<MovementEvent> {
            Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
                val parsed = JsonParser.parseReader(reader)
                if (!parsed.isJsonObject) throw IOException("root must be a JSON object")
                val array = parsed.asJsonObject.getAsJsonArray("events") ?: throw IOException("missing events array")
                val events = ArrayList<MovementEvent>()
                for (element in array) events.add(MovementEvent.fromJson(element.asJsonObject))
                return events
            }
        }

        @Throws(IOException::class)
        private fun loadDurationTicks(path: Path, events: List<MovementEvent>): Int {
            val maxEventTick = events.stream().mapToInt { it.tick }.max().orElse(0)
            Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
                val root = JsonParser.parseReader(reader).asJsonObject
                val duration = root.get("durationTicks")
                if (duration != null && duration.isJsonPrimitive) return Math.max(maxEventTick, duration.asInt)
            }
            return maxEventTick
        }

        private fun addRecordingActions(tick: Int, actions: List<MovementAction>) {
            if (recordingEvents.isNotEmpty()) {
                val last = recordingEvents[recordingEvents.size - 1]
                if (last.tick == tick) { last.actions.addAll(actions); return }
            }
            recordingEvents.add(MovementEvent(tick, ArrayList(actions)))
        }

        private fun applyAction(client: Minecraft, action: MovementAction) {
            when (action.type) {
                "key" -> setInput(client, action.key, action.down)
                "rotation" -> setRotation(client, action.yaw, action.pitch)
                "fly" -> setFlying(client, action.down)
                "chat" -> sendChatAction(client, action)
                "hotbar" -> selectHotbarItem(client, action.itemName, action.slot)
            }
        }

        private fun setInput(client: Minecraft, key: String?, down: Boolean) {
            val mapping = keyMapping(client.options, key) ?: return
            ClientUtils.setKeyMappingState(mapping, down)
            if (key == "attack") ProgrammaticAttackTracker.setHeld(mapping, down) else ProgrammaticMovementTracker.set(mapping, down)
        }

        private fun setRotation(client: Minecraft, yaw: Float, pitchValue: Float) {
            if (client.player == null) return
            if (RotationManager.isRotating()) RotationManager.cancelRotation()
            val pitch = Mth.clamp(pitchValue, -90.0f, 90.0f)
            RotationManager.rotateToYawPitch(client, yaw, pitch, 0L)
        }

        private fun setFlying(client: Minecraft, flying: Boolean) {
            val player = client.player ?: return
            if (!player.abilities.mayfly) return
            player.abilities.flying = flying
            player.onUpdateAbilities()
        }

        private fun sendChatAction(client: Minecraft, action: MovementAction) {
            val message = action.message
            if (message == null || message.isBlank()) return
            ClientUtils.sendCommand(if (action.command) "/$message" else message)
        }

        private fun selectHotbarItem(client: Minecraft?, itemName: String?, fallbackSlot: Int) {
            if (client == null || client.player == null) return
            var slot = findHotbarItemSlot(client, itemName)
            if (slot < 0 && normalizeItemName(itemName).isBlank() && fallbackSlot in 0..8) slot = fallbackSlot
            if (slot in 0..8) FailsafeManager.selectHotbarSlot(client, slot)
        }

        private fun findHotbarItemSlot(client: Minecraft?, itemName: String?): Int {
            val needle = normalizeItemName(itemName)
            if (needle.isBlank() || client == null) return -1
            val player = client.player ?: return -1
            val normalizedNeedle = needle.lowercase(Locale.ROOT)
            for (slot in 0 until 9) {
                val stack: ItemStack = player.inventory.getItem(slot)
                if (stack.isEmpty) continue
                val normalizedName = normalizeItemName(stack.hoverName.string).lowercase(Locale.ROOT)
                if (normalizedName.contains(normalizedNeedle)) return slot
            }
            return -1
        }

        private fun selectedHotbarItemName(client: Minecraft?): String {
            if (client == null) return ""
            val player = client.player ?: return ""
            val selected = (player.inventory as AccessorInventory).getSelected()
            if (selected < 0 || selected > 8) return ""
            val stack = player.inventory.getItem(selected)
            return if (stack.isEmpty) "" else normalizeItemName(stack.hoverName.string)
        }

        private fun normalizeItemName(itemName: String?): String {
            if (itemName == null) return ""
            return itemName.replace(Regex("\\u00A7[0-9A-FK-ORa-fk-or]"), "").trim()
        }

        private fun releasePlaybackInputs(client: Minecraft?) {
            if (client == null || client.options == null) return
            setInput(client, "forward", false)
            setInput(client, "back", false)
            setInput(client, "left", false)
            setInput(client, "right", false)
            setInput(client, "jump", false)
            setInput(client, "sneak", false)
            setInput(client, "sprint", false)
            setInput(client, "attack", false)
            setInput(client, "use", false)
        }

        private fun isClientReady(client: Minecraft?): Boolean = client != null && client.player != null && client.level != null && client.options != null
        private fun isMovementCommand(command: String): Boolean = command.lowercase(Locale.ROOT).trim().startsWith("aether movement ")

        @Throws(IOException::class)
        private fun resolveReplayPath(replayDirectory: Path, replayFile: String): Path {
            Files.createDirectories(replayDirectory)
            var file = replayFile.trim()
            if (!file.endsWith(".json")) file += ".json"
            val normalizedDirectory = replayDirectory.normalize()
            val resolved = replayDirectory.resolve(file).normalize()
            if (!resolved.startsWith(normalizedDirectory)) throw IOException("file must stay inside $replayDirectory")
            if (!Files.isRegularFile(resolved)) throw IOException("file not found: ${resolved.fileName}")
            return resolved
        }

        private fun isWindows(): Boolean = System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("win")

        private fun keyMapping(options: Options?, key: String?): KeyMapping? {
            if (options == null || key == null) return null
            return when (key) {
                "forward" -> options.keyUp
                "back" -> options.keyDown
                "left" -> options.keyLeft
                "right" -> options.keyRight
                "jump" -> options.keyJump
                "sneak" -> options.keyShift
                "sprint" -> options.keySprint
                "attack" -> options.keyAttack
                "use" -> options.keyUse
                else -> null
            }
        }
    }

    private class InputSnapshot(
        private val forward: Boolean,
        private val back: Boolean,
        private val left: Boolean,
        private val right: Boolean,
        private val jump: Boolean,
        private val sneak: Boolean,
        private val sprint: Boolean,
        private val attack: Boolean,
        private val use: Boolean,
        private val flying: Boolean,
        private val selectedHotbarSlot: Int,
        private val selectedHotbarItemName: String,
        private val yaw: Float,
        private val pitch: Float
    ) {
        fun diff(previous: InputSnapshot?): List<MovementAction> {
            val actions = ArrayList<MovementAction>()
            addKeyDiff(actions, "forward", forward, previous == null || previous.forward != forward)
            addKeyDiff(actions, "back", back, previous == null || previous.back != back)
            addKeyDiff(actions, "left", left, previous == null || previous.left != left)
            addKeyDiff(actions, "right", right, previous == null || previous.right != right)
            addKeyDiff(actions, "jump", jump, previous == null || previous.jump != jump)
            addKeyDiff(actions, "sneak", sneak, previous == null || previous.sneak != sneak)
            addKeyDiff(actions, "sprint", sprint, previous == null || previous.sprint != sprint)
            addKeyDiff(actions, "attack", attack, previous == null || previous.attack != attack)
            addKeyDiff(actions, "use", use, previous == null || previous.use != use)
            if (previous == null || previous.flying != flying) actions.add(MovementAction.fly(flying))
            if (previous == null || previous.selectedHotbarSlot != selectedHotbarSlot) actions.add(MovementAction.hotbar(selectedHotbarItemName, selectedHotbarSlot))
            if (previous == null || Math.abs(Mth.wrapDegrees(yaw - previous.yaw)) > ROTATION_EPSILON || Math.abs(pitch - previous.pitch) > ROTATION_EPSILON) {
                actions.add(MovementAction.rotation(yaw, pitch))
            }
            return actions
        }

        companion object {
            fun capture(client: Minecraft): InputSnapshot {
                val options = client.options
                val player = client.player
                return InputSnapshot(options.keyUp.isDown, options.keyDown.isDown, options.keyLeft.isDown, options.keyRight.isDown,
                    options.keyJump.isDown, options.keyShift.isDown, options.keySprint.isDown, options.keyAttack.isDown, options.keyUse.isDown,
                    player != null && player.abilities.flying,
                    if (player == null) -1 else (player.inventory as AccessorInventory).getSelected(),
                    selectedHotbarItemName(client), player?.yRot ?: 0.0f, player?.xRot ?: 0.0f)
            }

            private fun addKeyDiff(actions: MutableList<MovementAction>, key: String, down: Boolean, changed: Boolean) {
                if (changed) actions.add(MovementAction.key(key, down))
            }
        }
    }

    private class MovementEvent(val tick: Int, val actions: MutableList<MovementAction>) {
        fun toJson(): JsonObject {
            val obj = JsonObject()
            obj.addProperty("tick", tick)
            val actionArray = JsonArray()
            for (action in actions) actionArray.add(action.toJson())
            obj.add("actions", actionArray)
            return obj
        }

        companion object {
            fun fromJson(obj: JsonObject): MovementEvent {
                val tick = obj.get("tick").asInt
                val actionArray = obj.getAsJsonArray("actions")
                val actions = ArrayList<MovementAction>()
                if (actionArray != null) for (element in actionArray) actions.add(MovementAction.fromJson(element.asJsonObject))
                return MovementEvent(tick, actions)
            }
        }
    }

    private class MovementAction(
        val type: String,
        val key: String?,
        val down: Boolean,
        val yaw: Float,
        val pitch: Float,
        val message: String?,
        val command: Boolean,
        val itemName: String?,
        val slot: Int
    ) {
        fun toJson(): JsonObject {
            val obj = JsonObject()
            obj.addProperty("type", type)
            when (type) {
                "key" -> { obj.addProperty("key", key); obj.addProperty("down", down) }
                "fly" -> obj.addProperty("down", down)
                "rotation" -> { obj.addProperty("yaw", yaw); obj.addProperty("pitch", pitch) }
                "chat" -> { obj.addProperty("command", command); obj.addProperty("message", message) }
                "hotbar" -> { obj.addProperty("itemName", itemName ?: ""); obj.addProperty("slot", slot) }
            }
            return obj
        }

        companion object {
            fun key(key: String, down: Boolean) = MovementAction("key", key, down, 0.0f, 0.0f, null, false, null, -1)
            fun fly(down: Boolean) = MovementAction("fly", null, down, 0.0f, 0.0f, null, false, null, -1)
            fun rotation(yaw: Float, pitch: Float) = MovementAction("rotation", null, false, yaw, pitch, null, false, null, -1)
            fun chat(command: Boolean, message: String) = MovementAction("chat", null, false, 0.0f, 0.0f, message, command, null, -1)
            fun hotbar(itemName: String, slot: Int) = MovementAction("hotbar", null, false, 0.0f, 0.0f, null, false, itemName, slot)

            fun fromJson(obj: JsonObject): MovementAction {
                val type = obj.get("type").asString
                return when (type) {
                    "key" -> key(obj.get("key").asString, obj.get("down").asBoolean)
                    "fly" -> fly(obj.get("down").asBoolean)
                    "rotation" -> rotation(obj.get("yaw").asFloat, obj.get("pitch").asFloat)
                    "chat" -> chat(obj.get("command").asBoolean, obj.get("message").asString)
                    "hotbar" -> hotbar(if (obj.has("itemName")) obj.get("itemName").asString else "", if (obj.has("slot")) obj.get("slot").asInt else -1)
                    else -> MovementAction(type, null, false, 0.0f, 0.0f, null, false, null, -1)
                }
            }
        }
    }
}
