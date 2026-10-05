package dev.aether.ui.orbit;

import dev.aether.macro.MacroState;
import dev.aether.util.ClientUtils;
import dev.aether.util.TablistUtils;
import net.minecraft.client.Minecraft;

import java.util.Locale;

// which island the menu opened on, read from the sidebar and the tab list's "Area:" line; nothing is sent
enum OrbitIsland {
    GARDEN("Garden"),
    CRIMSON_ISLE("Crimson Isle"),
    OTHER("SkyBlock");

    private static OrbitIsland lastOpened;

    final String label;

    OrbitIsland(String label) {
        this.label = label;
    }

    static OrbitIsland current() {
        MacroState.Location location = ClientUtils.getCurrentLocation();
        if (location == MacroState.Location.GARDEN) return GARDEN;
        if (location != MacroState.Location.HUB) return OTHER;
        return fromArea(TablistUtils.findLine(Minecraft.getInstance(), "Area:"));
    }

    static OrbitIsland fromArea(String areaLine) {
        if (areaLine == null) return OTHER;
        String area = TablistUtils.stripColors(areaLine).replace(' ', ' ').trim().toLowerCase(Locale.ROOT);
        if (area.equals("area: garden")) return GARDEN;
        if (area.equals("area: crimson isle")) return CRIMSON_ISLE;
        return OTHER;
    }

    // the island the menu last opened on before this one, or null when it is the same or either is unknown
    static OrbitIsland arrive(OrbitIsland now) {
        OrbitIsland from = lastOpened;
        if (now != OTHER) lastOpened = now;
        return from != null && now != OTHER && from != now ? from : null;
    }

    // the category the menu should face first: the last macro's home, else what this island is for
    static String initialCategory(String lastMacroId, OrbitIsland island) {
        if ("strider_fishing".equals(lastMacroId)) return "macros";
        if ("farming".equals(lastMacroId)) return "farming";
        return island == CRIMSON_ISLE ? "macros" : "farming";
    }
}
