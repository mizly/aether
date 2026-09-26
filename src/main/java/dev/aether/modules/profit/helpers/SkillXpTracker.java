package dev.aether.modules.profit.helpers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.aether.macro.MacroStateManager;
import dev.aether.modules.profit.ProfitManager;
import dev.aether.util.NumberUtils;
import dev.aether.util.TablistUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

// the tab-list skills widget and action bar fraction provide direct absolute XP anchors
// at high levels the action bar degrades from (cur/max) to (percent), which alone cannot resolve an absolute xp value
public final class FarmingXpTracker {

    public static final int MAX_LEVEL = 60;

    // index 0 is level 0->1
    private static final long[] LEVEL_INCREMENTS = {
            50L, 125L, 200L, 300L, 500L, 750L, 1000L, 1500L, 2000L, 3500L,
            5000L, 7500L, 10000L, 15000L, 20000L, 30000L, 50000L, 75000L, 100000L, 200000L,
            300000L, 400000L, 500000L, 600000L, 700000L, 800000L, 900000L, 1000000L, 1100000L, 1200000L,
            1300000L, 1400000L, 1500000L, 1600000L, 1700000L, 1800000L, 1900000L, 2000000L, 2100000L, 2200000L,
            2300000L, 2400000L, 2500000L, 2600000L, 2750000L, 2900000L, 3100000L, 3400000L, 3700000L, 4000000L,
            4300000L, 4600000L, 4900000L, 5200000L, 5500000L, 5800000L, 6100000L, 6400000L, 6700000L, 7000000L,
    };

    // index = level, 0..60
    private static final long[] XP_TO_LEVEL = buildCumulative();
    public static final long XP_TO_MAX = XP_TO_LEVEL[MAX_LEVEL];

    // maps a "needed for next level" value back to the level you are on
    private static final Map<Long, Integer> NEEDED_TO_LEVEL = buildNeededMap();

    // -- Patterns --------------------------------------------------------------

    private static final Pattern AB_FRACTION = Pattern.compile(
            "Farming\\s+\\(([\\d.,]+)/([\\d.,]+[kKmMbB]?)\\)");

