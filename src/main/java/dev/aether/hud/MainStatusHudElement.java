package dev.aether.hud;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.modules.metaldetector.MetalDetectorSolver;
import dev.aether.modules.farming.FastLaneSwitchManager;
import dev.aether.modules.misc.AutoCarnivalManager;
import dev.aether.modules.profit.ProfitManager;
import dev.aether.modules.session.DailyFarmTimeTracker;
import dev.aether.modules.session.DynamicRestManager;
import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.util.Fonts;
import dev.aether.util.BpsTracker;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;

/**
 * All-in-one macro status card for the "Main" HUD theme.
 */
public class MainStatusHudElement extends HudElement {

    // Layout constants
    public static final float W          = 240f;
    private static final float PAD_H     = 12f;
    private static final float PAD_V     = 10f;
    private static final float ICON_BOX  = 30f;
    private static final float HEADER_H  = PAD_V + ICON_BOX + PAD_V;
    private static final float STATE_SZ  = 12f;
    private static final float LOC_SZ    = 9f;
    private static final float LABEL_SZ  = 9f;
    private static final float VAL_SZ    = 20f;
    private static final float SES_SZ    = 10f;
    private static final float ROW_SZ    = 10f;
    private static final float ROW_H     = 16f;
    private static final float SEP_H     = 1f;
    private static final float BOX_R     = 4f;
    private static final float BOX_PAD_H = 8f;
    private static final float BOX_PAD_V = 6f;
    private static final float BAR_H     = 3f;

    // Full content height (when macro running)
    private static final float CONTENT_H =
            SEP_H + 8f                           // separator + top gap
            + LABEL_SZ + 3f + VAL_SZ + 6f        // SESSION PROFIT label + value
            + ROW_H + ROW_H + ROW_H + ROW_H       // Session, Daily, Coins/hr, lane rows
            + 6f + BAR_H + 8f                     // rest bar
            + (BOX_PAD_V * 2f + LABEL_SZ + 4f + SES_SZ) // grid row
            + PAD_V;                              // bottom pad

    private static final String LOGO_ICON = "/assets/aether/icons/logo.svg";

    // Animation state
    private float macroLerp  = -1f;
    private float farmingLerp = -1f;
    private float contentH   = -1f;

    @Override public float   getX()            { return AetherConfig.MAIN_STATUS_HUD_X.get(); }
    @Override public float   getY()            { return AetherConfig.MAIN_STATUS_HUD_Y.get(); }
    @Override public void    setX(float x)     { AetherConfig.MAIN_STATUS_HUD_X.set((int) x); }
    @Override public void    setY(float y)     { AetherConfig.MAIN_STATUS_HUD_Y.set((int) y); }
    @Override public float   getScale()        { return AetherConfig.MAIN_STATUS_HUD_SCALE.get(); }
    @Override public void    setScale(float s) { AetherConfig.MAIN_STATUS_HUD_SCALE.set(s); }
    @Override public float   getWidth()        { return W; }
    @Override public float   getHeight()       { return HEADER_H + (contentH < 0f
            ? (MacroStateManager.isMacroRunning() ? CONTENT_H : 0f) : contentH); }
    @Override public boolean isVisible()       { return (AetherConfig.HUD_THEME.get() & 0x1) != 0; }
    @Override public String  getName()         { return "Main Status"; }
    @Override public void    savePosition()    { AetherConfig.save(); }

