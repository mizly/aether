package dev.aether.ui.gui.nav;

import dev.aether.ui.gui.Icon;
import dev.aether.util.AetherLang;

import java.util.List;

public record NavCategory(String id, String rawName, String rawDescription, Icon icon, int order, List<NavPage> pages) {
    public NavCategory {
        pages = List.copyOf(pages);
    }

    public String name() {
        return AetherLang.localize(rawName);
    }

    public String description() {
        return rawDescription == null ? "" : AetherLang.localize(rawDescription);
    }
}
