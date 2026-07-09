package dev.aether.config;

import com.google.gson.*;
import dev.aether.config.entries.*;

import java.io.*;
import java.nio.file.*;
import java.nio.file.StandardCopyOption;
import java.util.*;

public final class Config {
    private static final List<ConfigEntry<?>> ENTRIES = new ArrayList<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path configPath;
    private static boolean dirty;
    private static int autosaveSuspendDepth;

    private Config() {}

    static void setConfigPath(Path path) { configPath = path; }

    private static <T, E extends ConfigEntry<T>> E register(E entry) {
        ENTRIES.add(entry);
        return entry;
    }

    public static BooleanEntry bool(String key, boolean defaultValue) {
        return register(new BooleanEntry(key, defaultValue));
    }

    public static IntEntry integer(String key, int defaultValue) {
        return register(new IntEntry(key, defaultValue));
    }

    public static FloatEntry floatVal(String key, float defaultValue) {
        return register(new FloatEntry(key, defaultValue));
    }

    public static DoubleEntry doubleVal(String key, double defaultValue) {
        return register(new DoubleEntry(key, defaultValue));
    }

    public static StringEntry string(String key, String defaultValue) {
        return register(new StringEntry(key, defaultValue));
    }

    public static <T> ListEntry<T> list(String key, List<T> defaultValue, Class<T> elementType) {
        return register(new ListEntry<>(key, defaultValue, elementType));
    }

    public static void save() {
        dirty = true;
        flush();
    }

    public static void onEntryChanged() {
        dirty = true;
        if (autosaveSuspendDepth == 0) flush();
    }

    public static void flush() {
        if (configPath == null) return;
        if (!dirty && Files.exists(configPath)) return;
        writeConfig(configPath);
        dirty = false;
    }

    public static String toJsonString() {
        return GSON.toJson(toJsonObject());
    }

    public static boolean loadFrom(Path path) {
        if (path == null || !Files.exists(path)) return false;
        try {
            JsonObject obj = readJsonObject(path);
            withoutAutosave(() -> applyJson(obj, true));
            dirty = true;
            return true;
        } catch (Exception e) {
            System.err.println("[Aether] Config load failed for " + path + ": " + e.getMessage());
            return false;
        }
    }

    public static boolean loadFromJson(String json) {
        if (json == null || json.isBlank()) return false;
        try {
            JsonElement element = JsonParser.parseString(json);
            if (element == null || !element.isJsonObject()) {
                throw new JsonParseException("root value is not a JSON object");
            }
            JsonObject obj = element.getAsJsonObject();
            withoutAutosave(() -> applyJson(obj, true));
            dirty = true;
            return true;
        } catch (Exception e) {
            System.err.println("[Aether] Config import failed: " + e.getMessage());
            return false;
        }
    }

    public static void load() {
        if (configPath == null) return;
        if (!Files.exists(configPath)) {
            withoutAutosave(Config::resetEntriesOnly);
            dirty = true;
            return;
        }
        try {
            JsonObject obj = readJsonObject(configPath);
            withoutAutosave(() -> applyJson(obj, true));
            dirty = false;
        } catch (Exception e) {
            System.err.println("[Aether] Corrupt config file found. Wiping config and starting fresh: " + e.getMessage());
            withoutAutosave(Config::resetEntriesOnly);
            dirty = true;
            try {
                Files.deleteIfExists(configPath);
            } catch (IOException deleteError) {
                deleteError.printStackTrace();
            }
        }
    }

    public static void reset() {
        withoutAutosave(Config::resetEntriesOnly);
        dirty = true;
        flush();
    }

    public static void suspendAutosave(Runnable action) {
        withoutAutosave(action);
    }

    private static void writeConfig(Path path) {
        try {
            // Ensure parent directory exists (some environments may not have the config dir created)
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) Files.createDirectories(parent);
        } catch (IOException ignored) {}

        JsonObject obj = toJsonObject();
        try {
            Path parent = path.getParent();
            Path tempPath = parent == null
                    ? Path.of(path.getFileName() + ".tmp")
                    : parent.resolve(path.getFileName() + ".tmp");
            Files.writeString(tempPath, GSON.toJson(obj));
            try {
                Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // Print stack trace so developers can diagnose environment file permission errors.
            e.printStackTrace();
        }
    }

    private static JsonObject toJsonObject() {
        JsonObject obj = new JsonObject();
        for (ConfigEntry<?> entry : ENTRIES) {
            if (entry.isPersistent())
                obj.add(entry.getKey(), entry.toJson());
        }
        return obj;
    }

    private static JsonObject readJsonObject(Path path) throws IOException {
        try (Reader r = Files.newBufferedReader(path)) {
            JsonElement element = JsonParser.parseReader(r);
            if (element == null || !element.isJsonObject()) {
                throw new JsonParseException("root value is not a JSON object");
            }
            return element.getAsJsonObject();
        }
    }

    private static void applyJson(JsonObject obj, boolean resetMissing) {
        if (resetMissing) resetEntriesOnly();
        for (ConfigEntry<?> entry : ENTRIES) {
            if (obj.has(entry.getKey()))
                entry.fromJson(obj.get(entry.getKey()));
        }
    }

    private static void resetEntriesOnly() {
        for (ConfigEntry<?> entry : ENTRIES) entry.reset();
    }

    private static void withoutAutosave(Runnable action) {
        autosaveSuspendDepth++;
        try {
            action.run();
        } finally {
            autosaveSuspendDepth--;
        }
    }
}
