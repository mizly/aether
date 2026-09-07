package dev.aether.hud;

import dev.aether.renderer.AetherRenderQueue;
import dev.aether.ui.util.Fonts;
import dev.aether.renderer.NVGRenderer;
import dev.aether.renderer.NanoVGManager;
import dev.aether.ui.theme.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * NVG-based HUD layout editor.
 *
 * <p>Displayed when the user clicks "Edit HUD Layout" in the Visuals -> HUD settings.
 * All HUD elements are rendered in edit mode within a single NVG frame.
 * A grid overlay (togglable) provides visual snap feedback.</p>
 *
 * <ul>
 *   <li><b>Drag</b> - reposition a panel</li>
 *   <li><b>Ctrl/Shift + Drag</b> - resize (scale) a panel (Shift is the reliable modifier on macOS)</li>
 *   <li><b>Snap toggle</b> - snaps drag positions to a {@value #GRID_PX}px grid</li>
 *   <li><b>ESC / INSERT</b> - close</li>
 * </ul>
 */
public class HudEditScreen extends Screen {

    // -- Constants -------------------------------------------------------------

    private static final int GRID_PX    = 10;
    private static final int TOOLBAR_H  = 44;
    private static final float BTN_W    = 110f;
    private static final float BTN_H    = 28f;

    // -- State -----------------------------------------------------------------

    private boolean snapToGrid = true;
    private HudElement activeElement = null;

    /** Mouse coords tracked each frame for button hover detection. */
    private double mouseX, mouseY;

    /** Toolbar button X positions - written during render. */
    private float btnSnapX, btnDoneX, btnBaseY;

    // -- Constructor -----------------------------------------------------------

    public HudEditScreen() {
        super(Component.literal("HUD Editor"));
    }

    @Override public boolean isPauseScreen() { return false; }

    // -- Rendering -------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        mouseX = mx; mouseY = my;
        if (dev.aether.modules.visuals.StreamerModeManager.isEnabled()) return;

        for (HudElement element : HudRegistry.ELEMENTS) {
            if (element.isEnabled()) element.renderMinecraft(g, true);
        }
        AetherRenderQueue.enqueueBeforeGui(() -> renderQueued(true));
        AetherRenderQueue.enqueue(this::renderQueued);
    }

    private void renderQueued() {
        renderQueued(false);
    }

    private void renderQueued(boolean background) {
        if (dev.aether.modules.visuals.StreamerModeManager.isEnabled()) return;
        if (Minecraft.getInstance().screen != this) {
            return;
        }
        if (!NanoVGManager.isInitialized()) NanoVGManager.init();
        NanoVGManager.beginFrame(width, height);
        NVGRenderer nvg = NanoVGManager.getRenderer();
        try {
            if (background) renderEditorBackground(nvg);
            else renderEditor(nvg);
        } finally {
            NanoVGManager.endFrame();
        }
    }

    /** Suppress MC's built-in background so the game world stays visible. */
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {}

    private void renderEditorBackground(NVGRenderer nvg) {
        // Semi-transparent dim
        nvg.rect(0, 0, width, height, Theme.withAlpha(0xFF000000, 0x55));

        // Grid
        if (snapToGrid) renderGrid(nvg);

        // All HUD elements (edit mode = true)
        HudRegistry.renderEditMode(nvg);
    }

    private void renderEditor(NVGRenderer nvg) {
        for (HudElement element : HudRegistry.ELEMENTS) {
            if (!element.isEnabled()) continue;
            nvg.save();
            nvg.translate(element.getX(), element.getY());
            nvg.scale(element.getScale(), element.getScale());
            element.renderOverlay(nvg, true);
            nvg.restore();
        }

        // Element name labels above each panel
        renderElementLabels(nvg);

        // Toolbar
        renderToolbar(nvg);
    }

    private void renderGrid(NVGRenderer nvg) {
        int minor = Theme.withAlpha(0xFFFFFFFF, 0x0C);
        int major = Theme.withAlpha(0xFFFFFFFF, 0x1A);
        for (int x = 0; x < width; x += GRID_PX) {
            nvg.rect(x, 0, 1, height, (x % 50 == 0) ? major : minor);
        }
        for (int y = 0; y < height - TOOLBAR_H; y += GRID_PX) {
            nvg.rect(0, y, width, 1, (y % 50 == 0) ? major : minor);
        }
    }

    /** Draws a small pill label above each element for easy identification. */
    private void renderElementLabels(NVGRenderer nvg) {
        for (HudElement e : HudRegistry.ELEMENTS) {
            if (!e.isEnabled()) continue;
            float s   = e.getScale();
            float ex  = e.getX();
            float ey  = e.getY();
            float ew  = e.getWidth() * s;
            String lbl = e.getName();

            float lw = nvg.textWidth(Fonts.REGULAR, lbl, 9f) + 10f;
            float lx = ex + (ew - lw) / 2f;
            float ly = ey - 18f;
            if (ly < 4f) ly = ey + e.getHeight() * s + 4f;

            int pillColor = e.isInteracting()
                    ? HudStyle.alpha(Theme.HUD_ACCENT, 0.8f)
                    : HudStyle.alpha(Theme.HUD_BG, 0.8f);
            nvg.roundedRect(lx, ly, lw, 14f, 4f, pillColor);
            nvg.textCentered(Fonts.REGULAR, lbl, lx, ly, lw, 14f, 9f, Theme.HUD_TITLE);
        }
    }

    private void renderToolbar(NVGRenderer nvg) {
        float tbY = height - TOOLBAR_H;

        // Toolbar background
        nvg.rect(0, tbY, width, TOOLBAR_H, Theme.withAlpha(Theme.BG_SECONDARY, 0xF0));
        nvg.rect(0, tbY, width, 1f, Theme.BORDER_DEFAULT);

        // Center hint
        nvg.textCentered(Fonts.REGULAR,
                "Drag to move  \u2022  Ctrl/Shift+Drag to scale  \u2022  Toggle Snap for grid alignment",
                0, tbY, width, TOOLBAR_H, 10f, Theme.TEXT_SECONDARY);

        float margin = 14f;
        btnBaseY  = tbY + (TOOLBAR_H - BTN_H) / 2f;
        btnDoneX  = width - margin - BTN_W;
        btnSnapX  = btnDoneX - margin - BTN_W;

        // Snap button
        boolean snapOn  = snapToGrid;
        boolean snapHov = hov(btnSnapX, btnBaseY, BTN_W, BTN_H);
        nvg.roundedRect(btnSnapX, btnBaseY, BTN_W, BTN_H, 5f,
                snapHov ? Theme.BG_HOVER : Theme.BG_FIELD);
        nvg.rectOutline(btnSnapX, btnBaseY, BTN_W, BTN_H, 5f, 1f,
                snapOn ? Theme.ACCENT_PRIMARY : Theme.BORDER_DEFAULT);
        nvg.textCentered(Fonts.REGULAR,
                "Snap: " + (snapOn ? "ON" : "OFF"),
                btnSnapX, btnBaseY, BTN_W, BTN_H, 11f,
                snapOn ? Theme.ACCENT_PRIMARY : Theme.TEXT_SECONDARY);

        // Done button
        boolean doneHov = hov(btnDoneX, btnBaseY, BTN_W, BTN_H);
        int doneColor = doneHov ? Theme.ACCENT_SECONDARY : Theme.ACCENT_PRIMARY;
        nvg.roundedRect(btnDoneX, btnBaseY, BTN_W, BTN_H, 5f, doneColor);
        nvg.textCentered(Fonts.BOLD, "Done", btnDoneX, btnBaseY, BTN_W, BTN_H, 11f, 0xFFFFFFFF);
    }

    private boolean hov(float x, float y, float w, float h) {
        return mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
    }

    // -- Input -----------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        double mx = click.x(), my = click.y();
        mouseX = mx; mouseY = my;

        int button = click.button();
        // Toolbar buttons are left-click only.
        if (button == 0) {
            if (hov(btnDoneX, btnBaseY, BTN_W, BTN_H)) { onClose(); return true; }
            if (hov(btnSnapX, btnBaseY, BTN_W, BTN_H)) { snapToGrid = !snapToGrid; return true; }
        }

        // Resize = Ctrl/Shift + drag. On macOS Ctrl+left-click arrives as a right-click
        // (button 1), so accept button 1 too whenever a resize modifier is held.
        boolean resizeMod = (click.modifiers() & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SHIFT)) != 0;
        boolean move   = button == 0 && !resizeMod;
        boolean resize = resizeMod && (button == 0 || button == 1);
        if (move || resize) {
            for (HudElement e : HudRegistry.ELEMENTS) {
                if (!e.isEnabled()) continue;
                if (e.isHovered(mx, my)) {
                    activeElement = e;
                    e.startDrag(mx, my, resize);
                    return true;
                }
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double dx, double dy) {
        mouseX = click.x(); mouseY = click.y();
        // Gate on an active gesture, not the button: a macOS resize drag reports button 1.
        if (activeElement != null) {
            var win = Minecraft.getInstance().getWindow();
            activeElement.drag(click.x(), click.y(),
                    win.getGuiScaledWidth(), win.getGuiScaledHeight(),
                    snapToGrid ? GRID_PX : 0);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        if (activeElement != null) {
            activeElement.endDrag();
            activeElement = null;
        }
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        int key = input.key();
        if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_INSERT) {
            onClose();
            return true;
        }
        // G - toggle grid snapping with a keyboard shortcut
        if (key == GLFW.GLFW_KEY_G) { snapToGrid = !snapToGrid; return true; }
        return false;
    }
}
