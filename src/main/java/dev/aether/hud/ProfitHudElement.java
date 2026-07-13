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

    public static final float W        = 280f;
    private static final float PAD_H   = 8f;
    private static final float PAD_V   = 6f;
    private static final float TITLE_SZ = 12f;
    private static final float LABEL_SZ = 10f;
    private static final float ROW_H   = 16f;
    private static final float CORNER  = 6f;
    private static final String LONGEST_CATEGORY_TAG = "[VISITOR]";
    private static final float CATEGORY_TAG_GAP = 3f;
    private static final float FARM_BAR_H = 4f;

    /** Panel mode: {@code "session"}, {@code "lifetime"}, or {@code "daily"}. */
    private final String mode;

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
        boolean sleek = AetherConfig.HUD_THEME.get() == 2;
        float h = PAD_V + TITLE_SZ + 3f;
        if (!sleek) h += 1f;   // separator
        h += 8f;               // gap after separator

        int itemCount;
        if (AetherConfig.COMPACT_PROFIT_CALCULATOR.get()) {
            itemCount = (int) ProfitManager.getCompactDrops(mode).values()
                    .stream().filter(v -> v != 0).count();
        } else {
            itemCount = ProfitManager.getActiveDrops(mode).size();
        }

        if (itemCount > 0) {
            h += itemCount * ROW_H + 4f + ROW_H;   // rows + separator + total
            if (isSession()) h += ROW_H;            // coins-per-hour
        } else {
            h += ROW_H;
        }
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

    // -- Rendering -------------------------------------------------------------

    @Override
    protected void renderElement(NVGRenderer nvg, boolean editMode) {
        float ph = computeHeight();
        boolean mod   = AetherConfig.HUD_THEME.get() == 1;
        boolean sleek = AetherConfig.HUD_THEME.get() == 2;
        int border = isDragging() ? BORDER_DRAG : isResizing() ? BORDER_RESIZE : Theme.HUD_BORDER;

        if (sleek) {
            nvg.roundedRect(0, 0, W, ph, CORNER, Theme.withAlpha(Theme.HUD_BG, 0xCC));
            nvg.rectOutline(0, 0, W, ph, CORNER, 1f, Theme.HUD_BORDER);
        } else if (mod) {
            if (editMode) nvg.rect(-1, -1, W + 2, ph + 2, border);
            nvg.rect(0, 0, W, ph, Theme.HUD_BG);
            nvg.rect(0, 0, 3f, ph, Theme.HUD_ACCENT);
        } else {
            if (editMode) nvg.roundedRect(-1, -1, W + 2, ph + 2, CORNER + 1, border);
            nvg.shadow(0, 0, W, ph, CORNER, 12f, Theme.withAlpha(0xFF000000, 0.5f));
            nvg.roundedRect(0, 0, W, ph, CORNER, Theme.HUD_BG);
        }

        // Title
        String title  = title();
        float  titleX = (mod || sleek) ? PAD_H + 5f
                : (W - nvg.textWidth(Fonts.BOLD, title, TITLE_SZ)) / 2f;
        nvg.text(Fonts.BOLD, title, titleX, PAD_V, TITLE_SZ, Theme.HUD_TITLE);

        float ry = PAD_V + TITLE_SZ + 3f;
        if (!sleek) {
            nvg.rect(PAD_H, ry, W - PAD_H * 2f, 1f, Theme.HUD_SEP);
        }
        ry += 8f;

        // Profit rows
        float startRy = ry;
        if (AetherConfig.COMPACT_PROFIT_CALCULATOR.get()) {
            for (Map.Entry<String, Long> e : ProfitManager.getCompactDrops(mode).entrySet()) {
                if (e.getValue() != 0) {
                    int vc = e.getKey().equals("Costs") ? 0xFFFF5555 : 0xFFFFFF55;
                    row(nvg, ry, ProfitManager.getCompactCategoryLabel(e.getKey()),
                            fmt(e.getValue()), vc, compactCategoryColor(e.getKey()));
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
                int vc;
                if (item.equals("[Visitor] Visitor Cost") || item.equals("[Spray] Sprayonator")) {
                    vc = 0xFFFF5555;
                } else if (item.startsWith("[Visitor] ")) {
                    vc = 0xFFFFFF55;
                } else {
                    vc = ProfitManager.isPredefinedTrackedItem(item) ? 0xFFFFFF55 : Theme.HUD_VALUE;
                }
                row(nvg, ry, cName + " (" + cDisp + ")", fmt(lineVal), vc);
                ry += ROW_H;
            }
        }

        // Total + CPH
        if (ry > startRy) {
            nvg.rect(PAD_H, ry + 1f, W - PAD_H * 2f, 1f, Theme.HUD_SEP);
            ry += 10f;
            long total = ProfitManager.getTotalProfit(mode);
            row(nvg, ry, "Total Profit", fmt(total), 0xFFFFAA00);
            ry += ROW_H;
            if (isSession()) {
                long sesMs = MacroStateManager.getSessionRunningTime();
                long cph   = sesMs > 0 ? (long)(total / (sesMs / 3_600_000.0)) : 0;
                row(nvg, ry, "Coins per Hour", fmt(cph), 0xFF55FFFF);
                ry += ROW_H;
            }
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
                    String.format("%.2f%%", prog * 100f), 0xFFFFFF55);
            ry += ROW_H;

            if (AetherConfig.FARMING_HUD_XP_RATE.get()) {
                long perHour = dev.aether.modules.profit.helpers.FarmingXpTracker.getXpPerHour();
                String rateStr = maxed ? "MAX" : fmt(perHour);
                row(nvg, ry, "Farming XP/hr", rateStr, 0xFF55FF55);
                ry += ROW_H;
            }

            if (AetherConfig.FARMING_HUD_ETA_NEXT.get()) {
                long etaNext = dev.aether.modules.profit.helpers.FarmingXpTracker.getEtaToNextLevelMs();
                String s = maxed ? "done" : (etaNext < 0 ? "---" : formatEta(etaNext));
                row(nvg, ry, "Next level (" + (level + 1) + ")", s, 0xFFFFAA00);
                ry += ROW_H;
            }

            if (AetherConfig.FARMING_HUD_ETA_MAX.get()) {
                long etaMax = dev.aether.modules.profit.helpers.FarmingXpTracker.getEtaToMaxMs();
                String s = maxed ? "done" : (etaMax < 0 ? "---" : formatEta(etaMax));
                row(nvg, ry, "Time to 60", s, 0xFF55FFFF);
                ry += ROW_H;
            }

            float bw = W - PAD_H * 2f;
            nvg.roundedRect(PAD_H, ry, bw, FARM_BAR_H, FARM_BAR_H / 2f, Theme.HUD_BAR_BG);
            float fw = bw * Math.max(0f, Math.min(1f, prog));
            if (fw > 0) nvg.roundedRect(PAD_H, ry, fw, FARM_BAR_H, FARM_BAR_H / 2f, Theme.HUD_ACCENT);
            ry += FARM_BAR_H + 4f;
        }

        if (editMode) {
            String hint = isDragging() ? "moving..."
                        : isResizing() ? "resizing..."
                        : "drag  \u2022  ctrl+drag to resize";
            nvg.textCentered(Fonts.REGULAR, hint, 0, ry + 4f, W, 12f, 9f, Theme.HUD_LABEL);
        }
    }

    // -- Helpers ---------------------------------------------------------------

    private void row(NVGRenderer nvg, float y, String label, String value, int vc) {
        row(nvg, y, label, value, vc, Theme.HUD_LABEL);
    }

    private void row(NVGRenderer nvg, float y, String label, String value, int vc, int labelColor) {
        drawCategoryLabel(nvg, label, PAD_H + 5f, y, labelColor);
        nvg.textRight(Fonts.MONO, value, PAD_H, y, W - PAD_H * 2f, LABEL_SZ, vc);
    }

    private static int compactCategoryColor(String category) {
        return switch (category) {
            case "Crops" -> 0xFFFFFF55;
            case "Pest Items" -> 0xFFFF5555;
            case "Pets" -> 0xFFFFAA00;
            case "Feast" -> 0xFFFFFF55;
            case "Misc Drops" -> 0xFF55FFFF;
            case "Visitor" -> 0xFFFF55FF;
            case "Costs" -> 0xFFFF5555;
            default -> Theme.HUD_LABEL;
        };
    }

    private static int categoryTagColor(String tag) {
        return switch (tag) {
            case "[CROP]", "[FEAST]" -> 0xFFFFFF55;
            case "[PEST]", "[COST]" -> 0xFFFF5555;
            case "[PET]" -> 0xFFFFAA00;
            case "[MISC]" -> 0xFF55FFFF;
            case "[VISITOR]" -> 0xFFFF55FF;
            default -> Theme.HUD_LABEL;
        };
    }

    private void drawCategoryLabel(NVGRenderer nvg, String label, float x, float y, int fallbackColor) {
        if (!label.startsWith("[")) {
            nvg.text(Fonts.REGULAR, label, x, y, LABEL_SZ, fallbackColor);
            return;
        }

        int close = label.indexOf(']');
        if (close <= 0) {
            nvg.text(Fonts.REGULAR, label, x, y, LABEL_SZ, fallbackColor);
            return;
        }

        String tag = label.substring(0, close + 1);
        String rest = label.substring(close + 1);
        int tagColor = categoryTagColor(tag);
        nvg.text(Fonts.BOLD, tag, x, y, LABEL_SZ, tagColor);
        float itemX = x + nvg.textWidth(Fonts.BOLD, LONGEST_CATEGORY_TAG, LABEL_SZ) + CATEGORY_TAG_GAP;
        nvg.text(Fonts.REGULAR, rest, itemX, y, LABEL_SZ, fallbackColor);
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
