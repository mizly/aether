package dev.aether.ui.theme;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public enum ThemePreset {
    SLATE("Slate", "slate"),
    SAGE("Sage", "sage"),
    DUSK("Dusk", "dusk"),
    EMBER("Ember", "ember");

    private final String label;
    private final String resourceName;

    ThemePreset(String label, String resourceName) {
        this.label = label;
        this.resourceName = resourceName;
    }

    public String label() {
        return label;
    }

    public String json() {
        String path = "/assets/aether/themes/" + resourceName + ".json";
        try (var stream = ThemePreset.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing theme preset: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read theme preset: " + path, e);
        }
    }

    public void apply() {
        Theme.importJson(json());
    }
}
