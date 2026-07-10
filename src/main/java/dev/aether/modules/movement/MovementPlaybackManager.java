package dev.aether.modules.movement;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aether.mixin.AccessorInventory;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.rotation.RotationManager;
import dev.aether.macro.MacroStateManager;
import dev.aether.util.AetherLang;
import dev.aether.util.ClientUtils;
import dev.aether.util.ProgrammaticAttackTracker;
import dev.aether.util.ProgrammaticMovementTracker;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.awt.Desktop;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class MovementPlaybackManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter FILE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final float ROTATION_EPSILON = 0.01f;
    private static final Path MOVEMENT_DIR = FabricLoader.getInstance().getConfigDir()
            .resolve("aether")
            .resolve("movement");

    private static boolean registered;
    private static boolean recording;
    private static boolean playing;
    private static int recordingTick;
    private static int playbackTick;
    private static int playbackIndex;
    private static int playbackDurationTicks;
    private static Path recordingFile;
    private static InputSnapshot lastRecordedInput;
    private static final List<MovementEvent> recordingEvents = new ArrayList<>();
    private static final List<MovementEvent> playbackEvents = new ArrayList<>();

    private MovementPlaybackManager() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(MovementPlaybackManager::onClientTick);
    }

    public static synchronized void startRecording() {
        Minecraft client = Minecraft.getInstance();
        if (!isClientReady(client)) {
            ClientUtils.sendMessage("\u00A7cJoin a world before recording movement.", false);
            return;
        }
        if (playing) {
            stopPlayback(client, true);
        }
        if (recording) {
            ClientUtils.sendMessage("\u00A7eMovement recording is already running.", false);
            return;
        }

        recording = true;
        recordingTick = 0;
        recordingFile = MOVEMENT_DIR.resolve("movement_" + FILE_TIME_FORMAT.format(LocalDateTime.now()) + ".json");
        lastRecordedInput = null;
        recordingEvents.clear();
        ClientUtils.sendMessage("\u00A7aStarted movement recording.", false);
    }

    public static synchronized void stop() {
        Minecraft client = Minecraft.getInstance();
        if (recording) {
            stopRecording(client);
            return;
        }
        if (playing) {
            stopPlayback(client, true);
            return;
        }
        ClientUtils.sendMessage("\u00A7eNo movement recording or playback is running.", false);
    }

    public static synchronized void play(String replayFile) {
        Minecraft client = Minecraft.getInstance();
        playFromDirectory(client, MOVEMENT_DIR, replayFile);
    }

    public static synchronized void playFromDirectory(Minecraft client, Path replayDirectory, String replayFile) {
        if (!isClientReady(client)) {
            ClientUtils.sendMessage("\u00A7cJoin a world before playing movement.", false);
            return;
        }
        if (replayFile == null || replayFile.isBlank()) {
            ClientUtils.sendMessage("\u00A7eUsage: /aether movement play <replay_file>", false);
            return;
        }
        if (recording) {
            stopRecording(client);
        }
        if (MacroStateManager.isMacroRunning()) {
            MacroStateManager.stopMacro(client, "Movement playback started", false);
        }
        if (playing) {
            stopPlayback(client, true);
        }

        try {
            Path path = resolveReplayPath(replayDirectory, replayFile);
            List<MovementEvent> loadedEvents = loadEvents(path);
            playbackEvents.clear();
            playbackEvents.addAll(loadedEvents);
            playbackEvents.sort(Comparator.comparingInt(event -> event.tick));
            playbackTick = 0;
            playbackIndex = 0;
            playbackDurationTicks = loadDurationTicks(path, loadedEvents);
            playing = true;
            ClientUtils.sendMessage("\u00A7aPlaying movement replay: " + path.getFileName(), false);
        } catch (Exception ex) {
            ClientUtils.sendMessage("\u00A7cFailed to load movement replay: " + ex.getMessage(), false);
        }
    }

    public static synchronized void recordOutgoingChat(String message, boolean command) {
        if (!recording || message == null || message.isBlank()) {
            return;
        }
        if (command && isMovementCommand(message)) {
            return;
        }

        int tick = Math.max(1, recordingTick);
        MovementAction action = MovementAction.chat(command, message);
        addRecordingActions(tick, List.of(action));
    }

    public static boolean isRecording() {
        return recording;
    }

    public static boolean isPlaying() {
        return playing;
    }

    public static List<String> listReplayFiles() {
        return listReplayFiles(MOVEMENT_DIR);
    }

    public static List<String> listReplayFiles(Path replayDirectory) {
        if (!Files.isDirectory(replayDirectory)) {
            return List.of();
        }

        try (Stream<Path> files = Files.list(replayDirectory)) {
            return files
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".json"))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (IOException ignored) {
            return List.of();
        }
    }

    public static void openMovementFolder() {
        try {
            Files.createDirectories(MOVEMENT_DIR);

            if (isWindows()) {
                new ProcessBuilder("explorer.exe", MOVEMENT_DIR.toAbsolutePath().toString()).start();
            } else if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(MOVEMENT_DIR.toFile());
            } else {
                throw new IOException("desktop open is not supported on this platform");
            }

            ClientUtils.sendMessage("\u00A7a" + String.format(
                            AetherLang.localize("Opened movement folder: %s"),
                            MOVEMENT_DIR.toAbsolutePath()),
                    false);
        } catch (IOException ex) {
            ClientUtils.sendMessage("\u00A7c" + String.format(
                            AetherLang.localize("Failed to open movement folder: %s"),
                            ex.getMessage()),
                    false);
        }
    }

    private static synchronized void onClientTick(Minecraft client) {
        if (client == null) {
            return;
        }
        if (recording) {
            tickRecording(client);
        }
        if (playing) {
            tickPlayback(client);
        }
    }

    private static void tickRecording(Minecraft client) {
        if (!isClientReady(client)) {
            stopRecording(client);
            return;
        }

        recordingTick++;
        InputSnapshot snapshot = InputSnapshot.capture(client);
        List<MovementAction> actions = snapshot.diff(lastRecordedInput);
        if (!actions.isEmpty()) {
            addRecordingActions(recordingTick, actions);
        }
        lastRecordedInput = snapshot;
    }

    private static void tickPlayback(Minecraft client) {
        if (!isClientReady(client)) {
            stopPlayback(client, true);
            return;
        }

        playbackTick++;
        while (playbackIndex < playbackEvents.size()
                && playbackEvents.get(playbackIndex).tick <= playbackTick) {
            MovementEvent event = playbackEvents.get(playbackIndex++);
            for (MovementAction action : event.actions) {
                applyAction(client, action);
            }
        }

        if (playbackIndex >= playbackEvents.size() && playbackTick >= playbackDurationTicks) {
            stopPlayback(client, false);
            ClientUtils.sendMessage("\u00A7aMovement playback finished.", false);
        }
    }

    private static void stopRecording(Minecraft client) {
        recording = false;
        lastRecordedInput = null;
        try {
            saveRecording();
            ClientUtils.sendMessage("\u00A7aSaved movement recording: " + recordingFile.getFileName(), false);
        } catch (IOException ex) {
            ClientUtils.sendMessage("\u00A7cFailed to save movement recording: " + ex.getMessage(), false);
        }
    }

    private static void stopPlayback(Minecraft client, boolean notify) {
        playing = false;
        playbackTick = 0;
        playbackIndex = 0;
        playbackDurationTicks = 0;
        playbackEvents.clear();
        releasePlaybackInputs(client);
        if (notify) {
            ClientUtils.sendMessage("\u00A7eMovement playback stopped.", false);
        }
    }

    private static void saveRecording() throws IOException {
        Files.createDirectories(MOVEMENT_DIR);
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("durationTicks", recordingTick);
        root.addProperty("encoding", "aether.event_stream");

        JsonArray events = new JsonArray();
        for (MovementEvent event : recordingEvents) {
            events.add(event.toJson());
        }
        root.add("events", events);

        try (Writer writer = Files.newBufferedWriter(recordingFile, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        }
    }

    private static List<MovementEvent> loadEvents(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                throw new IOException("root must be a JSON object");
            }

            JsonArray array = parsed.getAsJsonObject().getAsJsonArray("events");
            if (array == null) {
                throw new IOException("missing events array");
            }

            List<MovementEvent> events = new ArrayList<>();
            for (JsonElement element : array) {
                events.add(MovementEvent.fromJson(element.getAsJsonObject()));
            }
            return events;
        }
    }

    private static int loadDurationTicks(Path path, List<MovementEvent> events) throws IOException {
        int maxEventTick = events.stream().mapToInt(event -> event.tick).max().orElse(0);
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonElement duration = root.get("durationTicks");
            if (duration != null && duration.isJsonPrimitive()) {
                return Math.max(maxEventTick, duration.getAsInt());
            }
        }
        return maxEventTick;
    }

    private static void addRecordingActions(int tick, List<MovementAction> actions) {
        if (!recordingEvents.isEmpty()) {
            MovementEvent last = recordingEvents.get(recordingEvents.size() - 1);
            if (last.tick == tick) {
                last.actions.addAll(actions);
                return;
            }
        }
        recordingEvents.add(new MovementEvent(tick, new ArrayList<>(actions)));
    }

    private static void applyAction(Minecraft client, MovementAction action) {
        switch (action.type) {
            case "key" -> setInput(client, action.key, action.down);
            case "rotation" -> setRotation(client, action.yaw, action.pitch);
            case "fly" -> setFlying(client, action.down);
            case "chat" -> sendChatAction(client, action);
            case "hotbar" -> selectHotbarItem(client, action.itemName, action.slot);
            default -> {
            }
        }
    }

    private static void setInput(Minecraft client, String key, boolean down) {
        KeyMapping mapping = keyMapping(client.options, key);
        if (mapping == null) {
            return;
        }

        ClientUtils.setKeyMappingState(mapping, down);
        if ("attack".equals(key)) {
            ProgrammaticAttackTracker.setHeld(mapping, down);
        } else {
            ProgrammaticMovementTracker.set(mapping, down);
        }
    }

    private static void setRotation(Minecraft client, float yaw, float pitch) {
        if (client.player == null) {
            return;
        }
        if (RotationManager.isRotating()) {
            RotationManager.cancelRotation();
        }
        pitch = Mth.clamp(pitch, -90.0f, 90.0f);
        RotationManager.rotateToYawPitch(client, yaw, pitch, 0L);
    }

    private static void setFlying(Minecraft client, boolean flying) {
        if (client.player == null || !client.player.getAbilities().mayfly) {
            return;
        }
        client.player.getAbilities().flying = flying;
        client.player.onUpdateAbilities();
    }

    private static void sendChatAction(Minecraft client, MovementAction action) {
        if (action.message == null || action.message.isBlank()) {
            return;
        }
        ClientUtils.sendCommand(action.command ? "/" + action.message : action.message);
    }

    private static void selectHotbarItem(Minecraft client, String itemName, int fallbackSlot) {
        if (client == null || client.player == null) {
            return;
        }

        int slot = findHotbarItemSlot(client, itemName);
        if (slot < 0 && normalizeItemName(itemName).isBlank() && fallbackSlot >= 0 && fallbackSlot <= 8) {
            slot = fallbackSlot;
        }
        if (slot >= 0 && slot <= 8) {
            FailsafeManager.selectHotbarSlot(client, slot);
        }
    }

    private static int findHotbarItemSlot(Minecraft client, String itemName) {
        String needle = normalizeItemName(itemName);
        if (needle.isBlank() || client == null || client.player == null) {
            return -1;
        }
        String normalizedNeedle = needle.toLowerCase(Locale.ROOT);

        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String normalizedName = normalizeItemName(stack.getHoverName().getString()).toLowerCase(Locale.ROOT);
            if (normalizedName.contains(normalizedNeedle)) {
                return slot;
            }
        }
        return -1;
    }

    private static String selectedHotbarItemName(Minecraft client) {
        if (client == null || client.player == null) {
            return "";
        }

        int selected = ((AccessorInventory) client.player.getInventory()).getSelected();
        if (selected < 0 || selected > 8) {
            return "";
        }

        ItemStack stack = client.player.getInventory().getItem(selected);
        return stack.isEmpty() ? "" : normalizeItemName(stack.getHoverName().getString());
    }

    private static String normalizeItemName(String itemName) {
        if (itemName == null) {
            return "";
        }
        return itemName.replaceAll("\u00A7[0-9A-FK-ORa-fk-or]", "").trim();
    }

    private static void releasePlaybackInputs(Minecraft client) {
        if (client == null || client.options == null) {
            return;
        }

        setInput(client, "forward", false);
        setInput(client, "back", false);
        setInput(client, "left", false);
        setInput(client, "right", false);
        setInput(client, "jump", false);
        setInput(client, "sneak", false);
        setInput(client, "sprint", false);
        setInput(client, "attack", false);
        setInput(client, "use", false);
    }

    private static boolean isClientReady(Minecraft client) {
        return client != null && client.player != null && client.level != null && client.options != null;
    }

    private static boolean isMovementCommand(String command) {
        String normalized = command.toLowerCase(Locale.ROOT).trim();
        return normalized.startsWith("aether movement ");
    }

    private static Path resolveReplayPath(Path replayDirectory, String replayFile) throws IOException {
        Files.createDirectories(replayDirectory);
        String file = replayFile.trim();
        if (!file.endsWith(".json")) {
            file += ".json";
        }

        Path normalizedDirectory = replayDirectory.normalize();
        Path resolved = replayDirectory.resolve(file).normalize();
        if (!resolved.startsWith(normalizedDirectory)) {
            throw new IOException("file must stay inside " + replayDirectory);
        }
        if (!Files.isRegularFile(resolved)) {
            throw new IOException("file not found: " + resolved.getFileName());
        }
        return resolved;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static KeyMapping keyMapping(Options options, String key) {
        if (options == null || key == null) {
            return null;
        }
        return switch (key) {
            case "forward" -> options.keyUp;
            case "back" -> options.keyDown;
            case "left" -> options.keyLeft;
            case "right" -> options.keyRight;
            case "jump" -> options.keyJump;
            case "sneak" -> options.keyShift;
            case "sprint" -> options.keySprint;
            case "attack" -> options.keyAttack;
            case "use" -> options.keyUse;
            default -> null;
        };
    }

    private static final class InputSnapshot {
        private final boolean forward;
        private final boolean back;
        private final boolean left;
        private final boolean right;
        private final boolean jump;
        private final boolean sneak;
        private final boolean sprint;
        private final boolean attack;
        private final boolean use;
        private final boolean flying;
        private final int selectedHotbarSlot;
        private final String selectedHotbarItemName;
        private final float yaw;
        private final float pitch;

        private InputSnapshot(
                boolean forward,
                boolean back,
                boolean left,
                boolean right,
                boolean jump,
                boolean sneak,
                boolean sprint,
                boolean attack,
                boolean use,
                boolean flying,
                int selectedHotbarSlot,
                String selectedHotbarItemName,
                float yaw,
                float pitch
        ) {
            this.forward = forward;
            this.back = back;
            this.left = left;
            this.right = right;
            this.jump = jump;
            this.sneak = sneak;
            this.sprint = sprint;
            this.attack = attack;
            this.use = use;
            this.flying = flying;
            this.selectedHotbarSlot = selectedHotbarSlot;
            this.selectedHotbarItemName = selectedHotbarItemName;
            this.yaw = yaw;
            this.pitch = pitch;
        }

        private static InputSnapshot capture(Minecraft client) {
            Options options = client.options;
            return new InputSnapshot(
                    options.keyUp.isDown(),
                    options.keyDown.isDown(),
                    options.keyLeft.isDown(),
                    options.keyRight.isDown(),
                    options.keyJump.isDown(),
                    options.keyShift.isDown(),
                    options.keySprint.isDown(),
                    options.keyAttack.isDown(),
                    options.keyUse.isDown(),
                    client.player != null && client.player.getAbilities().flying,
                    client.player == null ? -1 : ((AccessorInventory) client.player.getInventory()).getSelected(),
                    selectedHotbarItemName(client),
                    client.player == null ? 0.0f : client.player.getYRot(),
                    client.player == null ? 0.0f : client.player.getXRot()
            );
        }

        private List<MovementAction> diff(InputSnapshot previous) {
            List<MovementAction> actions = new ArrayList<>();
            addKeyDiff(actions, "forward", forward, previous == null || previous.forward != forward);
            addKeyDiff(actions, "back", back, previous == null || previous.back != back);
            addKeyDiff(actions, "left", left, previous == null || previous.left != left);
            addKeyDiff(actions, "right", right, previous == null || previous.right != right);
            addKeyDiff(actions, "jump", jump, previous == null || previous.jump != jump);
            addKeyDiff(actions, "sneak", sneak, previous == null || previous.sneak != sneak);
            addKeyDiff(actions, "sprint", sprint, previous == null || previous.sprint != sprint);
            addKeyDiff(actions, "attack", attack, previous == null || previous.attack != attack);
            addKeyDiff(actions, "use", use, previous == null || previous.use != use);

            if (previous == null || previous.flying != flying) {
                actions.add(MovementAction.fly(flying));
            }
            if (previous == null || previous.selectedHotbarSlot != selectedHotbarSlot) {
                actions.add(MovementAction.hotbar(selectedHotbarItemName, selectedHotbarSlot));
            }
            if (previous == null
                    || Math.abs(Mth.wrapDegrees(yaw - previous.yaw)) > ROTATION_EPSILON
                    || Math.abs(pitch - previous.pitch) > ROTATION_EPSILON) {
                actions.add(MovementAction.rotation(yaw, pitch));
            }
            return actions;
        }

        private static void addKeyDiff(
                List<MovementAction> actions,
                String key,
                boolean down,
                boolean changed
        ) {
            if (changed) {
                actions.add(MovementAction.key(key, down));
            }
        }
    }

    private static final class MovementEvent {
        private final int tick;
        private final List<MovementAction> actions;

        private MovementEvent(int tick, List<MovementAction> actions) {
            this.tick = tick;
            this.actions = actions;
        }

        private JsonObject toJson() {
            JsonObject object = new JsonObject();
            object.addProperty("tick", tick);
            JsonArray actionArray = new JsonArray();
            for (MovementAction action : actions) {
                actionArray.add(action.toJson());
            }
            object.add("actions", actionArray);
            return object;
        }

        private static MovementEvent fromJson(JsonObject object) {
            int tick = object.get("tick").getAsInt();
            JsonArray actionArray = object.getAsJsonArray("actions");
            List<MovementAction> actions = new ArrayList<>();
            if (actionArray != null) {
                for (JsonElement actionElement : actionArray) {
                    actions.add(MovementAction.fromJson(actionElement.getAsJsonObject()));
                }
            }
            return new MovementEvent(tick, actions);
        }
    }

    private static final class MovementAction {
        private final String type;
        private final String key;
        private final boolean down;
        private final float yaw;
        private final float pitch;
        private final String message;
        private final boolean command;
        private final String itemName;
        private final int slot;

        private MovementAction(
                String type,
                String key,
                boolean down,
                float yaw,
                float pitch,
                String message,
                boolean command,
                String itemName,
                int slot
        ) {
            this.type = type;
            this.key = key;
            this.down = down;
            this.yaw = yaw;
            this.pitch = pitch;
            this.message = message;
            this.command = command;
            this.itemName = itemName;
            this.slot = slot;
        }

        private static MovementAction key(String key, boolean down) {
            return new MovementAction("key", key, down, 0.0f, 0.0f, null, false, null, -1);
        }

        private static MovementAction fly(boolean down) {
            return new MovementAction("fly", null, down, 0.0f, 0.0f, null, false, null, -1);
        }

        private static MovementAction rotation(float yaw, float pitch) {
            return new MovementAction("rotation", null, false, yaw, pitch, null, false, null, -1);
        }

        private static MovementAction chat(boolean command, String message) {
            return new MovementAction("chat", null, false, 0.0f, 0.0f, message, command, null, -1);
        }

        private static MovementAction hotbar(String itemName, int slot) {
            return new MovementAction("hotbar", null, false, 0.0f, 0.0f, null, false, itemName, slot);
        }

        private JsonObject toJson() {
            JsonObject object = new JsonObject();
            object.addProperty("type", type);
            switch (type) {
                case "key" -> {
                    object.addProperty("key", key);
                    object.addProperty("down", down);
                }
                case "fly" -> object.addProperty("down", down);
                case "rotation" -> {
                    object.addProperty("yaw", yaw);
                    object.addProperty("pitch", pitch);
                }
                case "chat" -> {
                    object.addProperty("command", command);
                    object.addProperty("message", message);
                }
                case "hotbar" -> {
                    object.addProperty("itemName", itemName == null ? "" : itemName);
                    object.addProperty("slot", slot);
                }
                default -> {
                }
            }
            return object;
        }

        private static MovementAction fromJson(JsonObject object) {
            String type = object.get("type").getAsString();
            return switch (type) {
                case "key" -> key(object.get("key").getAsString(), object.get("down").getAsBoolean());
                case "fly" -> fly(object.get("down").getAsBoolean());
                case "rotation" -> rotation(object.get("yaw").getAsFloat(), object.get("pitch").getAsFloat());
                case "chat" -> chat(object.get("command").getAsBoolean(), object.get("message").getAsString());
                case "hotbar" -> hotbar(
                        object.has("itemName") ? object.get("itemName").getAsString() : "",
                        object.has("slot") ? object.get("slot").getAsInt() : -1);
                default -> new MovementAction(type, null, false, 0.0f, 0.0f, null, false, null, -1);
            };
        }
    }
}