    @Override
    protected void renderElement(NVGRenderer nvg, boolean editMode) {
        Minecraft mc = Minecraft.getInstance();
        MacroState.State    st  = MacroStateManager.getCurrentState();
        MacroState.Location loc = ClientUtils.getCurrentLocation();

        boolean macroing = editMode || st != MacroState.State.OFF;
        boolean farming  = editMode || st == MacroState.State.FARMING;
        boolean metalDetecting = st == MacroState.State.METAL_DETECTING;
        boolean autoCarnival = st == MacroState.State.AUTO_CARNIVAL;

        boolean gradient  = AetherConfig.MAIN_STATUS_GRADIENT.get();
        int     gradLeft  = gradient ? AetherConfig.MAIN_STATUS_GRADIENT_LEFT.get()  : Theme.HUD_ACCENT;
        int     gradRight = gradient ? AetherConfig.MAIN_STATUS_GRADIENT_RIGHT.get() : Theme.HUD_ACCENT;

        // ---- Animate lerps --------------------------------------------------
        float speed = Theme.animationFactor(1f);
        if (macroLerp < 0f)   macroLerp   = macroing ? 1f : 0f;
        if (farmingLerp < 0f) farmingLerp = farming  ? 1f : 0f;
        if (contentH < 0f)    contentH    = macroing ? CONTENT_H : 0f;

        float targetMacro   = macroing ? 1f : 0f;
        float targetFarming = farming  ? 1f : 0f;
        float targetContent = macroing ? CONTENT_H : 0f;
        macroLerp   += (targetMacro   - macroLerp)   * speed;
        farmingLerp += (targetFarming - farmingLerp) * speed;
        contentH    += (targetContent - contentH)    * speed;

        if (editMode) {
            macroLerp = 1f;
            farmingLerp = 1f;
            contentH = CONTENT_H;
        }
        float fullH = HEADER_H + contentH;
        float bw    = W - PAD_H * 2f;

        HudStyle.panel(nvg, W, fullH);
        HudStyle.accent(nvg, W, gradLeft, gradRight);

        float ry = PAD_V;

        // ---- Header ---------------------------------------------------------
        // Logo box: muted when idle, gradLeft when macroing
        int logoBoxColor = Theme.blend(Theme.HUD_SEP, gradLeft, macroLerp);
        nvg.roundedRect(PAD_H, ry, ICON_BOX, ICON_BOX, 6f, HudStyle.alpha(logoBoxColor, 0.14f));
        float logoSz  = ICON_BOX * 0.55f;
        float logoOff = (ICON_BOX - logoSz) / 2f;
        nvg.renderSVG(LOGO_ICON, PAD_H + logoOff, ry + logoOff, logoSz, logoSz, logoBoxColor);

        float textX   = PAD_H + ICON_BOX + 8f;
        float stateY  = ry + (ICON_BOX / 2f) - STATE_SZ - 1f;
        String titleStr = !macroing
                ? "Waiting"
                : metalDetecting
                        ? "Metal Detector"
                        : autoCarnival
                                ? "Auto Carnival"
                                : "Farming Macro";
        int    titleColor = Theme.HUD_TITLE;
        HudStyle.text(nvg, Fonts.BOLD, titleStr, textX, stateY, W - textX - PAD_H - 18f, STATE_SZ, titleColor);

        // Subtitle: "Garden - Farming" style location/state line
        String locStr   = locationLabel(loc);
        String stateStr = stateLabel(st);
        String subtitle = st == MacroState.State.OFF
                ? locStr
                : locStr + " - " + stateStr;
        HudStyle.text(nvg, Fonts.REGULAR, subtitle, textX, stateY + STATE_SZ + 2f,
                W - textX - PAD_H, LOC_SZ, HudStyle.stateColor(st));

        ry += ICON_BOX + PAD_V;

        // ---- Animated content block ----------------------------------------
        if (contentH < 0.5f) return;
        nvg.save();
        nvg.scissor(0, HEADER_H, W, contentH);
        nvg.globalAlpha(macroLerp);

        // Separator
        nvg.rect(PAD_H, ry, bw, SEP_H, Theme.HUD_SEP);
        ry += SEP_H + 8f;

        // Session profit
        long totalProfit = ProfitManager.getTotalProfit(false);
        long carnivalTokens = AutoCarnivalManager.getSessionTokensEarned();
        long carnivalTokensPerHour = AutoCarnivalManager.getSessionTokensPerHour();
        int scavengedTools = MetalDetectorSolver.getScavengedToolCount(mc);
        String backpackSummary = MetalDetectorSolver.getFilledBackpacksSummary();
        String primaryLabel = metalDetecting
                ? "TOOLS IN INVENTORY"
                : autoCarnival
                        ? "CARNIVAL TOKENS"
                        : "SESSION PROFIT";
        String primaryValue = metalDetecting
                ? String.valueOf(scavengedTools)
                : autoCarnival
                        ? fmtCompact(carnivalTokens)
                        : fmtCompact(totalProfit);
        nvg.text(Fonts.REGULAR, primaryLabel, PAD_H, ry, LABEL_SZ, Theme.HUD_LABEL);
        ry += LABEL_SZ + 3f;
        nvg.text(Fonts.BOLD, primaryValue, PAD_H, ry, VAL_SZ, Theme.HUD_VALUE);
        ry += VAL_SZ + 6f;

        // Session time row
        long sesMs   = MacroStateManager.getSessionRunningTime();
        long cph     = sesMs > 0 ? (long)(totalProfit / (sesMs / 3_600_000.0)) : 0;
        infoRow(nvg, ry,
                metalDetecting ? "Filled Backpacks" : autoCarnival ? "Tokens/hr" : "Coins/hr",
                metalDetecting ? backpackSummary : autoCarnival ? fmtCompact(carnivalTokensPerHour) : fmtCompact(cph),
                Theme.HUD_VALUE); ry += ROW_H;
        infoRow(nvg, ry, "Next Lane", FastLaneSwitchManager.getDisplayText(),
                FastLaneSwitchManager.hasEstimate() ? Theme.HUD_VALUE : Theme.HUD_LABEL); ry += ROW_H;
        infoRow(nvg, ry, "Session",   formatSessionTime(sesMs), Theme.HUD_VALUE); ry += ROW_H;
        infoRow(nvg, ry, "Daily", formatSessionTime(DailyFarmTimeTracker.getTodayMs()), Theme.HUD_VALUE); ry += ROW_H;

        // Rest progress bar
        long  restTrigger = DynamicRestManager.getNextRestTriggerMs();
        long  sched       = DynamicRestManager.getScheduledDurationMs();
        float prog        = (sched > 0 && restTrigger > 0)
                ? (float)(sched - Math.max(0, restTrigger - System.currentTimeMillis())) / sched
                : 0f;
        nvg.roundedRect(PAD_H, ry, bw, BAR_H, BAR_H / 2f, Theme.HUD_BAR_BG);
        float fw = bw * Math.max(0f, Math.min(1f, prog));
        if (fw > 0) {
            if (gradient) {
                nvg.horizontalGradient(PAD_H, ry, fw, BAR_H, BAR_H / 2f, gradLeft, gradRight);
            } else {
                nvg.roundedRect(PAD_H, ry, fw, BAR_H, BAR_H / 2f, Theme.HUD_ACCENT);
            }
        }
        ry += BAR_H + 8f;

        // ---- 2-col grid: [Next Rest | BPS] ----------------------------------
        float gridH   = BOX_PAD_V * 2f + LABEL_SZ + 4f + SES_SZ;
        float halfW   = (bw - 4f) / 2f;
        // BPS box fades as farmingLerp; Next Rest box widens to fill when farming=0
        float bpsBoxW  = halfW * farmingLerp;
        float restBoxW = bw - (farmingLerp > 0.01f ? bpsBoxW + 4f : 0f);

        // Next Rest box
        String restValStr = restTrigger <= 0 ? "---"
                : "In " + formatSessionTime(Math.max(0, restTrigger - System.currentTimeMillis()));
        nvg.roundedRect(PAD_H, ry, restBoxW, gridH, BOX_R, Theme.HUD_BAR_BG);
        nvg.text(Fonts.REGULAR, "NEXT REST", PAD_H + BOX_PAD_H, ry + BOX_PAD_V, LABEL_SZ, Theme.HUD_LABEL);
        int restValColor = Theme.HUD_VALUE;
        HudStyle.text(nvg, Fonts.BOLD, restValStr,
                PAD_H + BOX_PAD_H, ry + BOX_PAD_V + LABEL_SZ + 4f, restBoxW - BOX_PAD_H * 2, SES_SZ, restValColor);

        // BPS box (fades with farmingLerp)
        if (farmingLerp > 0.01f) {
            float bpsX = PAD_H + restBoxW + 4f;
            double bps    = BpsTracker.getBps();
            int    bpsClr = HudStyle.bpsColor(bps);
            String bpsStr = String.format("%.1f", bps);

            nvg.save();
            nvg.globalAlpha(macroLerp * farmingLerp);
            nvg.roundedRect(bpsX, ry, bpsBoxW, gridH, BOX_R, Theme.HUD_BAR_BG);
            nvg.text(Fonts.REGULAR, "BPS", bpsX + BOX_PAD_H, ry + BOX_PAD_V, LABEL_SZ, Theme.HUD_LABEL);
            nvg.text(Fonts.BOLD, bpsStr,
                    bpsX + BOX_PAD_H, ry + BOX_PAD_V + LABEL_SZ + 4f, SES_SZ, bpsClr);
            nvg.restore();
        }

        nvg.restore();
    }

