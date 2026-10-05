package dev.aether.ui.gui;

import dev.aether.ui.gui.nav.GuiActions;

// everything one frame draws with. coordinates are layout units with the view's top-left at 0,0;
// textScale is the frozen-while-dragging copy of the theme's text scale that sizes should multiply in.
// actions is the navigation state and every gui action; null only in frames built outside a view
public record GuiFrame(GuiCanvas canvas, Palette palette, HitRegions hits, FocusManager focus, Animator anim,
                       OverlayStack overlays, EditorSlot editors, InputCapture capture, GuiHost host,
                       Rect bounds, float mouseX, float mouseY, long nanos, float textScale, GuiActions actions) {

    public GuiFrame(GuiCanvas canvas, Palette palette, HitRegions hits, FocusManager focus, Animator anim,
                    OverlayStack overlays, EditorSlot editors, InputCapture capture, GuiHost host,
                    Rect bounds, float mouseX, float mouseY, long nanos, float textScale) {
        this(canvas, palette, hits, focus, anim, overlays, editors, capture, host, bounds, mouseX, mouseY, nanos,
                textScale, null);
    }

    // still draws, but registers no hits or focus and keeps every animation at its target: style thumbnails
    public GuiFrame inert() {
        return new GuiFrame(canvas, palette, hits.inert(), focus.inert(), anim.inert(), overlays, editors, capture,
                host, bounds, mouseX, mouseY, nanos, textScale, actions);
    }

    // the editor slot of the layer being drawn, so a field inside an overlay focuses into that overlay
    public GuiFrame withEditors(EditorSlot slot) {
        return new GuiFrame(canvas, palette, hits, focus, anim, overlays, slot, capture, host, bounds, mouseX,
                mouseY, nanos, textScale, actions);
    }
}
