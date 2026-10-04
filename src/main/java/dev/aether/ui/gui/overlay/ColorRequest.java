package dev.aether.ui.gui.overlay;

import dev.aether.ui.gui.Rect;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

// a colour picker anchored to a swatch; set runs live while dragging, with 0xAARRGGBB values
public record ColorRequest(Object anchorId, Rect anchor, String title, IntSupplier get, IntConsumer set) {
    public static ColorRequest of(Object anchorId, Rect anchor, String title, IntSupplier get, IntConsumer set) {
        return new ColorRequest(anchorId, anchor, title, get, set);
    }
}
