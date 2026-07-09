package dev.aether.ui;

import dev.aether.renderer.AetherBackground;
import dev.aether.renderer.AetherRenderQueue;
import dev.aether.renderer.NanoVGManager;
import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.util.Fonts;
import dev.aether.ui.util.TextInput;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Custom-styled Direct Connect screen.
 * Replaces {@link net.minecraft.client.gui.screens.DirectJoinServerScreen}
 * via {@link dev.aether.mixin.MixinDirectJoinServerScreen}.
 */
public class AetherDirectJoinScreen extends Screen {

    // -- Layout ----------------------------------------------------------------
    private static final float FIELD_W = 320f;
    private static final float FIELD_H = 32f;
    private static final float LABEL_H = 18f;
    private static final float BTN_W   = 154f;
    private static final float BTN_H   = 36f;
    private static final float BTN_GAP = 12f;
    private static final float BTN_R   = 6f;

    // -- State -----------------------------------------------------------------
    private final Screen          lastScreen;
    private final BooleanConsumer callback;
    private final ServerData      serverData;

    private final TextInput ipInput = new TextInput();

    // -- Layout cache ----------------------------------------------------------
    private float cx;
    private float titleY, ipLabelY, ipFieldY, btnsY;
    private float leftBtnX, rightBtnX;

    // -- Animation -------------------------------------------------------------
    private long lastTime;
    private final float[] hov = new float[2]; // 0 = Join, 1 = Cancel

    public AetherDirectJoinScreen(Screen lastScreen, BooleanConsumer callback, ServerData serverData) {
        super(Component.translatable("selectServer.direct"));
        this.lastScreen = lastScreen;
        this.callback   = callback;
        this.serverData = serverData;
    }

    // -- Lifecycle -------------------------------------------------------------

    @Override
    protected void init() {
        cx = width / 2f;
        lastTime = System.currentTimeMillis();

        float blockH   = 28f + 10f + LABEL_H + FIELD_H + 20f + BTN_H;
        float blockTop = (height - blockH) / 2f;

        titleY   = blockTop;
        ipLabelY = blockTop + 38f;
        ipFieldY = ipLabelY + LABEL_H;
        btnsY    = ipFieldY + FIELD_H + 20f;

        float totalBtns = BTN_W * 2f + BTN_GAP;
        leftBtnX  = cx - totalBtns / 2f;
        rightBtnX = leftBtnX + BTN_W + BTN_GAP;

        ipInput.setMaxLength(128);
        ipInput.setValue(serverData.ip);
        ipInput.setFocused(true);
    }

    // -- Render ----------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partialTick) {
        long now = System.currentTimeMillis();
        float dt = Math.min((now - lastTime) / 1000f, 0.05f);
        lastTime = now;

        float hspd = Math.min(1f, dt * 8f);
        hov[0] += ((isOverBtn(mx, my, leftBtnX)  ? 1f : 0f) - hov[0]) * hspd;
        hov[1] += ((isOverBtn(mx, my, rightBtnX) ? 1f : 0f) - hov[1]) * hspd;

        AetherRenderQueue.enqueue(() -> renderQueued(mx, my));
    }

    private void renderQueued(int mx, int my) {
        if (Minecraft.getInstance().screen != this) {
            return;
        }
        if (!NanoVGManager.isInitialized()) {
            NanoVGManager.init();
        }
        AetherBackground.INSTANCE.render(width, height, mx, my);
        NanoVGManager.beginFrame(width, height);
        NVGRenderer nvg = NanoVGManager.getRenderer();
        try {
            drawUI(nvg);
        } finally {
            NanoVGManager.endFrame();
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {}

    // -- Drawing ---------------------------------------------------------------

    private void drawUI(NVGRenderer nvg) {
        int   accent = Theme.ACCENT_PRIMARY;
        float fx     = cx - FIELD_W / 2f;

        // Title
        float tSize = 28f;
        float tW    = nvg.textWidth(Fonts.BOLD, "Direct Connect", tSize);
        nvg.text(Fonts.BOLD, "Direct Connect", cx - tW / 2f, titleY, tSize, 0xFFFFFFFF);
        nvg.rect(cx - tW / 2f, titleY + tSize + 4f, tW, 1f, Theme.withAlpha(accent, 0xBE));

        // IP field
        nvg.text(Fonts.REGULAR, "Server IP", fx, ipLabelY, 10f, Theme.withAlpha(0xFFFFFFFF, 0x80));
        nvg.roundedRect(fx, ipFieldY, FIELD_W, FIELD_H, 4f, Theme.withAlpha(0xFFFFFFFF, 0x18));
        nvg.roundedRect(fx, ipFieldY + FIELD_H - 1f, FIELD_W, 1f, 0f, Theme.withAlpha(accent, 0xCC));
        ipInput.render(nvg, fx, ipFieldY, FIELD_W, FIELD_H, 12f, 0xFFFFFFFF);

        // Buttons
        boolean canJoin = !ipInput.getValue().isBlank();
        drawBtn(nvg, "Join Server", leftBtnX,  hov[0], accent, canJoin);
        drawBtn(nvg, "Cancel",      rightBtnX, hov[1], accent, true);
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

        double mx = click.x(), my = click.y();
        if (isOverBtn((int)mx, (int)my, leftBtnX) && !ipInput.getValue().isBlank()) { doJoin();   return true; }
        if (isOverBtn((int)mx, (int)my, rightBtnX))                                 { doCancel(); return true; }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        int key = input.key();
        if (key == GLFW.GLFW_KEY_ESCAPE)                                  { doCancel(); return true; }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            if (!ipInput.getValue().isBlank()) doJoin();
            return true;
        }
        ipInput.keyPressed(key, input.modifiers());
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent input) {
        ipInput.charTyped((char) input.codepoint());
        return true;
    }

    // -- Helpers ---------------------------------------------------------------

    private boolean isOverBtn(int mx, int my, float bx) {
        return mx >= bx && mx <= bx + BTN_W && my >= btnsY && my <= btnsY + BTN_H;
    }

    private void doJoin() {
        serverData.ip = ipInput.getValue();
        callback.accept(true);
    }

    private void doCancel() { callback.accept(false); }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    public java.util.List<? extends net.minecraft.client.gui.components.events.GuiEventListener> children() {
        return java.util.List.of();
    }

}
