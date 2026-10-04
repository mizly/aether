package dev.aether.ui.gui;

import java.util.Locale;

// an svg from the mod's resources (drawn as a tinted silhouette) or a minecraft item by id
public sealed interface Icon permits Icon.Svg, Icon.Item {

    static Icon svg(String resourcePath) {
        return new Svg(resourcePath);
    }

    static Icon item(String id) {
        return new Item(id);
    }

    record Svg(String resourcePath) implements Icon {
    }

    // ids are normalised so "WHEAT" and "minecraft:wheat" are the same icon and map key
    record Item(String id) implements Icon {
        public Item {
            id = id.trim().toLowerCase(Locale.ROOT);
            if (id.indexOf(':') < 0) {
                id = "minecraft:" + id;
            }
        }
    }
}
