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

// kept out of aether_config.json on purpose: config profiles are shared between users and would leak the token.
public final class AetherTokenStore {
    private static final String FILE_NAME = "aether_auth.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object LOCK = new Object();

    private static volatile String token = "";
    private static volatile String discordId = "";
    private static volatile boolean loaded;

    private AetherTokenStore() {
    }

    public static void load() {
        synchronized (LOCK) {
            loaded = true;
            token = "";
            discordId = "";

            Path path = path();
            if (path == null || !Files.exists(path)) {
                return;
            }
            try (Reader reader = Files.newBufferedReader(path)) {
                JsonElement element = JsonParser.parseReader(reader);
                if (element == null || !element.isJsonObject()) {
                    return;
                }
                JsonObject obj = element.getAsJsonObject();
                token = readString(obj, "token");
                discordId = readString(obj, "discord_id");
            } catch (IOException | RuntimeException e) {
                Aether.LOGGER.warn("[aether] Could not read {}: {}", FILE_NAME, e.getClass().getSimpleName());
            }
        }
    }

    public static void save(String newToken, String newDiscordId) {
        synchronized (LOCK) {
            token = newToken == null ? "" : newToken.trim();
            discordId = newDiscordId == null ? "" : newDiscordId.trim();
            loaded = true;
            write();
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            token = "";
            discordId = "";
            loaded = true;
            Path path = path();
            if (path == null) {
                return;
            }
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                Aether.LOGGER.warn("[aether] Could not delete {}: {}", FILE_NAME, e.getClass().getSimpleName());
            }
        }
    }

    public static String getToken() {
        ensureLoaded();
        return token;
    }

    public static String getDiscordId() {
        ensureLoaded();
        return discordId;
    }

    public static boolean hasToken() {
        return !getToken().isEmpty();
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    private static void write() {
        Path path = path();
        if (path == null) {
            return;
        }

        JsonObject obj = new JsonObject();
        obj.addProperty("token", token);
        obj.addProperty("discord_id", discordId);

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
            // windows and some filesystems have no POSIX permissions; nothing to harden there.
        }
    }

    private static Path path() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static String readString(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return "";
        }
        try {
            return obj.get(key).getAsString().trim();
        } catch (RuntimeException e) {
            return "";
        }
    }
}
