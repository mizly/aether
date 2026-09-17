package dev.aether.hud;

import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.theme.Theme;

// subclasses draw at local (0,0); drag and resize live here so panels don't each repeat it
public abstract class HudElement {

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
    // ignores situational conditions (area, open screen, macro state) so the editor still lists it
    public boolean isEnabled() { return isVisible(); }
    public abstract String  getName();
    public abstract void    savePosition();

    // origin at (0,0), size width x height; the caller already applied translate and scale
    protected abstract void renderElement(NVGRenderer nvg, boolean editMode);

    // for items/entities that must draw outside the nvg frame, in screen space
    public void renderMinecraft(net.minecraft.client.gui.GuiGraphicsExtractor graphics, boolean editMode) {
        // default no-op
    }

    // Panels containing native items/entities must draw their themed surfaces before the GUI pass.
    public boolean rendersBeforeMinecraft() { return false; }

    // Some vanilla replacements queue gameplay drawing at their original HUD hook.
    public boolean rendersWithHud() { return true; }

    // second nvg frame, drawn on top of renderMinecraft output
    public void renderOverlay(NVGRenderer nvg, boolean editMode) {
        // default no-op
    }

    // -- Rendering -------------------------------------------------------------

    // edit mode renders enabled elements regardless of visibility
    public void render(NVGRenderer nvg, boolean editMode) {
        if (editMode ? !isEnabled() : !isVisible()) return;
        nvg.save();
        nvg.translate(getX(), getY());
        nvg.scale(getScale(), getScale());
        renderElement(nvg, editMode);
        if (editMode) {
            int border = isDragging() ? Theme.HUD_ACCENT : isResizing() ? Theme.HUD_WARNING : Theme.HUD_BORDER;
            nvg.rectOutline(0, 0, getWidth(), getHeight(), HudStyle.RADIUS, 1f, border);
        }
        nvg.restore();
    }

    // -- Interaction -----------------------------------------------------------

    public boolean isInteracting() { return dragging || resizing; }
    public boolean isDragging()    { return dragging; }
    public boolean isResizing()    { return resizing; }

    public boolean isHovered(double mx, double my) {
        float s = getScale();
        double lx = (mx - getX()) / s;
        double ly = (my - getY()) / s;
        return lx >= 0 && lx <= getWidth() && ly >= 0 && ly <= getHeight();
    }

    // ctrl resizes, otherwise moves
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

    // snap is the grid size in logical pixels, 0 disables it
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

    public void endDrag() {
        if (dragging || resizing) {
            dragging = false;
            resizing = false;
            savePosition();
        }
    }
}
