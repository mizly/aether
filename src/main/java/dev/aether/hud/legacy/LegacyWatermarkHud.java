package dev.aether.hud.legacy;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.modules.misc.AutoCarnivalManager;
import dev.aether.modules.profit.ProfitManager;
import dev.aether.modules.session.DailyFarmTimeTracker;
import dev.aether.modules.session.DynamicRestManager;
import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.util.Fonts;
import dev.aether.util.BpsTracker;
import dev.aether.util.PingTracker;
import net.minecraft.client.Minecraft;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Pre-Ryn rendering for the Watermark HUD. Preserves the two-phase width
 * animation and the icon-per-info-row look.
 */
public final class LegacyWatermarkHud {
    public static final String NAME    = "Aether";
    public static final String VERSION = "DEBUG";

    public static final float PAD_H     = 10f;
    public static final float PAD_V     = 6f;
    public static final float NAME_SZ   = 14f;
    public static final float VER_SZ    = 8f;
    public static final float INFO_SZ   = 10f;
    public static final float CORNER    = 5f;
    public static final float ICON_SZ   = 12f;
    public static final float ICON_GAP  = 3f;
    public static final float SEP       = 10f;
    public static final float BADGE_PAD = 5f;
    public static final float BADGE_R   = 3f;
    public static final float SHRINK_SPEED_MULT = 0.5f;
    public static final float GROW_SPEED_MULT   = 0.3f;

    public static final float WIDTH  = PAD_H * 2f + 200f;
    public static final float HEIGHT = PAD_V * 2f + NAME_SZ;

    private static final int BPS_GREEN  = 0xFF8AFFA0;
    private static final int BPS_YELLOW = 0xFFFFE08A;
    private static final int BPS_ORANGE = 0xFFFFBB88;
    private static final int BPS_RED    = 0xFFFF8A8A;

    private static final String LOGO_ICON        = "/assets/aether/icons/logo.svg";
    private static final String PERSON_ICON      = "/assets/aether/icons/person.svg";
    private static final String PERFORMANCE_ICON = "/assets/aether/icons/performance.svg";
    private static final String SIGNAL_ICON      = "/assets/aether/icons/signal.svg";
    private static final String CLOCK_ICON       = "/assets/aether/icons/clock.svg";
    private static final String REFRESH_ICON     = "/assets/aether/icons/refresh.svg";
    private static final String CASH_ICON        = "/assets/aether/icons/cash.svg";
    private static final String CALENDAR_ICON    = "/assets/aether/icons/calendar.svg";

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static float   animW       = -1f;
    private static boolean renderMacro = false;
    private static int     animStep    = 0;

    private LegacyWatermarkHud() {}

