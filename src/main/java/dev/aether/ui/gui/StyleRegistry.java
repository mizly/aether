package dev.aether.ui.gui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// the gui styles by id; Theme.GUI_STYLE names the active one
public final class StyleRegistry {
    public static final String DEFAULT_ID = "aurora";
    private static final GuiStyle DEBUG = new DebugStyle();

    private final Map<String, GuiStyle> styles = new LinkedHashMap<>();

    public StyleRegistry(List<GuiStyle> styles) {
        for (GuiStyle style : styles) {
            this.styles.put(style.id(), style);
        }
    }

    // the built-in styles; until the real ones are registered here every id resolves to the debug scene
    public static StyleRegistry defaults() {
        return new StyleRegistry(List.of());
    }

    public List<GuiStyle> all() {
        return List.copyOf(styles.values());
    }

    // an unknown or missing id falls back to aurora, so a stale config never leaves the gui blank
    public GuiStyle resolve(String id) {
        GuiStyle style = id == null ? null : styles.get(id);
        if (style == null) {
            style = styles.get(DEFAULT_ID);
        }
        return style != null ? style : DEBUG;
    }
}
