package dev.aether.config;

import dev.aether.ui.theme.Theme;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;

/**
 * Manages named theme profiles stored as JSON files under
 * {@code config/aether/themes/}.
 *
 * <p>Each profile is the output of {@link Theme#exportJson()}.
 * Loading applies the JSON via {@link Theme#importJson(String)} and
 * persists via {@link Theme#saveTheme()}.</p>
 */
public final class ThemeProfileManager {

    private static final Path DIR = FabricLoader.getInstance().getConfigDir()
            .resolve("aether").resolve("themes");

    static {
        try { Files.createDirectories(DIR); } catch (IOException ignored) {}
    }

    private ThemeProfileManager() {}

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

    /** Saves the current theme as a named profile. */
    public static void save(String name) {
        if (name.isBlank()) return;
        try { Files.writeString(DIR.resolve(sanitize(name) + ".json"), Theme.exportJson()); }
        catch (IOException e) { e.printStackTrace(); }
    }

    /** Loads a named theme profile and applies it live. */
    public static void load(String name) {
        Path src = DIR.resolve(sanitize(name) + ".json");
        if (!Files.exists(src)) return;
        try {
            String json = Files.readString(src);
            Theme.importJson(json);
            Theme.saveTheme();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void delete(String name) {
        try { Files.deleteIfExists(DIR.resolve(sanitize(name) + ".json")); }
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
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    // -- Export / Import -------------------------------------------------------

    public static String exportJson(String name) {
        try { return Files.readString(DIR.resolve(sanitize(name) + ".json")); }
        catch (IOException e) { return ""; }
    }

    public static void importJson(String name, String json) {
        if (name.isBlank() || json.isBlank()) return;
        try { Files.writeString(DIR.resolve(sanitize(name) + ".json"), json); }
        catch (IOException e) { e.printStackTrace(); }
    }

    // -- Helpers ---------------------------------------------------------------

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-. ]", "_").trim();
    }
}
