package dev.aether.hud;

import dev.aether.config.AetherConfig;


import dev.aether.macro.MacroStateManager;
import dev.aether.ui.theme.Theme;
import dev.aether.modules.profit.ProfitManager;
import dev.aether.ui.util.Fonts;
import dev.aether.renderer.NVGRenderer;

import java.util.Map;

/**
 * NVG-rendered profit-tracking panel - one instance for Session, one for Lifetime.
 */
public class ProfitHudElement extends HudElement {

    // -- Layout (at scale 1.0) -------------------------------------------------

    public static final float W        = 300f;
    private static final float PAD_H   = HudStyle.PAD;
    private static final float LABEL_SZ = 10f;
    private static final float ROW_H   = 18f;
    private static final String LONGEST_CATEGORY_TAG = "[VISITOR]";
    private static final float CATEGORY_TAG_GAP = 3f;
    private static final float FARM_BAR_H = 4f;

    /** Panel mode: {@code "session"}, {@code "lifetime"}, or {@code "daily"}. */
    private final String mode;
    private final ProfitGraph graph = new ProfitGraph();

    public ProfitHudElement(String mode) { this.mode = mode; }

    private boolean isSession()  { return "session".equals(mode); }

    private String title() {
        return switch (mode) {
            case "lifetime" -> "Lifetime Profit";
            case "daily"    -> "Daily Profit";
            default         -> "Session Profit";
        };
    }

    // -- HudElement API --------------------------------------------------------

    @Override public float getX() {
        return switch (mode) {
            case "lifetime" -> AetherConfig.LIFETIME_HUD_X.get();
            case "daily"    -> AetherConfig.DAILY_HUD_X.get();
            default         -> AetherConfig.SESSION_PROFIT_HUD_X.get();
        };
    }
    @Override public float getY() {
        return switch (mode) {
            case "lifetime" -> AetherConfig.LIFETIME_HUD_Y.get();
            case "daily"    -> AetherConfig.DAILY_HUD_Y.get();
            default         -> AetherConfig.SESSION_PROFIT_HUD_Y.get();
        };
    }
    @Override public void setX(float x) {
        switch (mode) {
            case "lifetime" -> AetherConfig.LIFETIME_HUD_X.set((int) x);
            case "daily"    -> AetherConfig.DAILY_HUD_X.set((int) x);
            default         -> AetherConfig.SESSION_PROFIT_HUD_X.set((int) x);
        }
    }
    @Override public void setY(float y) {
        switch (mode) {
            case "lifetime" -> AetherConfig.LIFETIME_HUD_Y.set((int) y);
            case "daily"    -> AetherConfig.DAILY_HUD_Y.set((int) y);
            default         -> AetherConfig.SESSION_PROFIT_HUD_Y.set((int) y);
        }
    }
    @Override public float getScale() {
        return switch (mode) {
            case "lifetime" -> AetherConfig.LIFETIME_HUD_SCALE.get();
            case "daily"    -> AetherConfig.DAILY_HUD_SCALE.get();
            default         -> AetherConfig.SESSION_PROFIT_HUD_SCALE.get();
        };
    }
    @Override public void setScale(float s) {
        switch (mode) {
            case "lifetime" -> AetherConfig.LIFETIME_HUD_SCALE.set(s);
            case "daily"    -> AetherConfig.DAILY_HUD_SCALE.set(s);
            default         -> AetherConfig.SESSION_PROFIT_HUD_SCALE.set(s);
        }
    }
    @Override public float   getWidth()  { return W; }
    @Override public float   getHeight() { return computeHeight(); }
    @Override public boolean isVisible() {
        if (!AetherConfig.PROFIT_HUD_ENABLED.get()) return false;
        return switch (mode) {
            case "lifetime" -> AetherConfig.SHOW_LIFETIME_HUD.get();
            case "daily"    -> AetherConfig.SHOW_DAILY_HUD.get();
            default         -> AetherConfig.SHOW_SESSION_PROFIT_HUD.get();
        };
    }
    @Override public String  getName()       { return title(); }
    @Override public void    savePosition()  { AetherConfig.save(); }

    // -- Height ----------------------------------------------------------------

    float computeHeight() {
        float h = HudStyle.CONTENT_Y + 50f;
        if (showGraph()) h += ProfitGraph.HEIGHT;
        int itemCount;
        if (AetherConfig.COMPACT_PROFIT_CALCULATOR.get()) {
            itemCount = (int) ProfitManager.getCompactDrops(mode).values()
                    .stream().filter(v -> v != 0).count();
        } else {
            itemCount = ProfitManager.getActiveDrops(mode).size();
        }

        h += itemCount > 0 ? itemCount * ROW_H : 24f;
        if (showFarmingXp()) {
            int rows = 1; // header (level + overall progress to 60)
            if (AetherConfig.FARMING_HUD_XP_RATE.get()) rows++;
            if (AetherConfig.FARMING_HUD_ETA_NEXT.get()) rows++;
            if (AetherConfig.FARMING_HUD_ETA_MAX.get()) rows++;
            h += 10f + rows * ROW_H + FARM_BAR_H + 4f; // separator + rows + progress bar
        }
        h += 8f;  // bottom padding
        return h;
    }

