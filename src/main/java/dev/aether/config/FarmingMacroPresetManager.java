package dev.aether.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aether.config.entries.ConfigEntry;
import dev.aether.notification.NotificationManager;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class FarmingMacroPresetManager {
    private static final Gson PRESET_GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String EMPTY_OPTION = "No Presets";
    private static final Path PRESET_DIR = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("aether")
            .resolve("farming-macro-presets");
    private static final Map<String, ConfigEntry<?>> PRESET_ENTRIES = createPresetEntries();
    private static final List<String> PRESET_OPTIONS = new ArrayList<>();

    private FarmingMacroPresetManager() {
    }

    public static void init() {
        ensurePresetDirectory();
        refreshPresetOptions();
    }

    public static List<String> getPresetOptions() {
        if (PRESET_OPTIONS.isEmpty()) {
            refreshPresetOptions();
        }
        return PRESET_OPTIONS;
    }

    public static int getSelectedPresetIndex() {
        refreshPresetOptions();
        if (PRESET_OPTIONS.size() == 1 && EMPTY_OPTION.equals(PRESET_OPTIONS.getFirst())) {
            return 0;
        }

        String selectedPreset = AetherConfig.FARMING_MACRO_PRESET.get();
        for (int i = 0; i < PRESET_OPTIONS.size(); i++) {
            if (PRESET_OPTIONS.get(i).equalsIgnoreCase(selectedPreset)) {
                return i;
            }
        }

        return 0;
    }

    public static void applyPresetByIndex(int index) {
        refreshPresetOptions();
        if (index < 0 || index >= PRESET_OPTIONS.size()) {
            return;
        }

        String presetName = PRESET_OPTIONS.get(index);
        if (EMPTY_OPTION.equals(presetName)) {
            return;
        }

        applyPreset(presetName);
    }

    public static void reapplySelectedPreset() {
        refreshPresetOptions();
        if (PRESET_OPTIONS.size() == 1 && EMPTY_OPTION.equals(PRESET_OPTIONS.getFirst())) {
            NotificationManager.warning("No Farming Presets", "Save a preset before loading one.");
            return;
        }

        applyPresetByIndex(getSelectedPresetIndex());
    }

    public static void saveCurrentPreset() {
        init();

        String presetName = AetherConfig.FARMING_MACRO_PRESET_NAME.get();
        String presetId = normalizePresetId(presetName);
        if (presetId.isBlank()) {
            NotificationManager.warning("Preset Name Required", "Enter a preset name before saving.");
            return;
        }

        JsonObject preset = new JsonObject();
        for (Map.Entry<String, ConfigEntry<?>> entry : PRESET_ENTRIES.entrySet()) {
            preset.add(entry.getKey(), entry.getValue().toJson());
        }

        Path presetPath = PRESET_DIR.resolve(presetId + ".json");
        try {
            Path tempPath = PRESET_DIR.resolve(presetId + ".json.tmp");
            Files.writeString(tempPath, PRESET_GSON.toJson(preset));
            try {
                Files.move(tempPath, presetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ignored) {
                Files.move(tempPath, presetPath, StandardCopyOption.REPLACE_EXISTING);
            }
            AetherConfig.FARMING_MACRO_PRESET.set(displayNameFromId(presetId));
            AetherConfig.save();
            refreshPresetOptions();
            NotificationManager.success("Farming Preset Saved", "Preset saved successfully.");
        } catch (Exception e) {
            NotificationManager.error("Farming Preset Save Failed", "Could not save preset.");
            System.err.println("[Aether] Failed to save farming macro preset '" + presetId + "': " + e.getMessage());
        }
    }

    public static void openPresetFolder() {
        init();
        try {
            if (isWindows()) {
                new ProcessBuilder("explorer.exe", PRESET_DIR.toAbsolutePath().toString()).start();
                return;
            }

            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(PRESET_DIR.toFile());
            }
        } catch (IOException e) {
            NotificationManager.error("Farming Preset Folder Failed", "Could not open preset folder.");
            System.err.println("[Aether] Failed to open farming macro preset folder: " + e.getMessage());
        }
    }

    public static void refreshPresetOptions() {
        ensurePresetDirectory();
        List<String> options = new ArrayList<>();
        try (var stream = Files.list(PRESET_DIR)) {
            stream.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .map(path -> path.getFileName().toString())
                    .map(fileName -> fileName.substring(0, fileName.length() - ".json".length()))
                    .map(FarmingMacroPresetManager::displayNameFromId)
                    .forEach(options::add);
        } catch (IOException e) {
            System.err.println("[Aether] Failed to list farming macro presets: " + e.getMessage());
        }

        PRESET_OPTIONS.clear();
        if (options.isEmpty()) {
            PRESET_OPTIONS.add(EMPTY_OPTION);
        } else {
            PRESET_OPTIONS.addAll(options);
        }
    }

    private static void applyPreset(String presetName) {
        init();

        String presetId = normalizePresetId(presetName);
        Path presetPath = PRESET_DIR.resolve(presetId + ".json");
        if (!Files.exists(presetPath)) {
            NotificationManager.error("Farming Preset Load Failed", "Preset file is missing.");
            System.err.println("[Aether] Farming macro preset file missing: " + presetPath.toAbsolutePath());
            return;
        }

        try (Reader reader = Files.newBufferedReader(presetPath)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root == null || !root.isJsonObject()) {
                throw new IllegalStateException("root value is not a JSON object");
            }

            JsonObject preset = root.getAsJsonObject();
            Config.suspendAutosave(() -> {
                for (Map.Entry<String, ConfigEntry<?>> entry : PRESET_ENTRIES.entrySet()) {
                    JsonElement value = preset.get(entry.getKey());
                    if (value != null && !value.isJsonNull()) {
                        entry.getValue().fromJson(value);
                    }
                }
                AetherConfig.FARMING_MACRO_PRESET.set(displayNameFromId(presetId));
            });
            AetherConfig.save();
            NotificationManager.success("Farming Preset Loaded", "Preset loaded successfully.");
        } catch (Exception e) {
            NotificationManager.error("Farming Preset Load Failed", "Could not load preset.");
            System.err.println("[Aether] Failed to apply farming macro preset '" + presetId + "': " + e.getMessage());
        }
    }

    private static Map<String, ConfigEntry<?>> createPresetEntries() {
        Map<String, ConfigEntry<?>> entries = new LinkedHashMap<>();
        addEntry(entries, AetherConfig.FARM_TYPE);
        addEntry(entries, AetherConfig.MACRO_HOLD_W_WHILE_FARMING);
        addEntry(entries, AetherConfig.MACRO_FAST_LANE_SWITCH);
        addEntry(entries, AetherConfig.MACRO_FAST_LANE_BOUNDARY_AXIS);
        addEntry(entries, AetherConfig.MACRO_FAST_LANE_LEFT_BOUNDARY);
        addEntry(entries, AetherConfig.MACRO_FAST_LANE_RIGHT_BOUNDARY);
        addEntry(entries, AetherConfig.MACRO_FARM_WAYPOINTS);
        addEntry(entries, AetherConfig.MACRO_ROTATE_ON_DROP);
        addEntry(entries, AetherConfig.MACRO_DROP_ROTATION_DEGREES);
        addEntry(entries, AetherConfig.SQUEAKY_MOUSEMAT);
        addEntry(entries, AetherConfig.MACRO_USE_CUSTOM_PITCH);
        addEntry(entries, AetherConfig.MACRO_CUSTOM_PITCH);
        addEntry(entries, AetherConfig.MACRO_CUSTOM_PITCH_HUMANIZATION);
        addEntry(entries, AetherConfig.MACRO_USE_CUSTOM_YAW);
        addEntry(entries, AetherConfig.MACRO_CUSTOM_YAW);
        addEntry(entries, AetherConfig.MACRO_CUSTOM_YAW_HUMANIZATION);
        addEntry(entries, AetherConfig.BPS_AVERAGE_WINDOW);
        return entries;
    }

    private static void addEntry(Map<String, ConfigEntry<?>> entries, ConfigEntry<?> entry) {
        entries.put(entry.getKey(), entry);
    }

    private static void ensurePresetDirectory() {
        try {
            Files.createDirectories(PRESET_DIR);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create farming macro preset directory: " + PRESET_DIR, e);
        }
    }

    private static String normalizePresetId(String presetName) {
        if (presetName == null) {
            return "";
        }

        String lower = presetName.trim().toLowerCase(Locale.ROOT);
        StringBuilder builder = new StringBuilder();
        boolean lastWasSeparator = false;
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                builder.append(ch);
                lastWasSeparator = false;
            } else if (!lastWasSeparator) {
                builder.append('-');
                lastWasSeparator = true;
            }
        }

        int end = builder.length();
        while (end > 0 && builder.charAt(end - 1) == '-') {
            end--;
        }
        return builder.substring(0, end);
    }

    private static String displayNameFromId(String presetId) {
        if (presetId == null || presetId.isBlank()) {
            return "";
        }

        String[] parts = presetId.replace('_', '-').split("-");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }
        return builder.toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