    public static void renderElement(NVGRenderer nvg, boolean isDragging, boolean isResizing, boolean editMode) {
        Minecraft mc = Minecraft.getInstance();

        boolean showLogo  = AetherConfig.WATERMARK_SHOW_LOGO.get();
        boolean showName  = AetherConfig.WATERMARK_SHOW_NAME.get();
        boolean gradient  = AetherConfig.WATERMARK_GRADIENT.get();
        int     gradLeft  = gradient ? AetherConfig.WATERMARK_GRADIENT_LEFT.get()  : Theme.ACCENT_PRIMARY;
        int     gradRight = gradient ? AetherConfig.WATERMARK_GRADIENT_COLOR.get() : Theme.ACCENT_PRIMARY;

        boolean wantMacro = AetherConfig.WATERMARK_SHOW_MACRO_STATUS.get() && MacroStateManager.isMacroRunning();

        float h      = HEIGHT;
        float nameW  = nvg.textWidth(Fonts.BOLD, NAME,    NAME_SZ);
        float verW   = nvg.textWidth(Fonts.BOLD, VERSION, VER_SZ);
        float brandW = PAD_H
                + (showLogo ? ICON_SZ + 4f : 0)
                + (showName ? nameW  + 2f  : 0)
                + verW + 1f + PAD_H;

        boolean showUser = AetherConfig.WATERMARK_SHOW_USERNAME.get();
        boolean showFps  = AetherConfig.WATERMARK_SHOW_FPS.get();
        boolean showPing = AetherConfig.WATERMARK_SHOW_PING.get();
        boolean showTime = AetherConfig.WATERMARK_SHOW_TIME.get();
        String custom    = AetherConfig.WATERMARK_CUSTOM_USERNAME.get();
        String username  = !custom.isEmpty() ? custom
                         : mc.player != null ? mc.player.getName().getString() : "---";
        String fps  = mc.getFps() + " fps";
        String ping = getPing(mc);
        String time = LocalTime.now().format(TIME_FMT);
        float userW = showUser ? nvg.textWidth(Fonts.REGULAR, username, INFO_SZ) : 0;
        float fpsW  = showFps  ? nvg.textWidth(Fonts.REGULAR, fps,      INFO_SZ) : 0;
        float pingW = showPing ? nvg.textWidth(Fonts.REGULAR, ping,     INFO_SZ) : 0;
        float timeW = showTime ? nvg.textWidth(Fonts.REGULAR, time,     INFO_SZ) : 0;

        MacroState.State st = MacroStateManager.getCurrentState();
        String stateStr  = "off";
        int    stateColor = 0xFFFF8A8A;
        switch (st) {
            case FARMING         -> { stateStr = "farming";         stateColor = 0xFF8AFFA0; }
            case METAL_DETECTING -> { stateStr = "metal detecting"; stateColor = 0xFFFFD166; }
            case AUTO_CARNIVAL   -> { stateStr = "auto carnival";   stateColor = 0xFFFF8A5B; }
            case CLEANING        -> { stateStr = "cleaning";        stateColor = 0xFFFFBB88; }
            case RECOVERING      -> { stateStr = "recovering";      stateColor = 0xFFFF8A8A; }
            case VISITING        -> { stateStr = "visitor";         stateColor = 0xFF8AFFFF; }
            case AUTOSELLING     -> { stateStr = "autoselling";     stateColor = 0xFFCC8AFF; }
            case WARDROBE        -> { stateStr = "wardrobe";        stateColor = 0xFFFFE08A; }
            case EQUIPMENT       -> { stateStr = "equipment";       stateColor = 0xFFFFE08A; }
            case GEORGE          -> { stateStr = "george";          stateColor = 0xFFFF8ACC; }
            case DROPPING_JUNK   -> { stateStr = "dropping junk";   stateColor = 0xFFFFBB88; }
            default -> {}
        }
        boolean farming  = (st == MacroState.State.FARMING);
        boolean autoCarnival = (st == MacroState.State.AUTO_CARNIVAL);
        double  bps      = BpsTracker.getBps();
        int     bpsClr   = bpsColor(bps);
        String  bpsStr   = String.format("%.1f bps", bps);
        long totalProfit = ProfitManager.getTotalProfit(false);
        long sesMs       = MacroStateManager.getSessionRunningTime();
        long dayMs       = DailyFarmTimeTracker.getTodayMs();
        long cph         = sesMs > 0 ? (long)(totalProfit / (sesMs / 3_600_000.0)) : 0;
        long tokenPh     = AutoCarnivalManager.getSessionTokensPerHour();
        String cphStr    = autoCarnival ? fmtCompact(tokenPh) + " tok/h" : fmtCompact(cph) + "/h";
        long restTrigger = DynamicRestManager.getNextRestTriggerMs();
        String restStr   = restTrigger <= 0 ? "---"
                         : formatTime(Math.max(0, restTrigger - System.currentTimeMillis()));
        String sessStr   = formatTime(sesMs);
        String dayStr    = formatTime(dayMs);
        float badgeTextW = nvg.textWidth(Fonts.BOLD, stateStr, INFO_SZ);
        float badgeW     = BADGE_PAD * 2f + badgeTextW;
        float bpsW       = farming ? nvg.textWidth(Fonts.MONO, bpsStr,  INFO_SZ) : 0;
        float cphW       = nvg.textWidth(Fonts.MONO, cphStr,  INFO_SZ);
        float restW      = nvg.textWidth(Fonts.MONO, restStr, INFO_SZ);
        float sessW      = nvg.textWidth(Fonts.MONO, sessStr, INFO_SZ);
        float dayW       = nvg.textWidth(Fonts.MONO, dayStr, INFO_SZ);

        float fullW = computeFullW(renderMacro, brandW, farming,
                badgeW, bpsW, cphW, restW, sessW, dayW,
                showUser, userW, showFps, fpsW, showPing, pingW, showTime, timeW);

        if (animW < 0f) {
            renderMacro = wantMacro;
            animW       = fullW;
            animStep    = 0;
        }
        if (animStep == 0) {
            if (wantMacro != renderMacro) animStep = 1;
        } else if (animStep == 1) {
            if (Math.abs(animW - brandW) < 1.5f) {
                animW       = brandW;
                renderMacro = wantMacro;
                fullW       = computeFullW(renderMacro, brandW, farming,
                        badgeW, bpsW, cphW, restW, sessW, dayW,
                        showUser, userW, showFps, fpsW, showPing, pingW, showTime, timeW);
                animStep    = 2;
            }
        } else {
            if (Math.abs(animW - fullW) < 1.5f) { animW = fullW; animStep = 0; }
            if (wantMacro != renderMacro) animStep = 1;
        }

        float targetW = (animStep == 1) ? brandW : fullW;
        float speed   = Math.min(1f, Theme.animationFactor() * (animStep == 1 ? SHRINK_SPEED_MULT : GROW_SPEED_MULT));
        animW += (targetW - animW) * speed;
        float w = animW;

        nvg.roundedRect(0, 0, w, h, CORNER, Theme.HUD_BG);
        if (gradient) {
            nvg.rectOutlineVerticalSidesGradient(0, 0, w, h, CORNER, 1f,
                    Theme.withAlpha(gradLeft, 0.7f),
                    Theme.withAlpha(gradRight, 0.7f), 0.4f);
        } else {
            nvg.rectOutlineVerticalSides(0, 0, w, h, CORNER, 1f,
                    Theme.withAlpha(Theme.ACCENT_PRIMARY, 0.7f), 0.4f);
        }
        if (editMode) {
            int border = isDragging ? Theme.HUD_ACCENT : isResizing ? Theme.HUD_WARNING : Theme.HUD_BORDER;
            nvg.roundedRect(-1, -1, w + 2, h + 2, CORNER + 1, border);
        }

        nvg.save();
        nvg.scissor(0, 0, w, h);

        float cy    = h / 2f;
        float iconY = cy - ICON_SZ / 2f;
        float textY = cy - NAME_SZ / 2f;
        float infoY = cy - INFO_SZ / 2f + 0.5f;
        float cx    = PAD_H;

        if (showLogo) {
            nvg.renderSVG(LOGO_ICON, cx, iconY, ICON_SZ, ICON_SZ,
                    accent(cx + ICON_SZ / 2f, w, gradient, gradLeft, gradRight));
            cx += ICON_SZ + 4f;
        }
        if (showName) {
            nvg.text(Fonts.BOLD, NAME, cx, textY, NAME_SZ,
                    accent(cx + nameW / 2f, w, gradient, gradLeft, gradRight));
            cx += nameW + 2f;
        }
        nvg.text(Fonts.BOLD, VERSION, cx, textY + VER_SZ / 2f + 0.5f, VER_SZ, Theme.TEXT_DIM);
        cx += verW;

        if (renderMacro) {
            cx += SEP;
            float badgeH = h - PAD_V * 2f + 2f;
            float badgeY = cy - badgeH / 2f;
            nvg.roundedRect(cx, badgeY, badgeW, badgeH, BADGE_R, Theme.withAlpha(stateColor, 0x25));
            nvg.rectOutline(cx, badgeY, badgeW, badgeH, BADGE_R, 1f, Theme.withAlpha(stateColor, 0x80));
            nvg.text(Fonts.BOLD, stateStr, cx + BADGE_PAD, infoY, INFO_SZ, stateColor);
            cx += badgeW;

            if (farming) {
                cx += SEP;
                nvg.renderSVG(PERFORMANCE_ICON, cx, iconY, ICON_SZ, ICON_SZ, bpsClr);
                cx += ICON_SZ + ICON_GAP;
                nvg.text(Fonts.MONO, bpsStr, cx, infoY, INFO_SZ, bpsClr);
                cx += bpsW;
            }

            cx += SEP;
            nvg.renderSVG(CASH_ICON, cx, iconY, ICON_SZ, ICON_SZ, Theme.HUD_LABEL);
            cx += ICON_SZ + ICON_GAP;
            nvg.text(Fonts.MONO, cphStr, cx, infoY, INFO_SZ, Theme.HUD_VALUE);
            cx += cphW;

            cx += SEP;
            nvg.renderSVG(REFRESH_ICON, cx, iconY, ICON_SZ, ICON_SZ, Theme.HUD_LABEL);
            cx += ICON_SZ + ICON_GAP;
            nvg.text(Fonts.MONO, restStr, cx, infoY, INFO_SZ, Theme.HUD_VALUE);
            cx += restW;

            cx += SEP;
            nvg.renderSVG(CLOCK_ICON, cx, iconY, ICON_SZ, ICON_SZ, Theme.HUD_LABEL);
            cx += ICON_SZ + ICON_GAP;
            nvg.text(Fonts.MONO, sessStr, cx, infoY, INFO_SZ, Theme.HUD_VALUE);
            cx += sessW;

            cx += SEP;
            nvg.renderSVG(CALENDAR_ICON, cx, iconY, ICON_SZ, ICON_SZ, Theme.HUD_LABEL);
            cx += ICON_SZ + ICON_GAP;
            nvg.text(Fonts.MONO, dayStr, cx, infoY, INFO_SZ, Theme.HUD_VALUE);
        } else {
            if (showUser) {
                cx += SEP;
                nvg.renderSVG(PERSON_ICON, cx, iconY, ICON_SZ, ICON_SZ,
                        accent(cx + ICON_SZ / 2f, w, gradient, gradLeft, gradRight));
                cx += ICON_SZ + ICON_GAP;
                nvg.text(Fonts.REGULAR, username, cx, infoY, INFO_SZ, Theme.HUD_VALUE);
                cx += userW;
            }
            if (showFps) {
                cx += SEP;
                nvg.renderSVG(PERFORMANCE_ICON, cx, iconY, ICON_SZ, ICON_SZ,
                        accent(cx + ICON_SZ / 2f, w, gradient, gradLeft, gradRight));
                cx += ICON_SZ + ICON_GAP;
                nvg.text(Fonts.REGULAR, fps, cx, infoY, INFO_SZ, Theme.HUD_VALUE);
                cx += fpsW;
            }
            if (showPing) {
                cx += SEP;
                nvg.renderSVG(SIGNAL_ICON, cx, iconY, ICON_SZ, ICON_SZ,
                        accent(cx + ICON_SZ / 2f, w, gradient, gradLeft, gradRight));
                cx += ICON_SZ + ICON_GAP;
                nvg.text(Fonts.REGULAR, ping, cx, infoY, INFO_SZ, Theme.HUD_VALUE);
                cx += pingW;
            }
            if (showTime) {
                cx += SEP;
                nvg.renderSVG(CLOCK_ICON, cx, iconY, ICON_SZ, ICON_SZ,
                        accent(cx + ICON_SZ / 2f, w, gradient, gradLeft, gradRight));
                cx += ICON_SZ + ICON_GAP;
                nvg.text(Fonts.REGULAR, time, cx, infoY, INFO_SZ, Theme.HUD_VALUE);
            }
        }

        nvg.restore();
    }

