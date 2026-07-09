package dev.aether.hud;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.modules.farming.FastLaneSwitchManager;
import dev.aether.modules.session.DynamicRestManager;
import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.util.Fonts;
import dev.aether.util.BpsTracker;
import dev.aether.util.ClientUtils;

/**
 * NVG-rendered macro status panel (state, timers, rest progress).
 *
 * <p>Three styles controlled by {@code AetherConfig.HUD_THEME.get()}:
 * <ul>
 *   <li>0 - Default: rounded rect with drop shadow</li>
 *   <li>1 - Module:  sharp rect, accent left bar</li>
 *   <li>2 - Sleek:   semi-transparent with state-coloured accent bar</li>
 * </ul>
 */
public class MacroHudElement extends HudElement {

    // -- Layout (at scale 1.0) -------------------------------------------------

    public static final float W        = 220f;
    private static final float PAD_H   = 10f;
    private static final float PAD_V   = 8f;
    private static final float TITLE_SZ = 12f;
    private static final float LABEL_SZ = 10f;
    private static final float ROW_H   = 14f;
    private static final float BAR_H   = 5f;
    private static final float CORNER  = 6f;

    // -- State colours ---------------------------------------------------------

    private static final int STATE_OFF         = 0xFFFF5555;
    private static final int STATE_FARMING     = 0xFF55FF55;
    private static final int STATE_METAL_DETECTING = 0xFFFFD166;
    private static final int STATE_AUTO_CARNIVAL = 0xFFFF8A5B;
    private static final int STATE_CLEANING    = 0xFFFFAA00;
    private static final int STATE_RECOVERING  = 0xFFFF5555;
    private static final int STATE_VISITING      = 0xFF55FFFF;
    private static final int STATE_AUTOSELLING   = 0xFFAA55FF;
    private static final int STATE_WARDROBE      = 0xFFFFFF55;
    private static final int STATE_EQUIPMENT     = 0xFFFFFF55;
    private static final int STATE_GEORGE        = 0xFFFF55FF;
    private static final int STATE_DROPPING_JUNK = 0xFFFFAA00;

    // -- Animated title --------------------------------------------------------

    private static final String TARGET_TITLE    = "Aether";
    private static final char[] SCRAMBLE_CHARS  = {'*', '/', '_', '\\', '|', '#', '!', '%', '&'};
    private static final int    CHAR_INTERVAL   = 90;   // ms per char
    private static final int    SCRAMBLE_MS     = 70;
    private static final int    STAY_MS         = 7000;

    private long animStart = -1;
    private int  animPhase = 0;            // 0=typing 1=staying 2=untyping

    // -- HudElement API --------------------------------------------------------

    @Override public float   getX()           { return AetherConfig.HUD_X.get(); }
    @Override public float   getY()           { return AetherConfig.HUD_Y.get(); }
    @Override public void    setX(float x)    { AetherConfig.HUD_X.set((int) x); }
    @Override public void    setY(float y)    { AetherConfig.HUD_Y.set((int) y); }
    @Override public float   getScale()       { return AetherConfig.HUD_SCALE.get(); }
    @Override public void    setScale(float s){ AetherConfig.HUD_SCALE.set(s); }
    @Override public float   getWidth()       { return W; }
    @Override public float   getHeight()      { return computeHeight(); }
    @Override public boolean isVisible()      {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        boolean inSupportedArea = ClientUtils.isSupportedHudArea(mc);
        return AetherConfig.SHOW_HUD.get() && (inSupportedArea || AetherConfig.SHOW_HUD_OUTSIDE_GARDEN.get());
    }
    @Override public String  getName()        { return "Macro HUD"; }
    @Override public void    savePosition()   { AetherConfig.save(); }

    // -- Height ----------------------------------------------------------------

    float computeHeight() {
        boolean sleek = AetherConfig.HUD_THEME.get() == 2;
        float h = PAD_V + TITLE_SZ + 4f;
        if (!sleek) h += 1f + 6f;   // separator + gap
        h += (sleek ? 5 : 6) * ROW_H;
        h += BAR_H + 4f + PAD_V;
        return h;
    }

    // -- Rendering -------------------------------------------------------------

