package dev.aether.ui.gui;

// local coordinates are in the space the region was registered in; pressRect is that region's
// local rect when the press landed, so drags keep their maths even if the layout moves underneath
public record PointerEvent(float rootX, float rootY, float localX, float localY, int button, int mods,
                           int clicks, Rect pressRect) {
}