    private static final Pattern TAB_MAX = Pattern.compile(
            "Farming\\s+(\\d+):\\s+MAX", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAB_FRACTION = Pattern.compile(
            "Farming\\s+(\\d+):\\s+([\\d.,]+)/([\\d.,]+[kKmMbB]?)");
    private static final Pattern TAB_PERCENT = Pattern.compile(
            "Farming\\s+(\\d+):\\s+([\\d.,]+)%");

    private static final Object LOCK = new Object();

    // Cross-thread readable scalars for the HUD.
    private static volatile long sessionXpGained = 0L;
    private static volatile int currentLevel = -1;
    private static volatile long absoluteXp = -1L;
    private static volatile long sessionStartAbsoluteXp = -1L;

    private FarmingXpTracker() {}

    // -- Action bar feed (called from the overlay-message hook) ----------------

    public static void onActionBar(Component component) {
        if (component == null || !ProfitManager.isProfitTrackingActive()) {
            return;
        }
        String s = TablistUtils.stripColors(component.getString());
        if (s.isEmpty() || !s.contains("Farming")) {
            return;
        }

        Matcher frac = AB_FRACTION.matcher(s);
        if (frac.find()) {
            try {
                long cur = (long) parseNum(frac.group(1));
                long needed = NumberUtils.parseShorthand(frac.group(2));
                int level = levelForNeeded(needed);
                if (level >= 0) {
                    setAnchor(level, cur);
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    // -- Tab list / per-tick update (called from ProfitLiveTracker) ------------

    // anchors absolute level and progress from the tab-list skills widget when it is there
    public static void updateFromTablist(Minecraft client) {
        if (client == null || client.getConnection() == null || !ProfitManager.isProfitTrackingActive()) {
            return;
        }
        List<String> lines = TablistUtils.getTabLines(client);
        for (String line : lines) {
            Matcher max = TAB_MAX.matcher(line);
            if (max.find()) {
                setAnchor(MAX_LEVEL, 0L);
                return;
            }
            Matcher frac = TAB_FRACTION.matcher(line);
            if (frac.find()) {
                try {
                    int level = Integer.parseInt(frac.group(1));
                    long cur = (long) parseNum(frac.group(2));
                    setAnchor(level, cur);
                    return;
                } catch (NumberFormatException ignored) {
                }
            }
            Matcher pct = TAB_PERCENT.matcher(line);
            if (pct.find()) {
                try {
                    int level = Integer.parseInt(pct.group(1));
                    double percent = parseNum(pct.group(2));
                    long inc = level < LEVEL_INCREMENTS.length ? LEVEL_INCREMENTS[level] : 0L;
                    setAnchor(level, (long) (inc * percent / 100.0));
                    return;
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }

    public static void reset() {
        synchronized (LOCK) {
            sessionXpGained = 0L;
            currentLevel = -1;
            absoluteXp = -1L;
            sessionStartAbsoluteXp = -1L;
        }
    }

    /**
     * Clears the live sample while keeping the baseline for a persisted macro session.
     * The next anchor will calculate the total gain from the original session start.
     */
    public static void resetLiveState() {
        synchronized (LOCK) {
            sessionXpGained = 0L;
            currentLevel = -1;
            absoluteXp = -1L;
        }
    }

    // -- HUD getters -----------------------------------------------------------

    public static boolean hasData() {
        return absoluteXp >= 0 && currentLevel >= 0;
    }

    public static boolean isMaxed() {
        return currentLevel >= MAX_LEVEL && absoluteXp >= XP_TO_MAX;
    }

    public static boolean isPaused() {
        return MacroStateManager.getSessionRunningTime() <= 0L;
    }

    public static int getLevel() {
        return currentLevel;
    }

    public static long getXpPerHour() {
        long sessionMs = MacroStateManager.getSessionRunningTime();
        return sessionMs > 0
                ? (long) (sessionXpGained * 3_600_000.0 / sessionMs)
                : 0L;
    }

    public static long getSessionXpGained() {
        return sessionXpGained;
    }

    public static long getRemainingToMax() {
        return Math.max(0L, XP_TO_MAX - Math.max(0L, absoluteXp));
    }

    public static float getProgressToMax() {
        if (absoluteXp <= 0) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, (float) absoluteXp / (float) XP_TO_MAX));
    }

    // -1 when unknown
    public static long getEtaToMaxMs() {
        long rate = getXpPerHour();
        if (rate <= 0 || isMaxed()) {
            return -1L;
        }
        return (long) (getRemainingToMax() / (double) rate * 3_600_000.0);
    }

    public static long getXpIntoLevel() {
        if (absoluteXp < 0 || currentLevel < 0) {
            return 0L;
        }
        return Math.max(0L, absoluteXp - XP_TO_LEVEL[Math.min(currentLevel, MAX_LEVEL)]);
    }

    public static long getXpForNextLevel() {
        if (currentLevel < 0 || currentLevel >= LEVEL_INCREMENTS.length) {
            return 0L;
        }
        return LEVEL_INCREMENTS[currentLevel];
    }

    public static long getRemainingToNextLevel() {
        long need = getXpForNextLevel();
        return need <= 0 ? 0L : Math.max(0L, need - getXpIntoLevel());
    }

    // -1 when unknown
    public static long getEtaToNextLevelMs() {
        long rate = getXpPerHour();
        if (rate <= 0 || currentLevel >= MAX_LEVEL) {
            return -1L;
        }
        return (long) (getRemainingToNextLevel() / (double) rate * 3_600_000.0);
    }

    // -- Internals -------------------------------------------------------------

    static void setAnchor(int level, long currentXpInLevel) {
        if (level < 0 || level > MAX_LEVEL) {
            return;
        }
        synchronized (LOCK) {
            long nextAbsoluteXp = XP_TO_LEVEL[level] + Math.max(0L, currentXpInLevel);

            // The tab list can arrive first on startup and establish level 60 with
            // no overflow. The first action-bar x/0 value is the existing post-cap
            // total, so use it as the session baseline instead of counting it.
            boolean initializingPostCapBaseline = level == MAX_LEVEL
                    && currentXpInLevel > 0L
                    && absoluteXp == XP_TO_MAX
                    && sessionStartAbsoluteXp == XP_TO_MAX
                    && sessionXpGained == 0L;
            if (initializingPostCapBaseline) {
                sessionStartAbsoluteXp = nextAbsoluteXp;
            }

            // At max level the tab list reports MAX, while the action bar can still
            // report the post-cap XP as x/0. Do not let the tab-list anchor erase it.
            if (level == MAX_LEVEL && currentXpInLevel == 0L && absoluteXp > nextAbsoluteXp) {
                nextAbsoluteXp = absoluteXp;
            }

            currentLevel = level;
            absoluteXp = nextAbsoluteXp;
            if (sessionStartAbsoluteXp < 0L) {
                sessionStartAbsoluteXp = absoluteXp;
            }
            sessionXpGained = Math.max(sessionXpGained,
                    Math.max(0L, absoluteXp - sessionStartAbsoluteXp));
        }
    }

    static int levelForNeeded(long needed) {
        if (needed == 0L) {
            return MAX_LEVEL;
        }
        Integer exact = NEEDED_TO_LEVEL.get(needed);
        if (exact != null) {
            return exact;
        }
        // Suffixed/rounded values (e.g. "2.8M") won't match exactly; pick nearest.
        int best = -1;
        long bestDelta = Long.MAX_VALUE;
        for (int level = 0; level < LEVEL_INCREMENTS.length; level++) {
            long delta = Math.abs(LEVEL_INCREMENTS[level] - needed);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = level;
            }
        }
        // Reject obviously wrong matches (>5% off) to avoid bad anchors.
        return bestDelta <= needed * 0.05 ? best : -1;
    }

    private static double parseNum(String s) {
        return Double.parseDouble(s.replace(",", "").trim());
    }

    private static long[] buildCumulative() {
        long[] table = new long[MAX_LEVEL + 1];
        long cum = 0L;
        for (int level = 1; level <= MAX_LEVEL; level++) {
            cum += LEVEL_INCREMENTS[level - 1];
            table[level] = cum;
        }
        return table;
    }

    private static Map<Long, Integer> buildNeededMap() {
        Map<Long, Integer> map = new HashMap<>();
        for (int level = 0; level < LEVEL_INCREMENTS.length; level++) {
            map.put(LEVEL_INCREMENTS[level], level);
        }
        return map;
    }
}
