package dev.aether.ui;

import dev.aether.renderer.AetherBackground;
import dev.aether.renderer.AetherRenderQueue;
import dev.aether.renderer.NanoVGManager;
import dev.aether.renderer.NVGRenderer;
import dev.aether.proxy.AetherProxyManager;
import dev.aether.proxy.AetherProxyScreen;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.util.Fonts;
import dev.aether.util.AetherLang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DirectJoinServerScreen;
import net.minecraft.client.gui.screens.ManageServerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

/**
 * Custom-styled multiplayer server list screen.
 * Fully replaces {@link net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen}
 * via {@link dev.aether.mixin.MixinJoinMultiplayerScreen}.
 */
public class AetherMultiplayerScreen extends Screen {

    // -- Layout ----------------------------------------------------------------
    private static final float HEADER_H   = 52f;
    private static final float ENTRY_H    = 50f;
    private static final float ENTRY_PAD  = 8f;
    private static final float BTN_W      = 200f;
    private static final float BTN_H      = 38f;
    private static final float BTN_GAP    = 8f;
    private static final float BTN_R      = 6f;
    private static final float PROXY_W    = 180f;
    private static final float PROXY_H    = 28f;

    // -- Button labels - two columns -------------------------------------------
    private static final String[] LEFT  = {"Join Server",  "Add Server",      "Direct Connect"};
    private static final String[] RIGHT = {"Edit Server",  "Delete Server",   "Back"};

    private final float[] leftHov  = new float[LEFT.length];
    private final float[] rightHov = new float[RIGHT.length];
    private float proxyHover;

    // -- State -----------------------------------------------------------------
    private final Screen lastScreen;
    private ServerList servers;
    private int selectedIndex = -1;
    private float scrollOffset = 0f;

    // -- Layout cache ----------------------------------------------------------
    private float listX, listY, listW, listH;
    private float leftBtnX, rightBtnX, btnStartY;
    private float proxyX, proxyY;

    // -- Animation -------------------------------------------------------------
    private long lastTime;
    private long openTime;

    public AetherMultiplayerScreen(Screen lastScreen) {
        super(Component.literal(AetherLang.localize("Multiplayer")));
        this.lastScreen = lastScreen;
    }

    // -- Lifecycle -------------------------------------------------------------

    @Override
    protected void init() {
        servers = new ServerList(minecraft);
        servers.load();
        selectedIndex = -1;
        scrollOffset = 0f;

        openTime = System.currentTimeMillis();
        lastTime = openTime;

        float footerH = BTN_H * LEFT.length + BTN_GAP * (LEFT.length - 1) + 24f;
        listX = 20f;
        listY = HEADER_H;
        listW = width - 40f;
        listH = height - HEADER_H - footerH;

        float colGap    = 12f;
        float totalBtns = BTN_W * 2 + colGap;
        leftBtnX  = (width - totalBtns) / 2f;
        rightBtnX = leftBtnX + BTN_W + colGap;
        btnStartY = height - footerH + 12f;
        proxyX = width - PROXY_W - 20f;
        proxyY = 12f;
        proxyHover = 0f;
    }

    // -- Render ----------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partialTick) {
        long now = System.currentTimeMillis();
        float dt = Math.min((now - lastTime) / 1000f, 0.05f);
        lastTime = now;
        long elapsed = now - openTime;

        // Hover update
        float hspd = Math.min(1f, dt * 8f);
        for (int i = 0; i < LEFT.length; i++) {
            leftHov[i]  += (isOverBtn(mx, my, leftBtnX,  i) ? 1f : 0f - leftHov[i])  * hspd;
            rightHov[i] += (isOverBtn(mx, my, rightBtnX, i) ? 1f : 0f - rightHov[i]) * hspd;
        }
        boolean overProxy = mx >= proxyX && mx <= proxyX + PROXY_W && my >= proxyY && my <= proxyY + PROXY_H;
        proxyHover += ((overProxy ? 1f : 0f) - proxyHover) * hspd;

