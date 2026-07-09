package dev.aether.ui;

import dev.aether.renderer.AetherBackground;
import dev.aether.renderer.AetherRenderQueue;
import dev.aether.renderer.NanoVGManager;
import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.util.Fonts;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.Minecraft;

/**
 * Custom-styled confirmation screen (Yes / No).
 * Replaces {@link net.minecraft.client.gui.screens.ConfirmScreen}
 * via {@link dev.aether.mixin.MixinConfirmScreen}.
 */
public class AetherConfirmScreen extends Screen {

    // -- Layout ----------------------------------------------------------------
    private static final float BTN_W   = 154f;
    private static final float BTN_H   = 36f;
    private static final float BTN_GAP = 12f;
    private static final float BTN_R   = 6f;

    /** 20-tick input delay before buttons activate (matches vanilla). */
    private static final long BUTTON_DELAY_MS = 1000L;

    // -- State -----------------------------------------------------------------
    private final BooleanConsumer callback;
    private final Component         message;

    // -- Layout cache ----------------------------------------------------------
    private float cx;
    private float titleY, msgY, btnsY;
    private float leftBtnX, rightBtnX;

    // -- Animation -------------------------------------------------------------
    private long openTime;
    private long lastTime;
    private final float[] hov = new float[2]; // 0 = Yes, 1 = No

    public AetherConfirmScreen(BooleanConsumer callback, Component title, Component message) {
        super(title);
        this.callback = callback;
        this.message  = message;
    }

    // -- Lifecycle -------------------------------------------------------------

    @Override
    protected void init() {
        cx = width / 2f;
        openTime = System.currentTimeMillis();
        lastTime = openTime;

        float blockH = 28f + 12f + 14f + 28f + BTN_H;
        float blockTop = (height - blockH) / 2f;

        titleY = blockTop;
        msgY   = blockTop + 44f;
        btnsY  = msgY    + 26f;

        float totalBtns = BTN_W * 2f + BTN_GAP;
        leftBtnX  = cx - totalBtns / 2f;
        rightBtnX = leftBtnX + BTN_W + BTN_GAP;
    }

    // -- Render ----------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partialTick) {
        if (!NanoVGManager.isInitialized()) {
            NanoVGManager.init();
        }

        long now = System.currentTimeMillis();
        float dt = Math.min((now - lastTime) / 1000f, 0.05f);
        lastTime = now;

        boolean enabled = now - openTime > BUTTON_DELAY_MS;

        float hspd = Math.min(1f, dt * 8f);
        hov[0] += ((enabled && isOverBtn(mx, my, leftBtnX)  ? 1f : 0f) - hov[0]) * hspd;
        hov[1] += ((enabled && isOverBtn(mx, my, rightBtnX) ? 1f : 0f) - hov[1]) * hspd;

        AetherRenderQueue.enqueue(() -> renderQueued(mx, my, enabled));
    }

    private void renderQueued(int mx, int my, boolean enabled) {
        if (Minecraft.getInstance().screen != this) {
            return;
        }
        AetherBackground.INSTANCE.render(width, height, mx, my);
        NanoVGManager.beginFrame(width, height);
        NVGRenderer nvg = NanoVGManager.getRenderer();
        try {
            drawUI(nvg, enabled);
        } finally {
            NanoVGManager.endFrame();
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {}

    // -- Drawing ---------------------------------------------------------------

    private void drawUI(NVGRenderer nvg, boolean enabled) {
        int accent = Theme.ACCENT_PRIMARY;

        // Title
        String titleStr = title.getString();
        float tSize = 22f;
        float tW    = nvg.textWidth(Fonts.BOLD, titleStr, tSize);
        nvg.text(Fonts.BOLD, titleStr, cx - tW / 2f, titleY, tSize, 0xFFFFFFFF);
        nvg.rect(cx - tW / 2f, titleY + tSize + 4f, tW, 1f, Theme.withAlpha(accent, 0xBE));

        // Message
        String msgStr = message.getString();
        float mW = nvg.textWidth(Fonts.REGULAR, msgStr, 12f);
        nvg.text(Fonts.REGULAR, msgStr, cx - mW / 2f, msgY, 12f, Theme.withAlpha(0xFFFFFFFF, 0x80));

        // Buttons
        drawBtn(nvg, "Yes", leftBtnX,  hov[0], accent, enabled);
        drawBtn(nvg, "No",  rightBtnX, hov[1], accent, enabled);
    }

    private void drawBtn(NVGRenderer nvg, String label, float bx, float h, int accent, boolean enabled) {
        float by      = btnsY;
        int textAlpha = enabled ? (int)((0.72f + h * 0.28f) * 255) : 0x40;
        int bgAlpha   = enabled ? (int)((0.11f + h * 0.09f) * 50) : 5;
        nvg.roundedRect(bx, by, BTN_W, BTN_H, BTN_R, Theme.withAlpha(0xFFFFFFFF, bgAlpha));
        if (enabled && h > 0.01f) {
            nvg.roundedRect(bx,              by + 6f, 2f, BTN_H - 12f, 1f, Theme.withAlpha(accent, (int)(h * 210)));
            nvg.roundedRect(bx + BTN_W - 2f, by + 6f, 2f, BTN_H - 12f, 1f, Theme.withAlpha(accent, (int)(h * 210)));
        }
        nvg.textCentered(Fonts.REGULAR, label, bx, by, BTN_W, BTN_H, 13f,
                Theme.withAlpha(0xFFFFFFFF, textAlpha));
    }

    // -- Input -----------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (click.button() == 0) {
            AetherBackground.INSTANCE.addRipple((float)click.x(), (float)click.y());
        }
        if (click.button() != 0) return true;

        boolean enabled = System.currentTimeMillis() - openTime > BUTTON_DELAY_MS;
        if (!enabled) return true;

        double mx = click.x(), my = click.y();
        if (isOverBtn((int)mx, (int)my, leftBtnX))  { callback.accept(true);  return true; }
        if (isOverBtn((int)mx, (int)my, rightBtnX)) { callback.accept(false); return true; }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        int key = input.key();
        boolean enabled = System.currentTimeMillis() - openTime > BUTTON_DELAY_MS;
        if (!enabled) return true;

        if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_N) { callback.accept(false); return true; }
        if (key == GLFW.GLFW_KEY_Y)                                  { callback.accept(true);  return true; }
        return true;
    }

    // -- Helpers ---------------------------------------------------------------

    private boolean isOverBtn(int mx, int my, float bx) {
        return mx >= bx && mx <= bx + BTN_W && my >= btnsY && my <= btnsY + BTN_H;
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    public java.util.List<? extends net.minecraft.client.gui.components.events.GuiEventListener> children() {
        return java.util.List.of();
    }

}