    private static float computeFullW(boolean macro, float brandW, boolean farming,
                                       float badgeW, float bpsW, float cphW, float restW, float sessW, float dayW,
                                       boolean showUser, float userW, boolean showFps, float fpsW,
                                       boolean showPing, float pingW, boolean showTime, float timeW) {
        if (macro) {
            return brandW
                    + SEP + badgeW
                    + (farming ? SEP + ICON_SZ + ICON_GAP + bpsW : 0)
                    + SEP + ICON_SZ + ICON_GAP + cphW
                    + SEP + ICON_SZ + ICON_GAP + restW
                    + SEP + ICON_SZ + ICON_GAP + sessW
                    + SEP + ICON_SZ + ICON_GAP + dayW;
        } else {
            return brandW
                    + (showUser ? SEP + ICON_SZ + ICON_GAP + userW : 0)
                    + (showFps  ? SEP + ICON_SZ + ICON_GAP + fpsW  : 0)
                    + (showPing ? SEP + ICON_SZ + ICON_GAP + pingW : 0)
                    + (showTime ? SEP + ICON_SZ + ICON_GAP + timeW : 0);
        }
    }

    private static int accent(float cx, float totalW, boolean gradient, int gradLeft, int gradRight) {
        if (!gradient) return Theme.ACCENT_PRIMARY;
        float t = totalW > 0f ? Math.max(0f, Math.min(1f, cx / totalW)) : 0f;
        return lerpColor(gradLeft, gradRight, t);
    }