    @Override
    protected void renderElement(NVGRenderer nvg, boolean editMode) {
        MacroState.State st = MacroStateManager.getCurrentState();
        String stateStr  = "off";
        int    stateColor = STATE_OFF;
        switch (st) {
            case FARMING     -> { stateStr = "farming";     stateColor = STATE_FARMING; }
            case METAL_DETECTING -> { stateStr = "metal detecting"; stateColor = STATE_METAL_DETECTING; }
            case AUTO_CARNIVAL -> { stateStr = "auto carnival"; stateColor = STATE_AUTO_CARNIVAL; }
            case CLEANING    -> { stateStr = "cleaning";    stateColor = STATE_CLEANING; }
            case RECOVERING  -> { stateStr = "recovering";  stateColor = STATE_RECOVERING; }
            case VISITING    -> { stateStr = "visitor";     stateColor = STATE_VISITING; }
            case AUTOSELLING   -> { stateStr = "autoselling";   stateColor = STATE_AUTOSELLING; }
            case WARDROBE      -> { stateStr = "wardrobe";      stateColor = STATE_WARDROBE; }
            case EQUIPMENT     -> { stateStr = "equipment";     stateColor = STATE_EQUIPMENT; }
            case GEORGE        -> { stateStr = "george";        stateColor = STATE_GEORGE; }
            case DROPPING_JUNK -> { stateStr = "dropping junk"; stateColor = STATE_DROPPING_JUNK; }
            default -> {}
        }

        long sessionMs     = MacroStateManager.getSessionRunningTime();
        long lifetimeMs    = MacroStateManager.getLifetimeRunningTime();
        long restTriggerMs = DynamicRestManager.getNextRestTriggerMs();
        String nextRest    = restTriggerMs <= 0 ? "---"
                           : formatTime(Math.max(0, restTriggerMs - System.currentTimeMillis()));

        float ph = computeHeight();
        boolean mod   = AetherConfig.HUD_THEME.get() == 1;
        boolean sleek = AetherConfig.HUD_THEME.get() == 2;
        int border = isDragging() ? BORDER_DRAG : isResizing() ? BORDER_RESIZE : Theme.HUD_BORDER;

        // Background
        if (sleek) {
            nvg.roundedRect(0, 0, W, ph, CORNER, Theme.withAlpha(Theme.HUD_BG, 0xCC));
            nvg.roundedRect(0, 0, 3f, ph, 2f, stateColor);
            nvg.rectOutline(0, 0, W, ph, CORNER, 1f, Theme.withAlpha(stateColor, 60));
        } else if (mod) {
            if (editMode) nvg.rect(-1, -1, W + 2, ph + 2, border);
            nvg.rect(0, 0, W, ph, Theme.HUD_BG);
            nvg.rect(0, 0, 3f, ph, Theme.HUD_ACCENT);
        } else {
            if (editMode) nvg.roundedRect(-1, -1, W + 2, ph + 2, CORNER + 1, border);
            nvg.shadow(0, 0, W, ph, CORNER, 12f, Theme.withAlpha(0xFF000000, 0.5f));
            nvg.roundedRect(0, 0, W, ph, CORNER, Theme.HUD_BG);
        }

        // Title row
        float ry = PAD_V;
        if (sleek) {
            nvg.circle(PAD_H + 4f, ry + TITLE_SZ / 2f + 1f, 4f, stateColor);
            nvg.text(Fonts.BOLD, stateStr.toUpperCase(), PAD_H + 12f, ry, TITLE_SZ, stateColor);
        } else {
            float tx = mod ? PAD_H + 5f
                           : (W - nvg.textWidth(Fonts.BOLD, TARGET_TITLE, TITLE_SZ)) / 2f;
            nvg.text(Fonts.BOLD, getAnimatedTitle(), tx, ry, TITLE_SZ, Theme.HUD_TITLE);
        }
        ry += TITLE_SZ + 4f;

        // Separator
        if (!sleek) {
            nvg.rect(PAD_H, ry, W - PAD_H * 2f, 1f, Theme.HUD_SEP);
            ry += 6f;
        }

        // Data rows
        if (!sleek) { row(nvg, ry, "macro state", stateStr, stateColor); ry += ROW_H; }
        row(nvg, ry, String.format("BPS (%d / %.1fs)", dev.aether.util.BpsTracker.getBreakCount(), dev.aether.util.BpsTracker.getActualWindowSeconds()), String.format("%.2f", dev.aether.util.BpsTracker.getBps())); ry += ROW_H;
        row(nvg, ry, "next lane", FastLaneSwitchManager.getDisplayText(),
                FastLaneSwitchManager.hasEstimate() ? Theme.HUD_VALUE : Theme.HUD_LABEL); ry += ROW_H;
        row(nvg, ry, "current session", formatTime(sessionMs));  ry += ROW_H;
        row(nvg, ry, "lifetime session", formatTime(lifetimeMs)); ry += ROW_H;
        row(nvg, ry, "next rest", nextRest); ry += ROW_H;

        // Progress bar
        long sched = DynamicRestManager.getScheduledDurationMs();
        float prog = (sched > 0 && restTriggerMs > 0)
                ? (float)(sched - Math.max(0, restTriggerMs - System.currentTimeMillis())) / sched
                : 0f;
        float bw = W - PAD_H * 2f;
        nvg.roundedRect(PAD_H, ry, bw, BAR_H, BAR_H / 2f, Theme.HUD_BAR_BG);
        float fw = bw * Math.max(0f, Math.min(1f, prog));
        if (fw > 0) nvg.roundedRect(PAD_H, ry, fw, BAR_H, BAR_H / 2f, Theme.HUD_ACCENT);
        ry += BAR_H + 4f;

        // Edit hint below bar
        if (editMode) {
            String hint = isDragging() ? "moving..."
                        : isResizing() ? "resizing..."
                        : "drag  \u2022  ctrl+drag to resize";
            nvg.textCentered(Fonts.REGULAR, hint, 0, ry + 2f, W, 12f, 9f, Theme.HUD_LABEL);
        }
    }