    private boolean showFarmingXp() {
        return isSession()
                && AetherConfig.FARMING_XP_HUD.get()
                && dev.aether.modules.profit.helpers.FarmingXpTracker.hasData();
    }

    private boolean showGraph() {
        return isSession() && AetherConfig.SESSION_PROFIT_GRAPH.get();
    }

    // -- Rendering -------------------------------------------------------------

    @Override
    protected void renderElement(NVGRenderer nvg, boolean editMode) {
        float ph = computeHeight();
        HudStyle.panel(nvg, W, ph);
        HudStyle.header(nvg, W, title(), "COINS");
        float ry = HudStyle.CONTENT_Y;
        long total = ProfitManager.getTotalProfit(mode);
        float contentWidth = W - PAD_H * 2;
        nvg.roundedRect(PAD_H, ry, contentWidth, 41f, 5f, Theme.HUD_BAR_BG);
        nvg.text(Fonts.REGULAR, "Total Profit", PAD_H + 8f, ry + 6f, 9f, Theme.HUD_LABEL);
        float totalWidth = isSession() ? contentWidth * 0.53f : contentWidth - 16f;
        HudStyle.text(nvg, Fonts.BOLD, fmt(total), PAD_H + 8f, ry + 19f, totalWidth - 8f, 15f,
                total < 0 ? Theme.HUD_ERROR : Theme.HUD_VALUE);
        if (isSession()) {
            float rateX = PAD_H + contentWidth * 0.57f;
            nvg.rect(rateX - 8f, ry + 8f, 0.7f, 25f, Theme.HUD_SEP);
            long sessionMs = MacroStateManager.getSessionRunningTime();
            long cph = sessionMs > 0 ? (long) (total / (sessionMs / 3_600_000.0)) : 0;
            nvg.text(Fonts.REGULAR, "Coins per Hour", rateX, ry + 6f, 9f, Theme.HUD_LABEL);
            HudStyle.text(nvg, Fonts.MONO, fmt(cph), rateX, ry + 21f,
                    W - PAD_H - rateX - 8f, 12f, Theme.HUD_ACCENT);
        }
        ry += 50f;

        if (showGraph()) {
            long now = System.nanoTime();
            graph.render(nvg, PAD_H, ry, contentWidth, ProfitManager.getSessionProfitHistory(now),
                    AetherConfig.SESSION_PROFIT_GRAPH_MINUTES.get() * 60_000L, now);
            ry += ProfitGraph.HEIGHT;
        }

        // Profit rows
        float startRy = ry;
        if (AetherConfig.COMPACT_PROFIT_CALCULATOR.get()) {
            for (Map.Entry<String, Long> e : ProfitManager.getCompactDrops(mode).entrySet()) {
                if (e.getValue() != 0) {
                    int vc = e.getKey().equals("Costs") ? Theme.HUD_ERROR : Theme.HUD_VALUE;
                    row(nvg, ry, ProfitManager.getCompactCategoryLabel(e.getKey()),
                            fmt(e.getValue()), vc);
                    ry += ROW_H;
                }
            }
        } else {
            for (Map.Entry<String, Long> e : ProfitManager.getActiveDrops(mode).entrySet()) {
                String item    = e.getKey();
                long   count   = e.getValue();
                long   lineVal = (long) ProfitManager.getItemValue(item, count);
                String cName   = ProfitManager.getCategorizedName(item);
                String cDisp;
                if (item.equals("[Spray] Sprayonator")) {
                    cDisp = "x" + String.format("%,d", ProfitManager.getSprayQuantity(mode));
                } else if (item.startsWith("Pet XP (")) {
                    cDisp = String.format("%,d XP", count);
                } else {
                    cDisp = "x" + String.format("%,d", count);
                }
                int vc = item.equals("[Visitor] Visitor Cost") || item.equals("[Spray] Sprayonator")
                        ? Theme.HUD_ERROR : Theme.HUD_VALUE;
                row(nvg, ry, cName + " (" + cDisp + ")", fmt(lineVal), vc);
                ry += ROW_H;
            }
        }

        if (ry == startRy) {
            nvg.text(Fonts.REGULAR, "No tracked drops yet", PAD_H, ry + 4f, LABEL_SZ, Theme.HUD_LABEL);
            ry += 24f;
        }

        // Farming XP / progress to 60
        if (showFarmingXp()) {
            nvg.rect(PAD_H, ry + 1f, W - PAD_H * 2f, 1f, Theme.HUD_SEP);
            ry += 10f;

            boolean maxed = dev.aether.modules.profit.helpers.FarmingXpTracker.isMaxed();
            int level = dev.aether.modules.profit.helpers.FarmingXpTracker.getLevel();
            float prog = dev.aether.modules.profit.helpers.FarmingXpTracker.getProgressToMax();

            // Header: current level + overall progress to 60
            row(nvg, ry, "Farming " + level + " → 60",
                    String.format("%.2f%%", prog * 100f), Theme.HUD_VALUE);
            ry += ROW_H;

            if (AetherConfig.FARMING_HUD_XP_RATE.get()) {
                long perHour = dev.aether.modules.profit.helpers.FarmingXpTracker.getXpPerHour();
                String rateStr = maxed ? "MAX" : fmt(perHour);
                row(nvg, ry, "Farming XP/hr", rateStr, Theme.HUD_SUCCESS);
                ry += ROW_H;
            }

            if (AetherConfig.FARMING_HUD_ETA_NEXT.get()) {
                long etaNext = dev.aether.modules.profit.helpers.FarmingXpTracker.getEtaToNextLevelMs();
                String s = maxed ? "done" : (etaNext < 0 ? "---" : formatEta(etaNext));
                row(nvg, ry, "Next level (" + (level + 1) + ")", s, Theme.HUD_VALUE);
                ry += ROW_H;
            }

            if (AetherConfig.FARMING_HUD_ETA_MAX.get()) {
                long etaMax = dev.aether.modules.profit.helpers.FarmingXpTracker.getEtaToMaxMs();
                String s = maxed ? "done" : (etaMax < 0 ? "---" : formatEta(etaMax));
                row(nvg, ry, "Time to 60", s, Theme.HUD_VALUE);
                ry += ROW_H;
            }

            float bw = W - PAD_H * 2f;
            nvg.roundedRect(PAD_H, ry, bw, FARM_BAR_H, FARM_BAR_H / 2f, Theme.HUD_BAR_BG);
            float fw = bw * Math.max(0f, Math.min(1f, prog));
            if (fw > 0) nvg.roundedRect(PAD_H, ry, fw, FARM_BAR_H, FARM_BAR_H / 2f, Theme.HUD_ACCENT);
            ry += FARM_BAR_H + 4f;
        }

    }

