package dev.aether.ui.gui;

// what the screen shell draws behind the gui in minecraft's own pass, before the nanovg frame
public enum Backdrop {
    // the world blurred, then a vanilla fill of the view's scrim colour so the blur pass actually runs
    WORLD_BLUR,
    // vanilla's container dim, for the inventory style
    VANILLA_DIM,
    NONE
}
