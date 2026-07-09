package dev.aether.ui;

import dev.aether.proxy.AetherProxyManager;
import dev.aether.proxy.AetherProxyScreen;
import dev.aether.renderer.AetherBackground;
import dev.aether.renderer.AetherRenderQueue;
import dev.aether.renderer.NanoVGManager;
import dev.aether.renderer.NVGRenderer;
import dev.aether.renderer.NVGScreen;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.util.Fonts;
import dev.aether.util.AetherLang;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * Custom NVG-rendered title screen that replaces the vanilla {@code TitleScreen}.
 *
 * <p>Injected via {@link dev.aether.mixin.MixinTitleScreen}. All animation state
 * is driven by wall-clock time ({@link System#currentTimeMillis()}).</p>
 */
public class AetherTitleScreen extends NVGScreen {


    // -- Buttons ----------------------------------------------------------------
    private static final String[] LABELS  = {"Singleplayer", "Multiplayer", "Options", "Quit"};
    private static final float    BTN_W   = 220f;
    private static final float    BTN_H   = 40f;
    private static final float    BTN_GAP = 10f;
    private static final float    BTN_R   = 6f;
    private static final float    AUTH_GAP = 12f;
    private static final float    PROXY_W = 220f;
    private static final float    PROXY_H = 34f;

    // -- Animation -------------------------------------------------------------
    private long  openTime;
    private long  lastTime;
    private final float[] hov = new float[LABELS.length];
    private float proxyHover;

    // -- Layout ----------------------------------------------------------------
    private float cx, titleY, versionY, btnsY, proxyY, sepY;

    private static final String VERSION = FabricLoader.getInstance()
            .getModContainer("aether")
            .map(c -> c.getMetadata().getVersion().getFriendlyString())
            .orElse("dev");


    public AetherTitleScreen() { super("Aether"); }

    // -- Lifecycle --------------------------------------------------------------

    @Override
    protected void initNVG() {
        cx = width / 2f;

        float totalBtns = LABELS.length * BTN_H + (LABELS.length - 1) * BTN_GAP;
        float proxyBlock = AUTH_GAP + PROXY_H;
        float blockH    = 36f + 10f + 16f + 34f + totalBtns + proxyBlock;
        float blockTop  = (height - blockH) / 2f;

        titleY   = blockTop;
        versionY = titleY + 52f;
        btnsY    = versionY + 34f;
        proxyY   = btnsY + totalBtns + AUTH_GAP;
        sepY     = height - 38f;

        openTime = System.currentTimeMillis();
        lastTime = openTime;
        proxyHover = 0f;
    }

    // -- Render ----------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partialTick) {
        if (!NanoVGManager.isInitialized()) NanoVGManager.init();

        long now = System.currentTimeMillis();
        float dtSec = Math.min((now - lastTime) / 1000f, 0.05f);
        lastTime = now;
        long elapsed = now - openTime;

        // -- Update Button Hover States ----------------------------------------
        float hspd = Math.min(1f, dtSec * 8f);
        for (int i = 0; i < LABELS.length; i++) {
            float by = btnsY + i * (BTN_H + BTN_GAP);
            float bx = cx - BTN_W / 2f;
            boolean over = mx >= bx && mx <= bx + BTN_W && my >= by && my <= by + BTN_H;
            hov[i] += ((over ? 1f : 0f) - hov[i]) * hspd;
        }
        float proxyX = cx - PROXY_W / 2f;
        boolean overProxy = mx >= proxyX && mx <= proxyX + PROXY_W && my >= proxyY && my <= proxyY + PROXY_H;
        proxyHover += ((overProxy ? 1f : 0f) - proxyHover) * hspd;

        AetherRenderQueue.enqueue(() -> renderQueued(mx, my, elapsed));
    }

    private void renderQueued(int mx, int my, long elapsed) {
        if (Minecraft.getInstance().screen != this) {
            return;
        }
        AetherBackground.INSTANCE.render(width, height, mx, my);
        NanoVGManager.beginFrame(width, height);
        NVGRenderer nvg = NanoVGManager.getRenderer();
        try {
            drawAll(nvg, elapsed);
        } finally {
            NanoVGManager.endFrame();
        }
    }

    private void drawAll(NVGRenderer nvg, long elapsed) {
        int accent = Theme.ACCENT_PRIMARY;

        // -- Mod name -----------------------------------------------------------
        float aTitle = fadeIn(elapsed, 0, 300);
        if (aTitle > 0f) {
            nvg.save();
            nvg.globalAlpha(aTitle);
            float tSize = 36f;
            float tW    = nvg.textWidth(Fonts.BOLD, "Aether", tSize);
            float tx    = cx - tW / 2f;
            nvg.text(Fonts.BOLD, "Aether", tx, titleY, tSize, 0xFFFFFFFF);
            /* nvg.glow(tx, titleY, tW, tSize, 16f, Theme.withAlpha(0xFFFFFFFF, (int)(10)), 0.66f); */
            float ulW = tW * Math.min(1f, elapsed / 600f);
            if (ulW > 0f)
                nvg.rect(tx, titleY + tSize + 5f, ulW, 1f,
                        Theme.withAlpha(accent, (int)(aTitle * 190)));
            nvg.restore();
        }

        // -- Version ------------------------------------------------------------
        float aVer = fadeIn(elapsed, 80, 250);
        if (aVer > 0f) {
            nvg.save();
            nvg.globalAlpha(aVer);
            float vW = nvg.textWidth(Fonts.REGULAR, VERSION, 11f);
            nvg.text(Fonts.REGULAR, VERSION, cx - vW / 2f, versionY, 11f,
                    Theme.withAlpha(0xFFFFFFFF, 0x50));
            nvg.restore();
        }

        // -- Buttons ------------------------------------------------------------
        for (int i = 0; i < LABELS.length; i++) {
            float aBtn = fadeIn(elapsed, 180 + i * 80, 220);
            if (aBtn <= 0f) continue;
            nvg.save();
            nvg.globalAlpha(aBtn);
            drawButton(nvg, i, accent);
            nvg.restore();
        }

        float aProxy = fadeIn(elapsed, 520, 220);
        if (aProxy > 0f) {
            nvg.save();
            nvg.globalAlpha(aProxy);
            drawProxyButton(nvg, accent);
            nvg.restore();
        }

        // -- Bottom separator + username ----------------------------------------
        float aBtm = fadeIn(elapsed, 460, 250);
        if (aBtm > 0f) {
            nvg.save();
            nvg.globalAlpha(aBtm);
            nvg.rect(0, sepY, width, 1f, Theme.withAlpha(accent, 0x16));
            String user = AetherLang.localize("Logged in as ") + Minecraft.getInstance().getUser().getName();
            nvg.text(Fonts.REGULAR, user, 14f, sepY + (height - sepY - 11f) / 2f, 11f,
                    Theme.withAlpha(0xFFFFFFFF, 0x40));
            nvg.restore();
        }
    }

    private void drawButton(NVGRenderer nvg, int i, int accent) {
        float by      = btnsY + i * (BTN_H + BTN_GAP);
        float bx      = cx - BTN_W / 2f;
        float h       = hov[i];
        int   bgAlpha = (int)((0.11f + h * 0.09f) * 50);
        nvg.roundedRect(bx, by, BTN_W, BTN_H, BTN_R, Theme.withAlpha(0xFFFFFFFF, bgAlpha));
        if (h > 0.01f) {
            nvg.roundedRect(bx, by + 6f, 2f, BTN_H - 12f, 1f,
                    Theme.withAlpha(accent, (int) (h * 210)));
            nvg.roundedRect(bx + BTN_W - 2f, by + 6f, 2f, BTN_H - 12f, 1f,
                    Theme.withAlpha(accent, (int)(h * 210)));
        }

        nvg.textCentered(Fonts.REGULAR, LABELS[i], bx, by, BTN_W, BTN_H, 13f,
                Theme.withAlpha(0xFFFFFFFF, (int)((0.72f + h * 0.28f) * 255)));
    }

    private void drawProxyButton(NVGRenderer nvg, int accent) {
        float bx = cx - PROXY_W / 2f;
        int bgAlpha = (int)((0.10f + proxyHover * 0.08f) * 50);
        nvg.roundedRect(bx, proxyY, PROXY_W, PROXY_H, BTN_R, Theme.withAlpha(0xFFFFFFFF, bgAlpha));
        if (proxyHover > 0.01f) {
            nvg.roundedRect(bx, proxyY + 5f, 2f, PROXY_H - 10f, 1f,
                    Theme.withAlpha(accent, (int) (proxyHover * 210)));
            nvg.roundedRect(bx + PROXY_W - 2f, proxyY + 5f, 2f, PROXY_H - 10f, 1f,
                    Theme.withAlpha(accent, (int) (proxyHover * 210)));
        }
        nvg.textCentered(Fonts.REGULAR, AetherProxyManager.selectedStatus(), bx, proxyY, PROXY_W, PROXY_H, 12f,
                Theme.withAlpha(0xFFFFFFFF, (int) ((0.72f + proxyHover * 0.28f) * 255)));
    }



    // -- Input -----------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (click.button() == 0) {
            AetherBackground.INSTANCE.addRipple((float) click.x(), (float) click.y());
        }

        if (click.button() != 0) return true;
        double mx = click.x(), my = click.y();
        for (int i = 0; i < LABELS.length; i++) {
            float by = btnsY + i * (BTN_H + BTN_GAP);
            float bx = cx - BTN_W / 2f;
            if (mx >= bx && mx <= bx + BTN_W && my >= by && my <= by + BTN_H) {
                handleClick(i);
                return true;
            }
        }
        float proxyX = cx - PROXY_W / 2f;
        if (mx >= proxyX && mx <= proxyX + PROXY_W && my >= proxyY && my <= proxyY + PROXY_H) {
            Minecraft.getInstance().setScreen(new AetherProxyScreen(this));
            return true;
        }
        return true;
    }


    private void handleClick(int i) {
        Minecraft mc = Minecraft.getInstance();
        switch (i) {
            case 0 -> mc.setScreen(new SelectWorldScreen(this));
            case 1 -> mc.setScreen(new JoinMultiplayerScreen(this));
            case 2 -> mc.setScreen(new OptionsScreen(this, mc.options, false));
            case 3 -> mc.stop();
        }
    }

    // -- Helpers ----------------------------------------------------------------

    private static float fadeIn(long elapsed, long startMs, long durMs) {
        long t = elapsed - startMs;
        if (t <= 0L)    return 0f;
        if (t >= durMs) return 1f;
        return t / (float) durMs;
    }

    @Override public boolean isPauseScreen() { return false; }
}

