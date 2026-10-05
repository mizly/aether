package dev.aether.ui.gui.page;

import dev.aether.ui.gui.Palette;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.theme.ThemePreset;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;

// palettes for themes that are not applied, so theme cards can preview them in the active skin
public final class ThemePalettes {
    private static final List<Theme.ThemeEntry> ENTRIES =
            Stream.concat(Theme.ENTRIES.stream(), Theme.HUD_ENTRIES.stream()).toList();
    private static final Map<String, Palette> CACHE = new HashMap<>();

    private ThemePalettes() {
    }

    public static Palette of(ThemePreset preset) {
        return CACHE.computeIfAbsent(preset.id(), id -> build(entry -> preset.colour(entry.label)));
    }

    public static Palette classic() {
        return CACHE.computeIfAbsent(Theme.DEFAULT_COLOURS_ID, id -> build(Theme::defaultColour));
    }

    // Palette reads Theme's statics, so the theme's colours stand in for the one call and go back after
    private static Palette build(ToIntFunction<Theme.ThemeEntry> colour) {
        int[] saved = ENTRIES.stream().mapToInt(entry -> entry.getter.get()).toArray();
        try {
            ENTRIES.forEach(entry -> entry.setter.accept(colour.applyAsInt(entry)));
            return Palette.fromTheme();
        } finally {
            for (int i = 0; i < saved.length; i++) {
                ENTRIES.get(i).setter.accept(saved[i]);
            }
        }
    }
}
