package dev.aether.hud;

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
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class WatermarkHudElement extends HudElement {

    private static final String NAME    = "Aether";
    private static final String VERSION = FabricLoader.getInstance()
            .getModContainer("aether")
            .map(container -> stripReleaseSuffix(container.getMetadata().getVersion().getFriendlyString()))
            .orElse("dev");

    private static final float PAD_H     = 12f;
    private static final float PAD_V     = 9f;
    private static final float NAME_SZ   = 14f;
    private static final float VER_SZ    = 8f;
    private static final float INFO_SZ   = 10f;
    private static final float ICON_SZ   = 12f;
    private static final float ICON_GAP  = 3f;
    private static final float SEP       = 14f;
    private static final float BADGE_PAD = 5f;
    private static final float BADGE_R   = 3f;
    private static final float SHRINK_SPEED_MULT = 0.5f;
    private static final float GROW_SPEED_MULT   = 0.3f;

    private static final String LOGO_ICON        = "/assets/aether/icons/logo.svg";
    private static final String PERSON_ICON      = "/assets/aether/icons/person.svg";
    private static final String PERFORMANCE_ICON = "/assets/aether/icons/performance.svg";
    private static final String SIGNAL_ICON      = "/assets/aether/icons/signal.svg";
    private static final String CLOCK_ICON       = "/assets/aether/icons/clock.svg";
    private static final String REFRESH_ICON     = "/assets/aether/icons/refresh.svg";
    private static final String CASH_ICON        = "/assets/aether/icons/cash.svg";
    private static final String CALENDAR_ICON    = "/assets/aether/icons/calendar.svg";

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    // Animation state
    private float   animW       = -1f;
    private boolean renderMacro = false;
    private int     animStep    = 0;   // 0=idle, 1=shrinking, 2=growing

    @Override public float   getX()            { return AetherConfig.WATERMARK_HUD_X.get(); }
    @Override public float   getY()            { return AetherConfig.WATERMARK_HUD_Y.get(); }
    @Override public void    setX(float x)     { AetherConfig.WATERMARK_HUD_X.set((int) x); }
    @Override public void    setY(float y)     { AetherConfig.WATERMARK_HUD_Y.set((int) y); }
    @Override public float   getScale()        { return AetherConfig.WATERMARK_HUD_SCALE.get(); }
    @Override public void    setScale(float s) { AetherConfig.WATERMARK_HUD_SCALE.set(s); }
    @Override public float   getWidth()        { return animW < 0f ? PAD_H * 2f + 200f : animW; }
    @Override public float   getHeight()       { return PAD_V * 2f + NAME_SZ; }
    @Override public boolean isVisible()       { return (AetherConfig.HUD_THEME.get() & 0x2) != 0; }
    @Override public String  getName()         { return "Watermark"; }
    @Override public void    savePosition()    { AetherConfig.save(); }

    @Override
    protected void renderElement(NVGRenderer nvg, boolean editMode) {
        Minecraft mc = Minecraft.getInstance();

        boolean showLogo  = AetherConfig.WATERMARK_SHOW_LOGO.get();
        boolean showName  = AetherConfig.WATERMARK_SHOW_NAME.get();
        boolean gradient  = AetherConfig.WATERMARK_GRADIENT.get();
        int     gradLeft  = gradient ? AetherConfig.WATERMARK_GRADIENT_LEFT.get()  : Theme.HUD_ACCENT;
        int     gradRight = gradient ? AetherConfig.WATERMARK_GRADIENT_COLOR.get() : Theme.HUD_ACCENT;

        boolean wantMacro = AetherConfig.WATERMARK_SHOW_MACRO_STATUS.get() && MacroStateManager.isMacroRunning();

        float h      = getHeight();
        float nameW  = nvg.textWidth(Fonts.BOLD, NAME,    NAME_SZ);
        float verW   = nvg.textWidth(Fonts.BOLD, VERSION, VER_SZ);
        float brandW = PAD_H
                + (showLogo ? ICON_SZ + 4f : 0)
                + (showName ? nameW  + 2f  : 0)
                + verW + 1f + PAD_H;

        // ---- Normal info data -----------------------------------------------
        boolean showUser = AetherConfig.WATERMARK_SHOW_USERNAME.get();
        boolean showFps  = AetherConfig.WATERMARK_SHOW_FPS.get();
        boolean showPing = AetherConfig.WATERMARK_SHOW_PING.get();
        boolean showTime = AetherConfig.WATERMARK_SHOW_TIME.get();
        String custom    = AetherConfig.WATERMARK_CUSTOM_USERNAME.get();
        String username  = !custom.isEmpty() ? custom
                         : mc.player != null ? mc.player.getName().getString() : "---";
        username = HudStyle.fit(nvg, Fonts.REGULAR, username, INFO_SZ, 110f);
        String fps  = mc.getFps() + " fps";
        String ping = getPing(mc);
        String time = LocalTime.now().format(TIME_FMT);
        float userW = showUser ? nvg.textWidth(Fonts.REGULAR, username, INFO_SZ) : 0;
        float fpsW  = showFps  ? nvg.textWidth(Fonts.REGULAR, fps,      INFO_SZ) : 0;
        float pingW = showPing ? nvg.textWidth(Fonts.REGULAR, ping,     INFO_SZ) : 0;
        float timeW = showTime ? nvg.textWidth(Fonts.REGULAR, time,     INFO_SZ) : 0;

        // ---- Macro status data ----------------------------------------------
        MacroState.State st = MacroStateManager.getCurrentState();
        String stateStr = st.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        int stateColor = HudStyle.stateColor(st);
        boolean farming  = (st == MacroState.State.FARMING);
        boolean autoCarnival = (st == MacroState.State.AUTO_CARNIVAL);
        double  bps      = BpsTracker.getBps();
        int     bpsClr   = HudStyle.bpsColor(bps);
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

        // ---- Compute full target width for current renderMacro ---------------
        float fullW = computeFullW(animW < 0f ? wantMacro : renderMacro, brandW, farming,
                badgeW, bpsW, cphW, restW, sessW, dayW,
                showUser, userW, showFps, fpsW, showPing, pingW, showTime, timeW);

        // ---- Two-phase animation state machine -------------------------------
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
        } else { // GROWING
            if (Math.abs(animW - fullW) < 1.5f) { animW = fullW; animStep = 0; }
            if (wantMacro != renderMacro) animStep = 1;
        }

        float targetW = (animStep == 1) ? brandW : fullW;
        float speed   = Math.min(1f, Theme.animationFactor() * (animStep == 1 ? SHRINK_SPEED_MULT : GROW_SPEED_MULT));
        animW += (targetW - animW) * speed;
        float w = animW;

        HudStyle.panel(nvg, w, h);
        HudStyle.accent(nvg, w, gradLeft, gradRight);

        // ---- Content (clipped to animated width) ----------------------------
        nvg.save();
        nvg.scissor(0, 0, w, h);

        float cy    = h / 2f;
        float iconY = cy - ICON_SZ / 2f;
        float textY = cy - NAME_SZ / 2f;
        float infoY = cy - INFO_SZ / 2f + 0.5f;
        float cx    = PAD_H;

        // Brand: logo + name + version
        if (showLogo) {
            nvg.renderSVG(LOGO_ICON, cx, iconY, ICON_SZ, ICON_SZ,
                    accent(cx + ICON_SZ / 2f, w, gradient, gradLeft, gradRight));
            cx += ICON_SZ + 4f;
        }
        if (showName) {
            nvg.text(Fonts.BOLD, NAME, cx, textY, NAME_SZ, Theme.HUD_TITLE);
            cx += nameW + 2f;
        }
        nvg.text(Fonts.BOLD, VERSION, cx, textY + VER_SZ / 2f + 0.5f, VER_SZ, Theme.HUD_LABEL);
        cx += verW;

        if (renderMacro) {
            // State badge
            cx += SEP;
            float badgeH = h - PAD_V * 2f + 2f;
            float badgeY = cy - badgeH / 2f;
            nvg.roundedRect(cx, badgeY, badgeW, badgeH, BADGE_R, HudStyle.alpha(stateColor, 0.14f));
            nvg.rectOutline(cx, badgeY, badgeW, badgeH, BADGE_R, 1f, HudStyle.alpha(stateColor, 0.5f));
            nvg.text(Fonts.BOLD, stateStr, cx + BADGE_PAD, infoY, INFO_SZ, stateColor);
            cx += badgeW;

            // BPS - farming only
            if (farming) {
                separator(nvg, cx, h);
                cx += SEP;
                nvg.renderSVG(PERFORMANCE_ICON, cx, iconY, ICON_SZ, ICON_SZ, bpsClr);
                cx += ICON_SZ + ICON_GAP;
                nvg.text(Fonts.MONO, bpsStr, cx, infoY, INFO_SZ, Theme.HUD_VALUE);
                cx += bpsW;
            }

            // Cash per hour
            cx += SEP;
            nvg.renderSVG(CASH_ICON, cx, iconY, ICON_SZ, ICON_SZ, Theme.HUD_LABEL);
            cx += ICON_SZ + ICON_GAP;
            nvg.text(Fonts.MONO, cphStr, cx, infoY, INFO_SZ, Theme.HUD_VALUE);
            cx += cphW;

            // Next rest
            cx += SEP;
            nvg.renderSVG(REFRESH_ICON, cx, iconY, ICON_SZ, ICON_SZ, Theme.HUD_LABEL);
            cx += ICON_SZ + ICON_GAP;
            nvg.text(Fonts.MONO, restStr, cx, infoY, INFO_SZ, Theme.HUD_VALUE);
            cx += restW;

            // Session timer
            cx += SEP;
            nvg.renderSVG(CLOCK_ICON, cx, iconY, ICON_SZ, ICON_SZ, Theme.HUD_LABEL);
            cx += ICON_SZ + ICON_GAP;
            nvg.text(Fonts.MONO, sessStr, cx, infoY, INFO_SZ, Theme.HUD_VALUE);
            cx += sessW;

            // Daily timer
            cx += SEP;
            nvg.renderSVG(CALENDAR_ICON, cx, iconY, ICON_SZ, ICON_SZ, Theme.HUD_LABEL);
            cx += ICON_SZ + ICON_GAP;
            nvg.text(Fonts.MONO, dayStr, cx, infoY, INFO_SZ, Theme.HUD_VALUE);

        } else {
            if (showUser) {
                separator(nvg, cx, h);
                cx += SEP;
                nvg.renderSVG(PERSON_ICON, cx, iconY, ICON_SZ, ICON_SZ,
                        accent(cx + ICON_SZ / 2f, w, gradient, gradLeft, gradRight));
                cx += ICON_SZ + ICON_GAP;
                nvg.text(Fonts.REGULAR, username, cx, infoY, INFO_SZ, Theme.HUD_VALUE);
                cx += userW;
            }
            if (showFps) {
                separator(nvg, cx, h);
                cx += SEP;
                nvg.renderSVG(PERFORMANCE_ICON, cx, iconY, ICON_SZ, ICON_SZ,
                        accent(cx + ICON_SZ / 2f, w, gradient, gradLeft, gradRight));
                cx += ICON_SZ + ICON_GAP;
                nvg.text(Fonts.REGULAR, fps, cx, infoY, INFO_SZ, Theme.HUD_VALUE);
                cx += fpsW;
            }
            if (showPing) {
                separator(nvg, cx, h);
                cx += SEP;
                nvg.renderSVG(SIGNAL_ICON, cx, iconY, ICON_SZ, ICON_SZ,
                        accent(cx + ICON_SZ / 2f, w, gradient, gradLeft, gradRight));
                cx += ICON_SZ + ICON_GAP;
                nvg.text(Fonts.REGULAR, ping, cx, infoY, INFO_SZ, Theme.HUD_VALUE);
                cx += pingW;
            }
            if (showTime) {
                separator(nvg, cx, h);
                cx += SEP;
                nvg.renderSVG(CLOCK_ICON, cx, iconY, ICON_SZ, ICON_SZ,
                        accent(cx + ICON_SZ / 2f, w, gradient, gradLeft, gradRight));
                cx += ICON_SZ + ICON_GAP;
                nvg.text(Fonts.REGULAR, time, cx, infoY, INFO_SZ, Theme.HUD_VALUE);
            }
        }

        nvg.restore();
    }

    // ---- Helpers ---------------------------------------------------------------

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
        if (!gradient) return Theme.HUD_ACCENT;
        float t = totalW > 0f ? Math.max(0f, Math.min(1f, cx / totalW)) : 0f;
        return Theme.blend(gradLeft, gradRight, t);
    }

    private static void separator(NVGRenderer nvg, float x, float height) {
        nvg.rect(x + SEP / 2f, 10f, 0.7f, height - 20f, Theme.HUD_SEP);
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

    private static String stripReleaseSuffix(String version) {
        return version.replaceFirst("-r\\d+$", "");
    }

    private static String getPing(Minecraft mc) {
        if (mc.player == null || mc.getConnection() == null) return "---";
        return PingTracker.getFormattedPing();
    }
}
