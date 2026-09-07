package dev.aether.hud.legacy;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.modules.farming.FastLaneSwitchManager;
import dev.aether.modules.session.DynamicRestManager;
import dev.aether.renderer.NVGRenderer;
import dev.aether.telemetry.AetherAuthService;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.util.Fonts;
import dev.aether.util.BpsTracker;

/**
 * Pre-Ryn rendering for the macro status HUD. Preserves the three theme styles
 * (Default / Module / Sleek) and the animated "Aether" title.
 */
public final class LegacyMacroHud {
    public static final float W        = 220f;
    public static final float PAD_H    = 10f;
    public static final float PAD_V    = 8f;
    public static final float TITLE_SZ = 12f;
    public static final float LABEL_SZ = 10f;
    public static final float ROW_H    = 14f;
    public static final float BAR_H    = 5f;
    public static final float CORNER   = 6f;

    private static final int STATE_OFF             = 0xFFFF5555;
    private static final int STATE_FARMING         = 0xFF55FF55;
    private static final int STATE_METAL_DETECTING = 0xFFFFD166;
    private static final int STATE_AUTO_CARNIVAL   = 0xFFFF8A5B;
    private static final int STATE_CLEANING        = 0xFFFFAA00;
    private static final int STATE_RECOVERING      = 0xFFFF5555;
    private static final int STATE_VISITING        = 0xFF55FFFF;
    private static final int STATE_AUTOSELLING     = 0xFFAA55FF;
    private static final int STATE_WARDROBE        = 0xFFFFFF55;
    private static final int STATE_EQUIPMENT       = 0xFFFFFF55;
    private static final int STATE_GEORGE          = 0xFFFF55FF;
    private static final int STATE_DROPPING_JUNK   = 0xFFFFAA00;

    private static final String TARGET_TITLE    = "Aether";
    private static final char[] SCRAMBLE_CHARS  = {'*', '/', '_', '\\', '|', '#', '!', '%', '&'};
    private static final int    CHAR_INTERVAL   = 90;
    private static final int    SCRAMBLE_MS     = 70;
    private static final int    STAY_MS         = 7000;

    private static long animStart = -1;
    private static int  animPhase = 0;

    private LegacyMacroHud() {}

    public static float computeHeight() {
        boolean sleek = AetherConfig.HUD_THEME.get() == 2;
        float h = PAD_V + TITLE_SZ + 4f;
        if (!sleek) h += 1f + 6f;
        h += (sleek ? 5 : 6) * ROW_H;
        h += BAR_H + 4f + PAD_V;
        return h;
    }

    public static void renderElement(NVGRenderer nvg, boolean isDragging, boolean isResizing, boolean editMode) {
        MacroState.State st = MacroStateManager.getCurrentState();
        String stateStr = "off";
        int    stateColor = STATE_OFF;
        switch (st) {
            case FARMING         -> { stateStr = "farming";         stateColor = STATE_FARMING; }
            case METAL_DETECTING -> { stateStr = "metal detecting"; stateColor = STATE_METAL_DETECTING; }
            case AUTO_CARNIVAL   -> { stateStr = "auto carnival";   stateColor = STATE_AUTO_CARNIVAL; }
            case CLEANING        -> { stateStr = "cleaning";        stateColor = STATE_CLEANING; }
            case RECOVERING      -> { stateStr = "recovering";      stateColor = STATE_RECOVERING; }
            case VISITING        -> { stateStr = "visitor";         stateColor = STATE_VISITING; }
            case AUTOSELLING     -> { stateStr = "autoselling";     stateColor = STATE_AUTOSELLING; }
            case WARDROBE        -> { stateStr = "wardrobe";        stateColor = STATE_WARDROBE; }
            case EQUIPMENT       -> { stateStr = "equipment";       stateColor = STATE_EQUIPMENT; }
            case GEORGE          -> { stateStr = "george";          stateColor = STATE_GEORGE; }
            case DROPPING_JUNK   -> { stateStr = "dropping junk";   stateColor = STATE_DROPPING_JUNK; }
            default -> {}
        }

        long sessionMs     = MacroStateManager.getSessionRunningTime();
        long restTriggerMs = DynamicRestManager.getNextRestTriggerMs();
        String nextRest    = restTriggerMs <= 0 ? "---"
                           : formatTime(Math.max(0, restTriggerMs - System.currentTimeMillis()));

        float ph = computeHeight();
        boolean mod   = AetherConfig.HUD_THEME.get() == 1;
        boolean sleek = AetherConfig.HUD_THEME.get() == 2;
        int border = isDragging ? Theme.HUD_ACCENT : isResizing ? Theme.HUD_WARNING : Theme.HUD_BORDER;

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

        if (!sleek) {
            nvg.rect(PAD_H, ry, W - PAD_H * 2f, 1f, Theme.HUD_SEP);
            ry += 6f;
        }

        if (!sleek) { row(nvg, ry, "macro state", stateStr, stateColor); ry += ROW_H; }
        row(nvg, ry, String.format("BPS (%d / %.1fs)", BpsTracker.getBreakCount(), BpsTracker.getActualWindowSeconds()),
                String.format("%.2f", BpsTracker.getBps())); ry += ROW_H;
        row(nvg, ry, "next lane", FastLaneSwitchManager.getDisplayText(),
                FastLaneSwitchManager.hasEstimate() ? Theme.HUD_VALUE : Theme.HUD_LABEL); ry += ROW_H;
        row(nvg, ry, "current session", formatTime(sessionMs));  ry += ROW_H;
        row(nvg, ry, "hours played", AetherAuthService.isAuthenticated()
                ? formatTime(AetherAuthService.getTotalSeconds() * 1000L)
                : "not linked"); ry += ROW_H;
        row(nvg, ry, "next rest", nextRest); ry += ROW_H;

        long sched = DynamicRestManager.getScheduledDurationMs();
        float prog = (sched > 0 && restTriggerMs > 0)
                ? (float)(sched - Math.max(0, restTriggerMs - System.currentTimeMillis())) / sched
                : 0f;
        float bw = W - PAD_H * 2f;
        nvg.roundedRect(PAD_H, ry, bw, BAR_H, BAR_H / 2f, Theme.HUD_BAR_BG);
        float fw = bw * Math.max(0f, Math.min(1f, prog));
        if (fw > 0) nvg.roundedRect(PAD_H, ry, fw, BAR_H, BAR_H / 2f, Theme.HUD_ACCENT);
        ry += BAR_H + 4f;

        if (editMode) {
            String hint = isDragging ? "moving..."
                        : isResizing ? "resizing..."
                        : "drag  •  ctrl+drag to resize";
            nvg.textCentered(Fonts.REGULAR, hint, 0, ry + 2f, W, 12f, 9f, Theme.HUD_LABEL);
        }
    }

    private static void row(NVGRenderer nvg, float y, String label, String value) {
        row(nvg, y, label, value, Theme.HUD_VALUE);
    }

    private static void row(NVGRenderer nvg, float y, String label, String value, int vc) {
        nvg.text(Fonts.REGULAR, label, PAD_H, y, LABEL_SZ, Theme.HUD_LABEL);
        nvg.textRight(Fonts.MONO, value, PAD_H, y, W - PAD_H * 2f, LABEL_SZ, vc);
    }

    private static String getAnimatedTitle() {
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
