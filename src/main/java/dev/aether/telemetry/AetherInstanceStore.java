package dev.aether.telemetry;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aether.Aether;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class AetherInstanceStore {
    private static final String FILE_NAME = "aether_instance.json";
    private static final String KEY = "instance_id";
    private static final Pattern VALID_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object LOCK = new Object();

    private static volatile String instanceId = "";
    private static volatile boolean loaded;

    private AetherInstanceStore() {
    }

    public static String getInstanceId() {
        ensureLoaded();
        return instanceId;
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    private static void load() {
        synchronized (LOCK) {
            if (loaded) {
                return;
            }

            String loadedId = read();
            if (!isValid(loadedId)) {
                loadedId = UUID.randomUUID().toString();
            }

            instanceId = loadedId;
            loaded = true;
            write();
        }
    }

    private static String read() {
        Path path = path();
        if (path == null || !Files.exists(path)) {
            return "";
        }

        try (Reader reader = Files.newBufferedReader(path)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (element == null || !element.isJsonObject()) {
                return "";
            }
            JsonObject obj = element.getAsJsonObject();
            if (!obj.has(KEY) || obj.get(KEY).isJsonNull()) {
                return "";
            }
            return obj.get(KEY).getAsString().trim();
        } catch (IOException | RuntimeException e) {
            Aether.LOGGER.warn("[aether] Could not read {}: {}", FILE_NAME, e.getClass().getSimpleName());
            return "";
        }
    }

    private static void write() {
        Path path = path();
        if (path == null) {
            return;
        }

        JsonObject obj = new JsonObject();
        obj.addProperty(KEY, instanceId);

        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = parent == null ? Path.of(FILE_NAME + ".tmp") : parent.resolve(FILE_NAME + ".tmp");
            Files.writeString(temp, GSON.toJson(obj));
            restrictPermissions(temp);
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Aether.LOGGER.warn("[aether] Could not write {}: {}", FILE_NAME, e.getClass().getSimpleName());
        }
    }

    private static void restrictPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
        }
    }

    private static Path path() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static boolean isValid(String value) {
        return value != null && VALID_ID.matcher(value).matches();
    }
}
