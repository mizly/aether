package dev.aether.config;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.nio.file.*;
import java.util.*;

/**
 * Lightweight JSON config backed by Gson, stored in Fabric's config directory.
 *
 * <p>Register entries with {@link #register} before calling {@link #init}.
 * Missing keys are auto-filled from defaults on load.</p>
 */
public final class ConfigManager {

    // -- Entry definition ------------------------------------------------------

    public static final class Entry {
        public final String key, label;
        public final Object defaultValue; // Boolean | Double | String
        public final double min, max;     // meaningful only for Double entries

        Entry(String key, String label, Object def, double min, double max) {
            this.key = key; this.label = label;
            this.defaultValue = def; this.min = min; this.max = max;
        }

        public boolean isBool()   { return defaultValue instanceof Boolean; }
        public boolean isNumber() { return defaultValue instanceof Double;  }
        public boolean isString() { return defaultValue instanceof String;  }
    }

    // -- State -----------------------------------------------------------------

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<Entry>          SCHEMA = new ArrayList<>();
    private static final Map<String, Object>  VALUES = new LinkedHashMap<>();
    private static Path configPath;

    private ConfigManager() {}

    // -- Registration ----------------------------------------------------------

    public static void register(String key, String label, boolean def) {
        SCHEMA.add(new Entry(key, label, def, 0, 0));
        VALUES.put(key, def);
    }

    public static void register(String key, String label, double def, double min, double max) {
        SCHEMA.add(new Entry(key, label, def, min, max));
        VALUES.put(key, def);
    }

    public static void register(String key, String label, String def) {
        SCHEMA.add(new Entry(key, label, def, 0, 0));
        VALUES.put(key, def);
    }

    // -- Lifecycle -------------------------------------------------------------

    /**
     * Registers built-in entries, then loads from disk.
     * Call once during mod init.
     */
    public static void init() {
        configPath = FabricLoader.getInstance().getConfigDir().resolve("aether_ui.json");
        registerDefaults();
        load();
    }

    private static void registerDefaults() {
        register("ui.animations",  "UI Animations",       true);
        register("ui.opacity",     "UI Opacity",          0.95,  0.3,  1.0);
        register("hud.enabled",    "Show HUD",            true);
        register("hud.scale",      "HUD Scale",           1.0,   0.5,  2.0);
        register("macro.delay",    "Macro Delay (ms)",    150.0, 0.0,  2000.0);
        register("theme.accent",   "Accent Color (hex)",  "#D32F2F");
    }

    public static void load() {
        if (configPath == null) return;
        if (!Files.exists(configPath)) { save(); return; }
        try (Reader r = Files.newBufferedReader(configPath)) {
            JsonObject obj = GSON.fromJson(r, JsonObject.class);
            if (obj == null) return;
            for (Entry e : SCHEMA) {
                if (!obj.has(e.key)) continue;  // auto-fills with default
                JsonElement el = obj.get(e.key);
                if (e.isBool())        VALUES.put(e.key, el.getAsBoolean());
                else if (e.isNumber()) VALUES.put(e.key, el.getAsDouble());
                else                   VALUES.put(e.key, el.getAsString());
            }
        } catch (Exception ex) {
            System.err.println("[aether] Config load failed: " + ex.getMessage());
        }
    }

    public static void save() {
        if (configPath == null) return;
        JsonObject obj = new JsonObject();
        VALUES.forEach((k, v) -> {
            if (v instanceof Boolean b)     obj.addProperty(k, b);
            else if (v instanceof Double d) obj.addProperty(k, d);
            else                            obj.addProperty(k, v.toString());
        });
        try { Files.writeString(configPath, GSON.toJson(obj)); }
        catch (Exception ex) { System.err.println("[aether] Config save failed: " + ex.getMessage()); }
    }

    // -- Accessors -------------------------------------------------------------

    public static boolean getBool(String key)   { return (Boolean) VALUES.getOrDefault(key, false); }
    public static double  getNumber(String key) { return ((Number) VALUES.getOrDefault(key, 0.0)).doubleValue(); }
    public static String  getString(String key) { return VALUES.getOrDefault(key, "").toString(); }
    public static void    set(String key, Object value) { VALUES.put(key, value); }

    public static List<Entry> getSchema() { return Collections.unmodifiableList(SCHEMA); }
}
