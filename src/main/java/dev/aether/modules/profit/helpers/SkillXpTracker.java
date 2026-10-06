package dev.aether.modules.profit.helpers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.aether.macro.MacroState;
import dev.aether.macro.MacroStateManager;
import dev.aether.modules.profit.ProfitManager;
import dev.aether.util.NumberUtils;
import dev.aether.util.TablistUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

// the tab-list skills widget and action bar fraction provide direct absolute XP anchors
// at high levels the action bar degrades from (cur/max) to (percent), which alone cannot resolve an absolute xp value
public final class SkillXpTracker {

    // every skill shares one table; farming just keeps going past 50
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

    // maps a "needed for next level" value back to the level you are on
    private static final Map<Long, Integer> NEEDED_TO_LEVEL = buildNeededMap();

    // built after the tables above, which the constructor reads
    public static final SkillXpTracker FARMING = new SkillXpTracker("Farming", 60);
    public static final SkillXpTracker FISHING = new SkillXpTracker("Fishing", 50);
    private static final SkillXpTracker[] ALL = {FARMING, FISHING};

    private final String skill;
    private final int maxLevel;
    private final long xpToMax;
    private final Pattern abFraction;
    private final Pattern tabMax;
    private final Pattern tabFraction;
    private final Pattern tabPercent;

    private final Object lock = new Object();

    // Cross-thread readable scalars for the HUD.
    private volatile long sessionXpGained = 0L;
    private volatile int currentLevel = -1;
    private volatile long absoluteXp = -1L;
    private volatile long sessionStartAbsoluteXp = -1L;

    SkillXpTracker(String skill, int maxLevel) {
        this.skill = skill;
        this.maxLevel = maxLevel;
        this.xpToMax = XP_TO_LEVEL[maxLevel];
        String name = Pattern.quote(skill);
        abFraction = Pattern.compile(name + "\\s+\\(([\\d.,]+)/([\\d.,]+[kKmMbB]?)\\)");
        tabMax = Pattern.compile(name + "\\s+(\\d+):\\s+MAX", Pattern.CASE_INSENSITIVE);
        tabFraction = Pattern.compile(name + "\\s+(\\d+):\\s+([\\d.,]+)/([\\d.,]+[kKmMbB]?)");
        tabPercent = Pattern.compile(name + "\\s+(\\d+):\\s+([\\d.,]+)%");
    }

    // the skill the running macro levels, so the hud follows whichever module is on
    public static SkillXpTracker active() {
        return MacroStateManager.getCurrentState() == MacroState.State.FISHING ? FISHING : FARMING;
    }

    public static void onActionBarAll(Component component) {
        for (SkillXpTracker tracker : ALL) {
            tracker.onActionBar(component);
        }
    }

    public static void updateAllFromTablist(Minecraft client) {
        for (SkillXpTracker tracker : ALL) {
            tracker.updateFromTablist(client);
        }
    }

    public static void resetAll() {
        for (SkillXpTracker tracker : ALL) {
            tracker.reset();
        }
    }

    public static void resetAllLiveState() {
        for (SkillXpTracker tracker : ALL) {
            tracker.resetLiveState();
        }
    }

    public String getSkill() {
        return skill;
    }

    public int getMaxLevel() {
        return maxLevel;
    }

    // -- Action bar feed (called from the overlay-message hook) ----------------

