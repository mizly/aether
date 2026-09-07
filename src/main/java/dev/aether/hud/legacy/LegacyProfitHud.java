package dev.aether.hud.legacy;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroStateManager;
import dev.aether.modules.profit.ProfitManager;
import dev.aether.renderer.NVGRenderer;
import dev.aether.ui.theme.Theme;
import dev.aether.ui.util.Fonts;

import java.util.Map;

/**
 * Pre-Ryn rendering for the Session/Lifetime/Daily profit HUD. Preserves the
 * three theme styles and the compact/expanded profit rows.
 */
public final class LegacyProfitHud {
    public static final float W        = 280f;
    public static final float PAD_H    = 8f;
    public static final float PAD_V    = 6f;
    public static final float TITLE_SZ = 12f;
    public static final float LABEL_SZ = 10f;
    public static final float ROW_H    = 16f;
    public static final float CORNER   = 6f;
    private static final String LONGEST_CATEGORY_TAG = "[VISITOR]";
    private static final float CATEGORY_TAG_GAP = 3f;
    private static final float FARM_BAR_H = 4f;

    private LegacyProfitHud() {}

    public static float computeHeight(String mode) {
        boolean sleek = AetherConfig.HUD_THEME.get() == 2;
        float h = PAD_V + TITLE_SZ + 3f;
        if (!sleek) h += 1f;
        h += 8f;

        int itemCount;
        if (AetherConfig.COMPACT_PROFIT_CALCULATOR.get()) {
            itemCount = (int) ProfitManager.getCompactDrops(mode).values()
                    .stream().filter(v -> v != 0).count();
        } else {
            itemCount = ProfitManager.getActiveDrops(mode).size();
        }

        if (itemCount > 0) {
            h += itemCount * ROW_H + 4f + ROW_H;
            if (isSession(mode)) h += ROW_H;
        } else {
            h += ROW_H;
        }
        if (showFarmingXp(mode)) {
            int rows = 1;
            if (AetherConfig.FARMING_HUD_XP_RATE.get()) rows++;
            if (AetherConfig.FARMING_HUD_ETA_NEXT.get()) rows++;
            if (AetherConfig.FARMING_HUD_ETA_MAX.get()) rows++;
            h += 10f + rows * ROW_H + FARM_BAR_H + 4f;
        }
        h += 8f;
        return h;
    }

    private static boolean isSession(String mode) {
        return "session".equals(mode);
    }

    private static boolean showFarmingXp(String mode) {
        return isSession(mode)
                && AetherConfig.FARMING_XP_HUD.get()
                && dev.aether.modules.profit.helpers.FarmingXpTracker.hasData();
    }

    private static String title(String mode) {
        return switch (mode) {
            case "lifetime" -> "Lifetime Profit";
            case "daily"    -> "Daily Profit";
            default         -> "Session Profit";
        };
    }

    public static void renderElement(NVGRenderer nvg, String mode,
                                     boolean isDragging, boolean isResizing, boolean editMode) {
        float ph = computeHeight(mode);
        boolean mod   = AetherConfig.HUD_THEME.get() == 1;
        boolean sleek = AetherConfig.HUD_THEME.get() == 2;
        int border = isDragging ? Theme.HUD_ACCENT : isResizing ? Theme.HUD_WARNING : Theme.HUD_BORDER;

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

        String title  = title(mode);
        float  titleX = (mod || sleek) ? PAD_H + 5f
                : (W - nvg.textWidth(Fonts.BOLD, title, TITLE_SZ)) / 2f;
        nvg.text(Fonts.BOLD, title, titleX, PAD_V, TITLE_SZ, Theme.HUD_TITLE);

        float ry = PAD_V + TITLE_SZ + 3f;
        if (!sleek) {
            nvg.rect(PAD_H, ry, W - PAD_H * 2f, 1f, Theme.HUD_SEP);
        }
        ry += 8f;

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

        if (ry > startRy) {
            nvg.rect(PAD_H, ry + 1f, W - PAD_H * 2f, 1f, Theme.HUD_SEP);
            ry += 10f;
            long total = ProfitManager.getTotalProfit(mode);
            row(nvg, ry, "Total Profit", fmt(total), 0xFFFFAA00);
            ry += ROW_H;
            if (isSession(mode)) {
                long sesMs = MacroStateManager.getSessionRunningTime();
                long cph   = sesMs > 0 ? (long)(total / (sesMs / 3_600_000.0)) : 0;
                row(nvg, ry, "Coins per Hour", fmt(cph), 0xFF55FFFF);
                ry += ROW_H;
            }
        }

        if (showFarmingXp(mode)) {
            nvg.rect(PAD_H, ry + 1f, W - PAD_H * 2f, 1f, Theme.HUD_SEP);
            ry += 10f;

            boolean maxed = dev.aether.modules.profit.helpers.FarmingXpTracker.isMaxed();
            int level = dev.aether.modules.profit.helpers.FarmingXpTracker.getLevel();
            float prog = dev.aether.modules.profit.helpers.FarmingXpTracker.getProgressToMax();

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
            String hint = isDragging ? "moving..."
                        : isResizing ? "resizing..."
                        : "drag  •  ctrl+drag to resize";
            nvg.textCentered(Fonts.REGULAR, hint, 0, ry + 4f, W, 12f, 9f, Theme.HUD_LABEL);
        }
    }

    private static void row(NVGRenderer nvg, float y, String label, String value, int vc) {
        row(nvg, y, label, value, vc, Theme.HUD_LABEL);
    }

    private static void row(NVGRenderer nvg, float y, String label, String value, int vc, int labelColor) {
        drawCategoryLabel(nvg, label, PAD_H + 5f, y, labelColor);
        nvg.textRight(Fonts.MONO, value, PAD_H, y, W - PAD_H * 2f, LABEL_SZ, vc);
    }

    private static int compactCategoryColor(String category) {
        return switch (category) {
            case "Crops" -> 0xFFFFFF55;
            case "Shards" -> 0xFFAA55FF;
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
            case "[SHARD]" -> 0xFFAA55FF;
            case "[PEST]", "[COST]" -> 0xFFFF5555;
            case "[PET]" -> 0xFFFFAA00;
            case "[MISC]" -> 0xFF55FFFF;
            case "[VISITOR]" -> 0xFFFF55FF;
            default -> Theme.HUD_LABEL;
        };
    }

    private static void drawCategoryLabel(NVGRenderer nvg, String label, float x, float y, int fallbackColor) {
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
