package dev.aether.config;

import dev.aether.bootstrap.AetherBootstrapHooks;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;

/**
 * Manages named config profiles stored as JSON files under
 * {@code config/aether/profiles/}.
 *
 * <p>Each profile stores a snapshot of the live config values. Loading a
 * profile applies it to memory and writes it to the main config file.</p>
 */
public final class ConfigProfileManager {

    private static final Path DIR = FabricLoader.getInstance().getConfigDir()
            .resolve("aether").resolve("profiles");
    private static volatile Path activeProfilePath;

    static {
        try { Files.createDirectories(DIR); } catch (IOException ignored) {}
    }

    private ConfigProfileManager() {}

    // -- CRUD ------------------------------------------------------------------

    public static List<String> list() {
        try {
            return Files.list(DIR)
                    .filter(p -> p.toString().endsWith(".json"))
                    .map(p -> p.getFileName().toString().replace(".json", ""))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Saves the current config as a named profile. */
    public static void save(String name) {
        if (name.isBlank()) return;
        Path profilePath = DIR.resolve(sanitize(name) + ".json");
        try {
            Files.writeString(profilePath, AetherConfig.toJsonString());
            activeProfilePath = profilePath;
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** Loads a named profile and applies it to the live config. */
    public static boolean load(String name) {
        Path src = DIR.resolve(sanitize(name) + ".json");
        if (!Files.exists(src)) return false;
        boolean loaded = AetherConfig.loadFrom(src.toFile());
        if (loaded) {
            activeProfilePath = src;
            AetherBootstrapHooks.onConfigProfileLoaded(src.toFile());
            AetherConfig.flush();
            syncActiveProfileFromLiveConfig();
        }
        return loaded;
    }

    public static void delete(String name) {
        Path profilePath = DIR.resolve(sanitize(name) + ".json");
        try {
            Files.deleteIfExists(profilePath);
            if (profilePath.equals(activeProfilePath)) {
                activeProfilePath = null;
            }
        }
        catch (IOException e) { e.printStackTrace(); }
    }

    public static boolean rename(String oldName, String newName) {
        String oldSanitized = sanitize(oldName);
        String newSanitized = sanitize(newName);
        if (oldSanitized.isBlank() || newSanitized.isBlank()) return false;

        Path src = DIR.resolve(oldSanitized + ".json");
        if (!Files.exists(src)) return false;
        if (oldSanitized.equals(newSanitized)) return true;

        Path dst = DIR.resolve(newSanitized + ".json");
        if (Files.exists(dst)) return false;
        try {
            Files.move(src, dst);
            if (src.equals(activeProfilePath)) {
                activeProfilePath = dst;
            }
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    // -- Export / Import -------------------------------------------------------

    /** Returns the raw JSON of a saved profile (for clipboard export). */
    public static String exportJson(String name) {
        try { return Files.readString(DIR.resolve(sanitize(name) + ".json")); }
        catch (IOException e) { return ""; }
    }

    /** Returns the raw JSON of a saved profile (for clipboard export), with sensitive fields blanked. */
    public static String exportJsonSanitized(String name) {
        String json = exportJson(name);
        if (json.isBlank()) return json;
        try {
            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            // Always blank sensitive fields
            obj.addProperty("bootstrapLicenseKey", "");
            obj.addProperty("discordWebhookUrl", "");
            obj.add("coopNames", new com.google.gson.JsonArray());
            obj.addProperty("customUsername", "");
            obj.addProperty("serverNick", "");
            // Optionally blank more fields here if needed
            return new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(obj);
        } catch (Exception e) {
            e.printStackTrace();
            return json;
        }
    }

    /** Saves a JSON string as a named profile (for clipboard import). */
    public static void importJson(String name, String json) {
        if (name.isBlank() || json.isBlank()) return;
        try { Files.writeString(DIR.resolve(sanitize(name) + ".json"), json); }
        catch (IOException e) { e.printStackTrace(); }
    }

    /** Imports from clipboard JSON using the same name embedded in the JSON filename,
     *  falling back to {@code name} if the JSON has no filename. */
    public static void importFromClipboard(String name, String json) {
        importJson(name.isBlank() ? "imported" : name, json);
    }

    // -- Helpers ---------------------------------------------------------------

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-. ]", "_").trim();
    }

    static void syncActiveProfileFromLiveConfig() {
        Path profilePath = activeProfilePath;
        if (profilePath == null) {
            return;
        }

        try {
            Files.createDirectories(profilePath.getParent());
            Files.writeString(profilePath, AetherConfig.toJsonString());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

