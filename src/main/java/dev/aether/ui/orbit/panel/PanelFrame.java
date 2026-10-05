package dev.aether.ui.orbit.panel;

import dev.aether.ui.gui.Animator;
import dev.aether.ui.gui.FocusManager;
import dev.aether.ui.gui.GuiCanvas;
import dev.aether.ui.gui.HitRegions;
import dev.aether.ui.gui.Palette;
import dev.aether.ui.gui.Rect;

// everything one aurora frame draws with; an inert frame registers no input and animates nothing
public record PanelFrame(GuiCanvas canvas, HitRegions hits, FocusManager focus, Animator anim, Palette palette,
                          Rect viewport, float mouseX, float mouseY, long nanos, PanelHost host, boolean frozen) {

    public PanelFrame inert() {
        return frozen ? this : new PanelFrame(canvas, hits.inert(), focus.inert(), anim.inert(), palette, viewport,
                -1f, -1f, nanos, host, true);
    }

    public float seconds() {
        return nanos / 1_000_000_000f;
    }
}
