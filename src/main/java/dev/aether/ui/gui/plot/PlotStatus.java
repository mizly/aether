package dev.aether.ui.gui.plot;

// what hypixel's icon and lore say about a plot; UNCLEANED and the greenhouse glass are inferred from
// screenshots, the rest is confirmed by mods that parse this menu
public enum PlotStatus {
    LOCKED, UNLOCKABLE, UNCLEANED, CLEANED, PRESET, GREENHOUSE, BARN;

    public boolean unlocked() {
        return this != LOCKED && this != UNLOCKABLE;
    }
}
