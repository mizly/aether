package dev.aether.ui.gui.skin;

import dev.aether.ui.gui.Animator;
import dev.aether.ui.gui.FocusManager;
import dev.aether.ui.gui.GuiCanvas;
import dev.aether.ui.gui.GuiClock;
import dev.aether.ui.gui.HitRegions;
import dev.aether.ui.gui.Palette;

// what skin primitives draw with: the canvas, this frame's palette, animations and time, and optionally the
// hit and focus registries. narrow on purpose, so a satellite screen can build one without a GuiView
public final class SkinContext {
    private final GuiCanvas canvas;
    private final Palette palette;
    private final Animator anim;
    private final GuiClock clock;
    private final HitRegions hits;
    private final FocusManager focus;
    private final float textScale;
    private final float pixel;
    private final float mouseX;
    private final float mouseY;

    private SkinContext(GuiCanvas canvas, Palette palette, Animator anim, GuiClock clock, HitRegions hits,
                        FocusManager focus, float textScale, float pixel, float mouseX, float mouseY) {
        this.canvas = canvas;
        this.palette = palette;
        this.anim = anim;
        this.clock = clock;
        this.hits = hits;
        this.focus = focus;
        this.textScale = textScale;
        this.pixel = pixel;
        this.mouseX = mouseX;
        this.mouseY = mouseY;
    }

    // hits and focus start inert: nothing registers until withHits / withFocus hand real ones in
    public static SkinContext of(GuiCanvas canvas, Palette palette, Animator anim, GuiClock clock) {
        return new SkinContext(canvas, palette, anim, clock, new HitRegions(canvas).inert(),
                new FocusManager(canvas).inert(), 1f, 1f, Float.NaN, Float.NaN);
    }

    public SkinContext withHits(HitRegions hits) {
        return new SkinContext(canvas, palette, anim, clock, hits, focus, textScale, pixel, mouseX, mouseY);
    }

    public SkinContext withFocus(FocusManager focus) {
        return new SkinContext(canvas, palette, anim, clock, hits, focus, textScale, pixel, mouseX, mouseY);
    }

    public SkinContext withPalette(Palette palette) {
        return new SkinContext(canvas, palette, anim, clock, hits, focus, textScale, pixel, mouseX, mouseY);
    }

    // multiplies every FontRole size, the menu's text scale setting
    public SkinContext withTextScale(float textScale) {
        return new SkinContext(canvas, palette, anim, clock, hits, focus, textScale, pixel, mouseX, mouseY);
    }

    // one device pixel in layout units (1 / ui scale), so hairlines can snap to whole pixels
    public SkinContext withPixel(float pixel) {
        return new SkinContext(canvas, palette, anim, clock, hits, focus, textScale, pixel, mouseX, mouseY);
    }

    // the pointer in root layout units, for hover help placement
    public SkinContext withPointer(float x, float y) {
        return new SkinContext(canvas, palette, anim, clock, hits, focus, textScale, pixel, x, y);
    }

    // registers nothing and animates nothing, for thumbnails and previews
    public SkinContext inert() {
        return new SkinContext(canvas, palette, anim.inert(), clock, hits.inert(), focus.inert(), textScale, pixel,
                Float.NaN, Float.NaN);
    }

    public GuiCanvas canvas() {
        return canvas;
    }

    public Palette palette() {
        return palette;
    }

    public Animator anim() {
        return anim;
    }

    public GuiClock clock() {
        return clock;
    }

    public HitRegions hits() {
        return hits;
    }

    public FocusManager focus() {
        return focus;
    }

    public float textScale() {
        return textScale;
    }

    public float pixel() {
        return pixel;
    }

    public float mouseX() {
        return mouseX;
    }

    public float mouseY() {
        return mouseY;
    }

    public boolean hasPointer() {
        return !Float.isNaN(mouseX) && !Float.isNaN(mouseY);
    }

    public long nowMs() {
        return clock.nanos() / 1_000_000L;
    }
}