    public void onActionBar(Component component) {
        if (component == null || !ProfitManager.isProfitTrackingActive()) {
            return;
        }
        String s = TablistUtils.stripColors(component.getString());
        if (s.isEmpty() || !s.contains(skill)) {
            return;
        }

        Matcher frac = abFraction.matcher(s);
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
    public void updateFromTablist(Minecraft client) {
        if (client == null || client.getConnection() == null || !ProfitManager.isProfitTrackingActive()) {
            return;
        }
        List<String> lines = TablistUtils.getTabLines(client);
        for (String line : lines) {
            Matcher max = tabMax.matcher(line);
            if (max.find()) {
                setAnchor(maxLevel, 0L);
                return;
            }
            Matcher frac = tabFraction.matcher(line);
            if (frac.find()) {
                try {
                    int level = Integer.parseInt(frac.group(1));
                    long cur = (long) parseNum(frac.group(2));
                    setAnchor(level, cur);
                    return;
                } catch (NumberFormatException ignored) {
                }
            }
            Matcher pct = tabPercent.matcher(line);
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

    public void reset() {
        synchronized (lock) {
            sessionXpGained = 0L;
            currentLevel = -1;
            absoluteXp = -1L;
            sessionStartAbsoluteXp = -1L;
        }
    }

    // keeps the baseline, so a persisted session still counts its gain from the original start
    public void resetLiveState() {
        synchronized (lock) {
            sessionXpGained = 0L;
            currentLevel = -1;
            absoluteXp = -1L;
        }
    }

    // -- HUD getters -----------------------------------------------------------

    public boolean hasData() {
        return absoluteXp >= 0 && currentLevel >= 0;
    }

    public boolean isMaxed() {
        return currentLevel >= maxLevel && absoluteXp >= xpToMax;
    }

    public boolean isPaused() {
        return MacroStateManager.getSessionRunningTime() <= 0L;
    }

    public int getLevel() {
        return currentLevel;
    }

    public long getXpPerHour() {
        long sessionMs = MacroStateManager.getSessionRunningTime();
        return sessionMs > 0
                ? (long) (sessionXpGained * 3_600_000.0 / sessionMs)
                : 0L;
    }

    public long getSessionXpGained() {
        return sessionXpGained;
    }

    public long getRemainingToMax() {
        return Math.max(0L, xpToMax - Math.max(0L, absoluteXp));
    }

    public float getProgressToMax() {
        if (absoluteXp <= 0) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, (float) absoluteXp / (float) xpToMax));
    }

    // -1 when unknown
    public long getEtaToMaxMs() {
        long rate = getXpPerHour();
        if (rate <= 0 || isMaxed()) {
            return -1L;
        }
        return (long) (getRemainingToMax() / (double) rate * 3_600_000.0);
    }

    public long getXpIntoLevel() {
        if (absoluteXp < 0 || currentLevel < 0) {
            return 0L;
        }
        return Math.max(0L, absoluteXp - XP_TO_LEVEL[Math.min(currentLevel, maxLevel)]);
    }

    public long getXpForNextLevel() {
        if (currentLevel < 0 || currentLevel >= maxLevel) {
            return 0L;
        }
        return LEVEL_INCREMENTS[currentLevel];
    }

    public long getRemainingToNextLevel() {
        long need = getXpForNextLevel();
        return need <= 0 ? 0L : Math.max(0L, need - getXpIntoLevel());
    }

    // -1 when unknown
    public long getEtaToNextLevelMs() {
        long rate = getXpPerHour();
        if (rate <= 0 || currentLevel >= maxLevel) {
            return -1L;
        }
        return (long) (getRemainingToNextLevel() / (double) rate * 3_600_000.0);
    }

    // -- Internals -------------------------------------------------------------

    void setAnchor(int level, long currentXpInLevel) {
        if (level < 0 || level > maxLevel) {
            return;
        }
        synchronized (lock) {
            long nextAbsoluteXp = XP_TO_LEVEL[level] + Math.max(0L, currentXpInLevel);

            // The tab list can arrive first on startup and establish level 60 with
            // no overflow. The first action-bar x/0 value is the existing post-cap
            // total, so use it as the session baseline instead of counting it.
            boolean initializingPostCapBaseline = level == maxLevel
                    && currentXpInLevel > 0L
                    && absoluteXp == xpToMax
                    && sessionStartAbsoluteXp == xpToMax
                    && sessionXpGained == 0L;
            if (initializingPostCapBaseline) {
                sessionStartAbsoluteXp = nextAbsoluteXp;
            }

            // At max level the tab list reports MAX, while the action bar can still
            // report the post-cap XP as x/0. Do not let the tab-list anchor erase it.
            if (level == maxLevel && currentXpInLevel == 0L && absoluteXp > nextAbsoluteXp) {
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

    int levelForNeeded(long needed) {
        if (needed == 0L) {
            return maxLevel;
        }
        Integer exact = NEEDED_TO_LEVEL.get(needed);
        if (exact != null && exact < maxLevel) {
            return exact;
        }
        // Suffixed/rounded values (e.g. "2.8M") won't match exactly; pick nearest.
        int best = -1;
        long bestDelta = Long.MAX_VALUE;
        for (int level = 0; level < maxLevel; level++) {
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
        long[] table = new long[LEVEL_INCREMENTS.length + 1];
        long cum = 0L;
        for (int level = 1; level <= LEVEL_INCREMENTS.length; level++) {
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
