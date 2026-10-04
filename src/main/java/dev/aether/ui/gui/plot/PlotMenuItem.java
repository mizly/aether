package dev.aether.ui.gui.plot;

import java.util.List;
import java.util.Locale;

// one slot of the Configure Plots menu as read: item id, name and lore keep hypixel's § colour codes
public record PlotMenuItem(String itemId, String name, List<String> lore) {
    public PlotMenuItem {
        itemId = itemId == null ? "minecraft:air" : itemId.trim().toLowerCase(Locale.ROOT);
        if (itemId.indexOf(':') < 0) {
            itemId = "minecraft:" + itemId;
        }
        name = name == null ? "" : name;
        lore = lore == null ? List.of() : List.copyOf(lore);
    }
}
