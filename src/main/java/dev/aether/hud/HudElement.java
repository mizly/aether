package dev.aether.hud;

import dev.aether.renderer.NVGRenderer;

/**
 * Abstract base for all NVG-rendered HUD panels.
 *
 * <p>Subclasses implement position/scale accessors (backed by {@code AetherConfig}),
 * a {@link #renderElement} method that draws at local (0,0) coordinates, and
 * {@link #savePosition()} to persist changes.</p>
 *
 * <p>Common drag/resize interaction (with optional grid snapping) is handled here
 * so it does not need to be duplicated in each panel.</p>
 */
public abstract class HudElement {

    // -- Edit-mode border tints ------------------------------------------------

    protected static final int BORDER_DRAG   = 0xFFAAAAFF;
    protected static final int BORDER_RESIZE = 0xFFFFAA00;

    // -- Per-element drag / resize state --------------------------------------

    private boolean dragging, resizing;
    private float   dragOffX, dragOffY;
    private float   resizeStart, resizeMouseX;

    // -- Abstract API ---------------------------------------------------------

    public abstract float   getX();
    public abstract float   getY();
    public abstract void    setX(float x);
    public abstract void    setY(float y);
    public abstract float   getScale();
    public abstract void    setScale(float s);
    public abstract float   getWidth();
    public abstract float   getHeight();
    public abstract boolean isVisible();
    /**
     * Whether the element is enabled in config, ignoring situational conditions
     * (area, open screen, macro state). Controls presence in the HUD editor.
     */
    public boolean isEnabled() { return isVisible(); }
    /** Short display name shown in the HUD editor. */
    public abstract String  getName();
    /** Persist position / scale changes (typically calls {@code AetherConfig.save()}). */
    public abstract void    savePosition();

    /**
     * Draw the element in local space - origin at (0, 0), size (width x height).
     * The caller has already applied translate + scale to the NVG context.
     *
     * @param editMode {@code true} when rendering inside the HUD editor
     */
    protected abstract void renderElement(NVGRenderer nvg, boolean editMode);

    /**
     * Override to render Minecraft content (items, entities) that must be drawn
     * outside the NVG frame, after {@link dev.aether.renderer.NanoVGManager#endFrame()}.
     * Coordinates must be computed in screen space using {@link #getX()},
     * {@link #getY()}, and {@link #getScale()}.
     */
    public void renderMinecraft(net.minecraft.client.gui.GuiGraphicsExtractor graphics, boolean editMode) {
        // default no-op
    }

    /**
     * Override to render NVG content that must appear on top of
     * {@link #renderMinecraft} output (e.g. item counts, text overlays).
     * Called inside a second NVG frame opened after the MC rendering pass.
     * Local-space transform is already applied by the caller.
     */
    public void renderOverlay(NVGRenderer nvg, boolean editMode) {
        // default no-op
    }

    // -- Rendering -------------------------------------------------------------

    /**
     * Applies position + scale transform, then delegates to {@link #renderElement}.
     * In edit mode {@link #isEnabled()} elements render regardless of {@link #isVisible()}.
     */
    public void render(NVGRenderer nvg, boolean editMode) {
        if (editMode ? !isEnabled() : !isVisible()) return;
        nvg.save();
        nvg.translate(getX(), getY());
        nvg.scale(getScale(), getScale());
        renderElement(nvg, editMode);
        nvg.restore();
    }

    // -- Interaction -----------------------------------------------------------

    public boolean isInteracting() { return dragging || resizing; }
    public boolean isDragging()    { return dragging; }
    public boolean isResizing()    { return resizing; }

    /** Returns {@code true} when {@code (mx, my)} is within this element's screen rect. */
    public boolean isHovered(double mx, double my) {
        float s = getScale();
        double lx = (mx - getX()) / s;
        double ly = (my - getY()) / s;
        return lx >= 0 && lx <= getWidth() && ly >= 0 && ly <= getHeight();
    }

    /**
     * Begins a drag or resize gesture.
     *
     * @param ctrl {@code true} -> resize (Ctrl held), {@code false} -> move
     */
    public void startDrag(double mx, double my, boolean ctrl) {
        if (ctrl) {
            resizing     = true;
            resizeStart  = getScale();
            resizeMouseX = (float) mx;
        } else {
            dragging = true;
            dragOffX = (float)(mx - getX());
            dragOffY = (float)(my - getY());
        }
    }

    /**
     * Updates position or scale during an active gesture.
     *
     * @param snap grid size in logical pixels - 0 disables snapping
     */
    public void drag(double mx, double my, float screenW, float screenH, int snap) {
        if (dragging) {
            float nx = (float)(mx - dragOffX);
            float ny = (float)(my - dragOffY);
            if (snap > 0) {
                nx = Math.round(nx / snap) * (float) snap;
                ny = Math.round(ny / snap) * (float) snap;
            }
            nx = Math.max(0, Math.min(screenW - getWidth() * getScale(), nx));
            ny = Math.max(0, Math.min(screenH - getHeight() * getScale(), ny));
            setX(nx);
            setY(ny);
        } else if (resizing) {
            float delta = (float)(mx - resizeMouseX);
            setScale(Math.max(0.5f, Math.min(2.5f, resizeStart + delta * 0.005f)));
        }
    }

    /** Ends the active gesture and persists position/scale. */
    public void endDrag() {
        if (dragging || resizing) {
            dragging = false;
            resizing = false;
            savePosition();
        }
    }
}