    private void row(NVGRenderer nvg, float y, String label, String value, int valueColor) {
        float width = W - PAD_H * 2;
        String fittedValue = HudStyle.fit(nvg, Fonts.MONO, value, LABEL_SZ, width * 0.45f);
        float labelWidth = width - nvg.textWidth(Fonts.MONO, fittedValue, LABEL_SZ) - 10f;
        if (label.startsWith("[") && label.indexOf(']') > 0) {
            int close = label.indexOf(']');
            String tag = label.substring(1, close);
            float tagWidth = nvg.textWidth(Fonts.BOLD, LONGEST_CATEGORY_TAG, 8f) + 8f;
            nvg.roundedRect(PAD_H, y - 1f, tagWidth, 13f, 3f, HudStyle.alpha(Theme.HUD_ACCENT, 0.12f));
            nvg.textCentered(Fonts.BOLD, tag, PAD_H, y - 1f, tagWidth, 13f, 8f, Theme.HUD_ACCENT);
            HudStyle.text(nvg, Fonts.REGULAR, label.substring(close + 1).stripLeading(),
                    PAD_H + tagWidth + CATEGORY_TAG_GAP, y,
                    labelWidth - tagWidth - CATEGORY_TAG_GAP, LABEL_SZ, Theme.HUD_LABEL);
        } else {
            HudStyle.text(nvg, Fonts.REGULAR, label, PAD_H, y, labelWidth, LABEL_SZ, Theme.HUD_LABEL);
        }
        nvg.textRight(Fonts.MONO, fittedValue, PAD_H, y, width, LABEL_SZ, valueColor);
    }

    private static String fmt(long amount) { return String.format("%,d", amount); }

    private static String formatEta(long ms) {
        long totalSec = ms / 1_000L;
        long days = totalSec / 86_400L;
        long hours = (totalSec % 86_400L) / 3600L;
        long mins = (totalSec % 3600L) / 60L;
        long secs = totalSec % 60L;
        if (days > 0) return String.format("%dd %dh", days, hours);
        if (hours > 0) return String.format("%dh %dm", hours, mins);
        if (mins > 0) return String.format("%dm %ds", mins, secs);
        return String.format("%ds", secs);
    }
}
