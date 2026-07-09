package dev.aether.ui;

import dev.aether.renderer.AetherBackground;
import dev.aether.renderer.AetherRenderQueue;
import dev.aether.renderer.NanoVGManager;
import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.util.Fonts;
import dev.aether.ui.util.TextInput;
import dev.aether.util.AetherLang;
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
 * Custom-styled Add / Edit server screen.
 * Replaces {@link net.minecraft.client.gui.screens.ManageServerScreen}
 * via {@link dev.aether.mixin.MixinManageServerScreen}.
 */
public class AetherManageServerScreen extends Screen {

    // -- Layout ----------------------------------------------------------------
    private static final float FIELD_W   = 320f;
    private static final float FIELD_H   = 32f;
    private static final float LABEL_H   = 18f;
    private static final float SECTION_G = 20f;
    private static final float BTN_W     = 154f;
    private static final float BTN_H     = 36f;
    private static final float BTN_GAP   = 12f;
    private static final float BTN_R     = 6f;

    // -- State -----------------------------------------------------------------
    private final Screen          lastScreen;
    private final BooleanConsumer callback;
    private final ServerData      serverData;
    private final String          screenTitle;

    private final TextInput nameInput = new TextInput();
    private final TextInput ipInput   = new TextInput();
    private TextInput       focused   = null;

    // -- Layout cache ----------------------------------------------------------
    private float cx;
    private float titleY, nameLabelY, nameFieldY, ipLabelY, ipFieldY, btnsY;
    private float leftBtnX, rightBtnX;

    // -- Animation -------------------------------------------------------------
    private long lastTime;
    private final float[] hov = new float[2]; // 0 = Done, 1 = Cancel

    public AetherManageServerScreen(Screen lastScreen, Component title,
                                    BooleanConsumer callback, ServerData serverData) {
        super(title);
        this.lastScreen   = lastScreen;
        this.callback     = callback;
        this.serverData   = serverData;
        this.screenTitle  = title.getString();
    }

    // -- Lifecycle -------------------------------------------------------------

    @Override
    protected void init() {
        cx = width / 2f;
        lastTime = System.currentTimeMillis();

        float blockH  = 28f + 10f + LABEL_H + FIELD_H + SECTION_G + LABEL_H + FIELD_H + 20f + BTN_H;
        float blockTop = (height - blockH) / 2f;

        titleY     = blockTop;
        nameLabelY = blockTop + 38f;
        nameFieldY = nameLabelY + LABEL_H;
        ipLabelY   = nameFieldY + FIELD_H + SECTION_G;
        ipFieldY   = ipLabelY  + LABEL_H;
        btnsY      = ipFieldY  + FIELD_H + 20f;

        float totalBtns = BTN_W * 2f + BTN_GAP;
        leftBtnX  = cx - totalBtns / 2f;
        rightBtnX = leftBtnX + BTN_W + BTN_GAP;

        nameInput.setMaxLength(128);
        nameInput.setValue(serverData.name);
        ipInput.setMaxLength(128);
        ipInput.setValue(serverData.ip);

        focusField(nameInput);
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
        String titleStr = screenTitle;
        float tW    = nvg.textWidth(Fonts.BOLD, titleStr, tSize);
        nvg.text(Fonts.BOLD, titleStr, cx - tW / 2f, titleY, tSize, 0xFFFFFFFF);
        nvg.rect(cx - tW / 2f, titleY + tSize + 4f, tW, 1f, Theme.withAlpha(accent, 0xBE));

        // Server Name
        nvg.text(Fonts.REGULAR, AetherLang.localize("Server Name"), fx, nameLabelY, 10f, Theme.withAlpha(0xFFFFFFFF, 0x80));
        drawFieldBg(nvg, fx, nameFieldY, accent, nameInput.isFocused());
        nameInput.render(nvg, fx, nameFieldY, FIELD_W, FIELD_H, 12f,
                Theme.withAlpha(0xFFFFFFFF, nameInput.isFocused() ? 0xFF : 0xCC));

        // Server IP
        nvg.text(Fonts.REGULAR, AetherLang.localize("Server IP"), fx, ipLabelY, 10f, Theme.withAlpha(0xFFFFFFFF, 0x80));
        drawFieldBg(nvg, fx, ipFieldY, accent, ipInput.isFocused());
        ipInput.render(nvg, fx, ipFieldY, FIELD_W, FIELD_H, 12f,
                Theme.withAlpha(0xFFFFFFFF, ipInput.isFocused() ? 0xFF : 0xCC));

        // Buttons
        drawBtn(nvg, AetherLang.localize("Done"),   leftBtnX,  hov[0], accent);
        drawBtn(nvg, AetherLang.localize("Cancel"), rightBtnX, hov[1], accent);
    }

    private void drawFieldBg(NVGRenderer nvg, float x, float y, int accent, boolean active) {
        nvg.roundedRect(x, y, FIELD_W, FIELD_H, 4f, Theme.withAlpha(0xFFFFFFFF, active ? 0x18 : 0x10));
        nvg.roundedRect(x, y + FIELD_H - 1f, FIELD_W, 1f, 0f,
                Theme.withAlpha(accent, active ? 0xCC : 0x60));
    }

    private void drawBtn(NVGRenderer nvg, String label, float bx, float h, int accent) {
        float by      = btnsY;
        int textAlpha = (int)((0.72f + h * 0.28f) * 255);
        int bgAlpha   = (int)((0.11f + h * 0.09f) * 50);
        nvg.roundedRect(bx, by, BTN_W, BTN_H, BTN_R, Theme.withAlpha(0xFFFFFFFF, bgAlpha));
        if (h > 0.01f) {
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
        if (isOverBtn((int)mx, (int)my, leftBtnX))  { doDone();   return true; }
        if (isOverBtn((int)mx, (int)my, rightBtnX)) { doCancel(); return true; }

        float fx = cx - FIELD_W / 2f;
        if (mx >= fx && mx <= fx + FIELD_W) {
            if (my >= nameFieldY && my <= nameFieldY + FIELD_H) { focusField(nameInput); return true; }
            if (my >= ipFieldY   && my <= ipFieldY   + FIELD_H) { focusField(ipInput);   return true; }
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        int key = input.key();
        if (key == GLFW.GLFW_KEY_ESCAPE)                                  { doCancel(); return true; }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { doDone();   return true; }
        if (key == GLFW.GLFW_KEY_TAB) {
            focusField(nameInput.isFocused() ? ipInput : nameInput);
            return true;
        }
        if (focused != null) focused.keyPressed(key, input.modifiers());
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent input) {
        if (focused != null) focused.charTyped((char) input.codepoint());
        return true;
    }

    // -- Helpers ---------------------------------------------------------------

    private void focusField(TextInput field) {
        if (focused != null) focused.setFocused(false);
        focused = field;
        field.setFocused(true);
    }

    private boolean isOverBtn(int mx, int my, float bx) {
        return mx >= bx && mx <= bx + BTN_W && my >= btnsY && my <= btnsY + BTN_H;
    }

    private void doDone() {
        serverData.name = nameInput.getValue();
        serverData.ip   = ipInput.getValue();
        callback.accept(true);
    }

    private void doCancel() { callback.accept(false); }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    public java.util.List<? extends net.minecraft.client.gui.components.events.GuiEventListener> children() {
        return java.util.List.of();
    }

}