    private void infoRow(NVGRenderer nvg, float y, String label, String value, int valColor) {
        float cy    = y + ROW_H / 2f;
        float textY = cy - ROW_SZ / 2f + 0.5f;
        HudStyle.row(nvg, PAD_H, textY, W - PAD_H * 2f, label, value, ROW_SZ, valColor);
    }

    // ---- Helpers ---------------------------------------------------------------

    private static String stateLabel(MacroState.State st) {
        return switch (st) {
            case FARMING       -> "Farming";
            case METAL_DETECTING -> "Metal Detecting";
            case AUTO_CARNIVAL -> "Auto Carnival";
            case CLEANING      -> "Cleaning";
            case RECOVERING    -> "Recovering";
            case VISITING      -> "Visitor";
            case AUTOSELLING   -> "Autoselling";
            case WARDROBE      -> "Wardrobe";
            case EQUIPMENT     -> "Equipment";
            case GEORGE        -> "George";
            case DROPPING_JUNK -> "Dropping Junk";
            default            -> "Idle";
        };
    }

    private static String locationLabel(MacroState.Location loc) {
        return switch (loc) {
            case GARDEN          -> "Garden";
            case CRYSTAL_HOLLOWS -> "Crystal Hollows";
            case HUB             -> "Hub";
            case LOBBY           -> "Lobby";
            case LIMBO           -> "Limbo";
            default              -> "Unknown";
        };
    }

    private static String fmtCompact(long v) {
        if (v < 0)          return "-" + fmtCompact(-v);
        if (v >= 1_000_000) return String.format("%.1fM", v / 1_000_000.0);
        if (v >= 1_000)     return String.format("%.1fk", v / 1_000.0);
        return String.valueOf(v);
    }

    private static String formatSessionTime(long ms) {
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m";
        return sec + "s";
    }
}
