package dev.aether.ui.theme;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

// the original red on black first, then ordered by accent hue so the picker reads as a colour wheel, with the
// neutral and light themes last
public enum ThemePreset {
    AETHER("Aether", "aether"),
    CRIMSON("Crimson", "crimson"),
    EMBER("Ember", "ember"),
    GRUVBOX("Gruvbox", "gruvbox"),
    MIDNIGHT("Midnight", "midnight"),
    MATCHA("Matcha", "matcha"),
    GARDEN("Garden", "garden"),
    SAGE("Sage", "sage"),
    OCEAN("Ocean", "ocean"),
    NORD("Nord", "nord"),
    SLATE("Slate", "slate"),
    TOKYO_NIGHT("Tokyo Night", "tokyonight"),
    DUSK("Dusk", "dusk"),
    CATPPUCCIN("Catppuccin", "catppuccin"),
    AMETHYST("Amethyst", "amethyst"),
    DRACULA("Dracula", "dracula"),
    ROSE_PINE("Rosé Pine", "rosepine"),
    GRAPHITE("Graphite", "graphite"),
    PAPER("Paper", "paper");

    // the classic red field defaults fail the contrast checks, so a fresh install starts here instead
    public static final ThemePreset DEFAULT = CRIMSON;

    public enum Target { MENU, MENU_AND_HUD }

    private final String label;
    private final String id;
    private Map<String, Integer> colours;

    ThemePreset(String label, String id) {
        this.label = label;
        this.id = id;
    }

    public String label() {
        return label;
    }

    public String id() {
        return id;
    }

    public String json() {
        String path = "/assets/aether/themes/" + id + ".json";
        try (var stream = ThemePreset.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing theme preset: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read theme preset: " + path, e);
        }
    }

    // parsed once: pickers draw every preset's swatches each frame
    public Map<String, Integer> colours() {
        if (colours == null) {
            Map<String, Integer> parsed = new LinkedHashMap<>();
            JsonParser.parseString(json()).getAsJsonObject().entrySet().forEach(entry ->
                    parsed.put(entry.getKey(), (int) Long.parseLong(entry.getValue().getAsString(), 16)));
            colours = Collections.unmodifiableMap(parsed);
        }
        return colours;
    }

    public int colour(String label) {
        return colours().getOrDefault(label, 0);
    }

    public boolean active() {
        return id.equals(Theme.PRESET_ID);
    }

    public void apply() {
        apply(Target.MENU_AND_HUD);
    }

    public void apply(Target target) {
        JsonObject json = JsonParser.parseString(json()).getAsJsonObject();
        if (target == Target.MENU) Theme.HUD_ENTRIES.forEach(entry -> json.remove(entry.label));
        Theme.importJson(json.toString());
        Theme.markPresetApplied(id, target == Target.MENU_AND_HUD);
    }

    public static Target defaultTarget() {
        return Theme.HUD_EDITED ? Target.MENU : Target.MENU_AND_HUD;
    }

    // accepts the id or the display name in any case, spacing or accents ("Rose pine", "tokyo-night")
    public static ThemePreset byName(String name) {
        if (name == null) return null;
        String key = normalise(name);
        for (ThemePreset preset : values()) {
            if (preset.id.equals(key) || normalise(preset.label).equals(key)) return preset;
        }
        return null;
    }

    public static ThemePreset current() {
        for (ThemePreset preset : values()) {
            if (preset.active()) return preset;
        }
        return null;
    }

    public static ThemePreset matching() {
        for (ThemePreset preset : values()) {
            if (Theme.ENTRIES.stream().allMatch(entry -> entry.getter.get() == preset.colour(entry.label))) {
                return preset;
            }
        }
        return null;
    }

    private static String normalise(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("[^A-Za-z0-9]", "")
                .toLowerCase(Locale.ROOT);
    }
}