    // -- Helpers ---------------------------------------------------------------

    private void row(NVGRenderer nvg, float y, String label, String value) {
        row(nvg, y, label, value, Theme.HUD_VALUE);
    }

    private void row(NVGRenderer nvg, float y, String label, String value, int vc) {
        nvg.text(Fonts.REGULAR, label, PAD_H, y, LABEL_SZ, Theme.HUD_LABEL);
        nvg.textRight(Fonts.MONO, value, PAD_H, y, W - PAD_H * 2f, LABEL_SZ, vc);
    }

    private String getAnimatedTitle() {
        long now = System.currentTimeMillis();
        if (animStart < 0) { animStart = now; animPhase = 0; }
        int  n       = TARGET_TITLE.length();
        long phaseDur = (long)(n - 1) * CHAR_INTERVAL + SCRAMBLE_MS;
        long elapsed  = now - animStart;
        if (animPhase == 0 && elapsed >= phaseDur) { animPhase = 1; animStart = now; }
        else if (animPhase == 1 && elapsed >= STAY_MS)  { animPhase = 2; animStart = now; }
        else if (animPhase == 2 && elapsed >= phaseDur) { animPhase = 0; animStart = now; }
        elapsed = now - animStart;
        if (animPhase == 1) return TARGET_TITLE;
        StringBuilder sb = new StringBuilder();
        if (animPhase == 0) {
            for (int i = 0; i < n; i++) {
                long cs = (long) i * CHAR_INTERVAL;
                if (elapsed < cs) break;
                if (elapsed < cs + SCRAMBLE_MS) { sb.append(SCRAMBLE_CHARS[(int)((elapsed - cs) / 20) % SCRAMBLE_CHARS.length]); break; }
                sb.append(TARGET_TITLE.charAt(i));
            }
        } else {
            for (int i = 0; i < n; i++) {
                long cs = (long)(n - 1 - i) * CHAR_INTERVAL;
                if (elapsed < cs) sb.append(TARGET_TITLE.charAt(i));
                else if (elapsed < cs + SCRAMBLE_MS) { sb.append(SCRAMBLE_CHARS[(int)((elapsed - cs) / 20) % SCRAMBLE_CHARS.length]); break; }
                else break;
            }
        }
        return sb.toString();
    }

    private static String formatTime(long ms) {
        long s = ms / 1000, h = s / 3600, m = (s % 3600) / 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s % 60)
                     : String.format("%02d:%02d", m, s % 60);
    }
}
