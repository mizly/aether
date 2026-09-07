package dev.aether.hud;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.modules.farming.FastLaneSwitchManager;
import dev.aether.modules.session.DynamicRestManager;
import dev.aether.renderer.NVGRenderer;
import dev.aether.telemetry.AetherAuthService;
import dev.aether.ui.theme.Theme;
import dev.aether.util.BpsTracker;
import dev.aether.util.ClientUtils;

public class MacroHudElement extends HudElement {
    public static final float W = 240f;
    private static final float ROW_H = 18f;
    private static final float LABEL_SZ = 10f;

    // -- HudElement API --------------------------------------------------------

    @Override public float   getX()           { return AetherConfig.HUD_X.get(); }
    @Override public float   getY()           { return AetherConfig.HUD_Y.get(); }
    @Override public void    setX(float x)    { AetherConfig.HUD_X.set((int) x); }
    @Override public void    setY(float y)    { AetherConfig.HUD_Y.set((int) y); }
    @Override public float   getScale()       { return AetherConfig.HUD_SCALE.get(); }
    @Override public void    setScale(float s){ AetherConfig.HUD_SCALE.set(s); }
    @Override public float   getWidth()       { return W; }
    @Override public float   getHeight()      { return computeHeight(); }
    @Override public boolean isEnabled()      { return AetherConfig.SHOW_HUD.get(); }
    @Override public boolean isVisible()      {
        boolean inSupportedArea = ClientUtils.isSupportedHudArea();
        return isEnabled() && (inSupportedArea || AetherConfig.SHOW_HUD_OUTSIDE_GARDEN.get());
    }
    @Override public String  getName()        { return "Macro HUD"; }
    @Override public void    savePosition()   { AetherConfig.save(); }

    float computeHeight() {
        return HudStyle.CONTENT_Y + 6 * ROW_H + 8f + HudStyle.PAD;
    }

    @Override
    protected void renderElement(NVGRenderer nvg, boolean editMode) {
        MacroState.State state = MacroStateManager.getCurrentState();
        String stateLabel = state.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        long sessionMs = MacroStateManager.getSessionRunningTime();
        long restTrigger = DynamicRestManager.getNextRestTriggerMs();
        String nextRest = restTrigger <= 0 ? "---"
                : formatTime(Math.max(0, restTrigger - System.currentTimeMillis()));

        HudStyle.panel(nvg, W, computeHeight());
        HudStyle.header(nvg, W, "Macro", "Aether");
        float y = HudStyle.CONTENT_Y;
        row(nvg, y, "Status", stateLabel, HudStyle.stateColor(state)); y += ROW_H;
        row(nvg, y, String.format("BPS (%d / %.1fs)", BpsTracker.getBreakCount(), BpsTracker.getActualWindowSeconds()),
                String.format("%.2f", BpsTracker.getBps()), Theme.HUD_VALUE); y += ROW_H;
        row(nvg, y, "Next lane", FastLaneSwitchManager.getDisplayText(),
                FastLaneSwitchManager.hasEstimate() ? Theme.HUD_VALUE : Theme.HUD_LABEL); y += ROW_H;
        row(nvg, y, "Current session", formatTime(sessionMs), Theme.HUD_VALUE); y += ROW_H;
        row(nvg, y, "Hours played", AetherAuthService.isAuthenticated()
                ? formatTime(AetherAuthService.getTotalSeconds() * 1000L) : "not linked", Theme.HUD_VALUE); y += ROW_H;
        row(nvg, y, "Next rest", nextRest, Theme.HUD_VALUE); y += ROW_H;

        long duration = DynamicRestManager.getScheduledDurationMs();
        float progress = duration > 0 && restTrigger > 0
                ? (float) (duration - Math.max(0, restTrigger - System.currentTimeMillis())) / duration : 0f;
        HudStyle.progress(nvg, HudStyle.PAD, y + 3f, W - HudStyle.PAD * 2, 4f, progress);
    }

    private void row(NVGRenderer nvg, float y, String label, String value, int color) {
        HudStyle.row(nvg, HudStyle.PAD, y, W - HudStyle.PAD * 2, label, value, LABEL_SZ, color);
    }

    private static String formatTime(long ms) {
        long s = ms / 1000, h = s / 3600, m = (s % 3600) / 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s % 60)
                     : String.format("%02d:%02d", m, s % 60);
    }
}
