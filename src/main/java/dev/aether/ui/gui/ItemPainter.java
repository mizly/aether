package dev.aether.ui.gui;

import dev.aether.renderer.NVGRenderer;

// draws a minecraft item icon inside the nanovg frame; the renderer's item icons are installed through
// GuiCanvas.installItemPainter so this package never needs minecraft's item classes
@FunctionalInterface
public interface ItemPainter {
    void paint(NVGRenderer nvg, String itemId, float x, float y, float size, int tint);
}