        AetherRenderQueue.enqueue(() -> renderQueued(mx, my, elapsed));
    }

    private void renderQueued(int mx, int my, long elapsed) {
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
            drawTitle(nvg, elapsed);
            drawServerList(nvg, mx, my);
            drawButtons(nvg);
            drawProxyButton(nvg);
        } finally {
            NanoVGManager.endFrame();
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {
        // Suppressed - AetherBackground handles it.
    }

    // -- Drawing ---------------------------------------------------------------

    private void drawTitle(NVGRenderer nvg, long elapsed) {
        float alpha = fadeIn(elapsed, 0, 300);
        if (alpha <= 0f) return;
        nvg.save();
        nvg.globalAlpha(alpha);
        int accent = Theme.ACCENT_PRIMARY;

        float tSize = 36f;
        float tW    = nvg.textWidth(Fonts.BOLD, "Multiplayer", tSize);
        float tx    = (width - tW) / 2f;
        float ty    = (HEADER_H - tSize) / 2f;
        nvg.text(Fonts.BOLD, "Multiplayer", tx, ty, tSize, 0xFFFFFFFF);

        float ulW = tW * Math.min(1f, elapsed / 600f);
        if (ulW > 0f)
            nvg.rect(tx, ty + tSize + 5f, ulW, 1f, Theme.withAlpha(accent, (int)(alpha * 190)));
        nvg.restore();
    }

    private void drawServerList(NVGRenderer nvg, int mx, int my) {
        int count = servers.size();
        if (count == 0) {
            float msgW = nvg.textWidth(Fonts.REGULAR, "No servers saved.", 12f);
            nvg.text(Fonts.REGULAR, "No servers saved.",
                    (width - msgW) / 2f, listY + listH / 2f - 6f,
                    12f, Theme.withAlpha(0xFFFFFFFF, 0x50));
            return;
        }

        // Clip to list area
        nvg.save();
        nvg.scissor(listX, listY, listW, listH);

        float rowH  = ENTRY_H + ENTRY_PAD;
        float totalH = count * rowH - ENTRY_PAD;
        float maxScroll = Math.max(0f, totalH - listH);
        scrollOffset = Math.max(0f, Math.min(scrollOffset, maxScroll));

        int accent = Theme.ACCENT_PRIMARY;

        for (int i = 0; i < count; i++) {
            ServerData s = servers.get(i);
            float ey = listY + i * rowH - scrollOffset;
            if (ey + ENTRY_H < listY || ey > listY + listH) continue;

            boolean hovered  = mx >= listX && mx <= listX + listW && my >= ey && my <= ey + ENTRY_H;
            boolean selected = i == selectedIndex;

            // Row background
            int bgAlpha = selected ? 0x28 : (hovered ? 0x14 : 0x0A);
            nvg.roundedRect(listX, ey, listW, ENTRY_H, 4f,
                    Theme.withAlpha(0xFFFFFFFF, bgAlpha));

            // Left accent bar when selected
            if (selected)
                nvg.roundedRect(listX, ey + 8f, 2f, ENTRY_H - 16f, 1f,
                        Theme.withAlpha(accent, 210));

            // Server name
            nvg.text(Fonts.BOLD, s.name,
                    listX + 14f, ey + 9f, 13f,
                    Theme.withAlpha(0xFFFFFFFF, selected ? 255 : 204));

            // IP
            nvg.text(Fonts.REGULAR, s.ip,
                    listX + 14f, ey + 26f, 11f,
                    Theme.withAlpha(0xFFFFFFFF, 0x55));
        }

        nvg.restore();
    }

    private void drawButtons(NVGRenderer nvg) {
        int accent = Theme.ACCENT_PRIMARY;
        boolean hasSel = selectedIndex >= 0;

        for (int i = 0; i < LEFT.length; i++) {
            boolean enabled = isLeftEnabled(i, hasSel);
            drawBtn(nvg, LEFT[i],  leftBtnX,  i, leftHov[i],  enabled, accent);
        }
        for (int i = 0; i < RIGHT.length; i++) {
            boolean enabled = isRightEnabled(i, hasSel);
            drawBtn(nvg, RIGHT[i], rightBtnX, i, rightHov[i], enabled, accent);
        }
    }

    private void drawProxyButton(NVGRenderer nvg) {
        int accent = Theme.ACCENT_PRIMARY;
        int bgAlpha = (int)((0.11f + proxyHover * 0.09f) * 50);
        nvg.roundedRect(proxyX, proxyY, PROXY_W, PROXY_H, BTN_R, Theme.withAlpha(0xFFFFFFFF, bgAlpha));
        if (proxyHover > 0.01f) {
            nvg.roundedRect(proxyX, proxyY + 5f, 2f, PROXY_H - 10f, 1f,
                    Theme.withAlpha(accent, (int)(proxyHover * 210)));
            nvg.roundedRect(proxyX + PROXY_W - 2f, proxyY + 5f, 2f, PROXY_H - 10f, 1f,
                    Theme.withAlpha(accent, (int)(proxyHover * 210)));
        }
        nvg.textCentered(Fonts.REGULAR, AetherProxyManager.selectedStatus(), proxyX, proxyY, PROXY_W, PROXY_H, 12f,
                Theme.withAlpha(0xFFFFFFFF, (int)((0.72f + proxyHover * 0.28f) * 255)));
    }

    private void drawBtn(NVGRenderer nvg, String label, float bx, int row,
                         float hov, boolean enabled, int accent) {
        float by = btnStartY + row * (BTN_H + BTN_GAP);
        int textAlpha = enabled ? (int)((0.72f + hov * 0.28f) * 255) : 0x40;
        int bgAlpha   = (int)((0.11f + hov * 0.09f) * 50);

        nvg.roundedRect(bx, by, BTN_W, BTN_H, BTN_R,
                Theme.withAlpha(0xFFFFFFFF, bgAlpha));

        if (enabled && hov > 0.01f) {
            nvg.roundedRect(bx, by + 6f, 2f, BTN_H - 12f, 1f,
                    Theme.withAlpha(accent, (int)(hov * 210)));
            nvg.roundedRect(bx + BTN_W - 2f, by + 6f, 2f, BTN_H - 12f, 1f,
                    Theme.withAlpha(accent, (int)(hov * 210)));
        }

        nvg.textCentered(Fonts.REGULAR, label, bx, by, BTN_W, BTN_H, 13f,
                Theme.withAlpha(0xFFFFFFFF, textAlpha));
    }

    // -- Input -----------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (click.button() == 0) {
            AetherBackground.INSTANCE.addRipple((float) click.x(), (float) click.y());
        }
        if (click.button() != 0) return true;

        double mx = click.x(), my = click.y();

        if (mx >= proxyX && mx <= proxyX + PROXY_W && my >= proxyY && my <= proxyY + PROXY_H) {
            minecraft.setScreen(new AetherProxyScreen(this));
            return true;
        }

        // Server list click / double-click
        float rowH = ENTRY_H + ENTRY_PAD;
        if (mx >= listX && mx <= listX + listW && my >= listY && my <= listY + listH) {
            int idx = (int)((my - listY + scrollOffset) / rowH);
            if (idx >= 0 && idx < servers.size()) {
                if (idx == selectedIndex && doubled) {
                    doJoin();
                } else {
                    selectedIndex = idx;
                }
                return true;
            }
        }

        // Left buttons
        for (int i = 0; i < LEFT.length; i++) {
            if (isOverBtn((int)mx, (int)my, leftBtnX, i) && isLeftEnabled(i, selectedIndex >= 0)) {
                handleLeft(i);
                return true;
            }
        }
        // Right buttons
        for (int i = 0; i < RIGHT.length; i++) {
            if (isOverBtn((int)mx, (int)my, rightBtnX, i) && isRightEnabled(i, selectedIndex >= 0)) {
                handleRight(i);
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (mx >= listX && mx <= listX + listW && my >= listY && my <= listY + listH) {
            scrollOffset = Math.max(0f, scrollOffset - (float)(scrollY * 16f));
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    // -- Button actions --------------------------------------------------------

    private void handleLeft(int i) {
        switch (i) {
            case 0 -> doJoin();
            case 1 -> doAdd();
            case 2 -> doDirectConnect();
        }
    }

    private void handleRight(int i) {
        switch (i) {
            case 0 -> doEdit();
            case 1 -> doDelete();
            case 2 -> minecraft.setScreen(lastScreen);
        }
    }

    private void doJoin() {
        if (selectedIndex < 0 || selectedIndex >= servers.size()) return;
        ServerData s = servers.get(selectedIndex);
        ConnectScreen.startConnecting(this, minecraft,
                ServerAddress.parseString(s.ip), s, false, null);
    }

    private void doAdd() {
        ServerData blank = new ServerData("", "", ServerData.Type.OTHER);
        minecraft.setScreen(new ManageServerScreen(this,
                Component.translatable("addServer.title"),
                accepted -> {
                    if (accepted) { servers.add(blank, false); servers.save(); }
                    minecraft.setScreen(this);
                    init();
                }, blank));
    }

    private void doEdit() {
        if (selectedIndex < 0 || selectedIndex >= servers.size()) return;
        ServerData s = servers.get(selectedIndex);
        minecraft.setScreen(new ManageServerScreen(this,
                Component.translatable("editServer.title"),
                accepted -> {
                    if (accepted) servers.save();
                    minecraft.setScreen(this);
                    init();
                }, s));
    }

    private void doDelete() {
        if (selectedIndex < 0 || selectedIndex >= servers.size()) return;
        ServerData s = servers.get(selectedIndex);
        minecraft.setScreen(new ConfirmScreen(
                accepted -> {
                    if (accepted) {
                        servers.remove(s);
                        servers.save();
                        selectedIndex = -1;
                    }
                    minecraft.setScreen(this);
                    init();
                },
                Component.translatable("selectServer.deleteQuestion"),
                Component.literal(s.name)));
    }

    private void doDirectConnect() {
        ServerData tmp = new ServerData("", "", ServerData.Type.OTHER);
        minecraft.setScreen(new DirectJoinServerScreen(this,
                accepted -> {
                    if (accepted)
                        ConnectScreen.startConnecting(this, minecraft,
                                ServerAddress.parseString(tmp.ip), tmp, false, null);
                    else
                        minecraft.setScreen(this);
                }, tmp));
    }

    // -- Helpers ---------------------------------------------------------------

    private boolean isLeftEnabled(int i, boolean hasSel) {
        return switch (i) {
            case 0 -> hasSel; // Join
            default -> true;
        };
    }

    private boolean isRightEnabled(int i, boolean hasSel) {
        return switch (i) {
            case 0, 1 -> hasSel; // Edit, Delete
            default -> true;
        };
    }

    private boolean isOverBtn(int mx, int my, float bx, int row) {
        float by = btnStartY + row * (BTN_H + BTN_GAP);
        return mx >= bx && mx <= bx + BTN_W && my >= by && my <= by + BTN_H;
    }

    private static float fadeIn(long elapsed, long startMs, long durMs) {
        long t = elapsed - startMs;
        if (t <= 0L) return 0f;
        if (t >= durMs) return 1f;
        return t / (float) durMs;
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    public java.util.List<? extends net.minecraft.client.gui.components.events.GuiEventListener> children() {
        return java.util.List.of();
    }
}
