package dev.aether.ui.gui;

// everything one frame draws with. coordinates are layout units with the view's top-left at 0,0;
// textScale is the frozen-while-dragging copy of the theme's text scale that sizes should multiply in
public record GuiFrame(GuiCanvas canvas, Palette palette, HitRegions hits, FocusManager focus, Animator anim,
                       OverlayStack overlays, EditorSlot editors, InputCapture capture, GuiHost host,
                       Rect bounds, float mouseX, float mouseY, long nanos, float textScale) {

    // still draws, but registers no hits or focus and keeps every animation at its target: style thumbnails
    public GuiFrame inert() {
        return new GuiFrame(canvas, palette, hits.inert(), focus.inert(), anim.inert(), overlays, editors, capture,
                host, bounds, mouseX, mouseY, nanos, textScale);
    }

    // the editor slot of the layer being drawn, so a field inside an overlay focuses into that overlay
    public GuiFrame withEditors(EditorSlot slot) {
        return new GuiFrame(canvas, palette, hits, focus, anim, overlays, slot, capture, host, bounds, mouseX,
                mouseY, nanos, textScale);
    }
}