    private static int bpsColor(double bps) {
        if (bps >= 18.5) return BPS_GREEN;
        if (bps >= 16.0) return lerpColor(BPS_YELLOW, BPS_GREEN,  (float)((bps - 16.0) / 2.5));
        if (bps >= 14.0) return lerpColor(BPS_ORANGE, BPS_YELLOW, (float)((bps - 14.0) / 2.0));
        return lerpColor(BPS_RED, BPS_ORANGE, (float)(Math.max(0, bps) / 14.0));
    }

    private static int lerpColor(int a, int b, float t) {
        int aa = (a >> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return ((int)(aa + (ba - aa) * t) << 24)
             | ((int)(ar + (br - ar) * t) << 16)
             | ((int)(ag + (bg - ag) * t) << 8)
             |  (int)(ab + (bb - ab) * t);
    }

    private static String fmtCompact(long v) {
        if (v < 0)          return "-" + fmtCompact(-v);
        if (v >= 1_000_000) return String.format("%.1fM", v / 1_000_000.0);
        if (v >= 1_000)     return String.format("%.1fk", v / 1_000.0);
        return String.valueOf(v);
    }

    private static String formatTime(long ms) {
        long s = ms / 1000, h = s / 3600, m = (s % 3600) / 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s % 60)
                     : String.format("%02d:%02d", m, s % 60);
    }

    private static String getPing(Minecraft mc) {
        if (mc.player == null || mc.getConnection() == null) return "---";
        return PingTracker.getFormattedPing();
    }
}
