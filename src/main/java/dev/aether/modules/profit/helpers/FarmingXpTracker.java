package dev.aether.modules.profit.helpers;

import dev.aether.modules.profit.ProfitManager;
import dev.aether.util.NumberUtils;
import dev.aether.util.TablistUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks Farming skill XP and computes a live XP/hour rate plus progress toward
 * level 60.
 *
 * <p>Inspired by SkyHanni's {@code SkillApi}: the action bar drives live XP gains
 * (its {@code +N Farming (...)} message), while the tab list "Skills" widget and
 * the action bar fraction form anchor the <em>absolute</em> level/progress. This
 * matters because at high levels the action bar degrades from {@code (cur/max)} to
 * {@code (percent)}, which alone cannot resolve an absolute XP value.</p>
 *
 * <p>XP/hour uses a rolling per-second window (like SkyHanni) so the rate reflects
 * <em>current</em> farming speed and pauses when farming stops.</p>
 */
public final class FarmingXpTracker {

    public static final int MAX_LEVEL = 60;

    /** XP required to go from level L to L+1 (index 0 = level 0->1). */
    private static final long[] LEVEL_INCREMENTS = {
            50L, 125L, 200L, 300L, 500L, 750L, 1000L, 1500L, 2000L, 3500L,
            5000L, 7500L, 10000L, 15000L, 20000L, 30000L, 50000L, 75000L, 100000L, 200000L,
            300000L, 400000L, 500000L, 600000L, 700000L, 800000L, 900000L, 1000000L, 1100000L, 1200000L,
            1300000L, 1400000L, 1500000L, 1600000L, 1700000L, 1800000L, 1900000L, 2000000L, 2100000L, 2200000L,
            2300000L, 2400000L, 2500000L, 2600000L, 2750000L, 2900000L, 3100000L, 3400000L, 3700000L, 4000000L,
            4300000L, 4600000L, 4900000L, 5200000L, 5500000L, 5800000L, 6100000L, 6400000L, 6700000L, 7000000L,
    };

    /** Cumulative XP to <em>reach</em> each level (index = level, 0..60). */
    private static final long[] XP_TO_LEVEL = buildCumulative();
    /** Total XP required to reach level 60 (= 111,672,425). */
    public static final long XP_TO_MAX = XP_TO_LEVEL[MAX_LEVEL];

    /** Maps a "needed for next level" value back to the level you are currently on. */
    private static final Map<Long, Integer> NEEDED_TO_LEVEL = buildNeededMap();

    // -- Patterns --------------------------------------------------------------

    private static final Pattern AB_GAIN = Pattern.compile("\\+([\\d.,]+)\\s+Farming");
    private static final Pattern AB_FRACTION = Pattern.compile(
            "Farming\\s+\\(([\\d.,]+)/([\\d.,]+[kKmMbB]?)\\)");

    private static final Pattern TAB_MAX = Pattern.compile(
            "Farming\\s+(\\d+):\\s+MAX", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAB_FRACTION = Pattern.compile(
            "Farming\\s+(\\d+):\\s+([\\d.,]+)/([\\d.,]+[kKmMbB]?)");
    private static final Pattern TAB_PERCENT = Pattern.compile(
            "Farming\\s+(\\d+):\\s+([\\d.,]+)%");

    // -- Rolling-window config -------------------------------------------------

    private static final int RATE_WINDOW = 60;         // seconds of history averaged (SkyHanni uses 30; 60
                                                       // smooths the spikes from Hypixel's coarse XP batches)
    private static final int RATE_GRACE_SECONDS = 5;   // keep the rate alive through short farming pauses
    private static final long RATE_TICK_MS = 1_000L;   // recompute once a second

    private static final Object LOCK = new Object();

    // Per-second absolute-XP deltas (newest first), capped at RATE_WINDOW. XP/hour is the window
    // average (sum * 3600 / size) -- the SkyHanni approach: steady on the coarse XP staircase. A
    // grace timer pads short pauses with zero-seconds instead of clearing the window (which dropped
    // the rate) or decaying between server updates (which sawtoothed it).
    private static final Deque<Double> xpGainQueue = new ArrayDeque<>();
    private static long lastRateMs = 0L;
    private static long lastTotalXp = -1L;
    private static int graceTimer = 0;

    // Cross-thread readable scalars for the HUD.
    private static volatile double sessionXpGained = 0.0;
    private static volatile double xpPerHour = 0.0;
    private static volatile boolean active = false;
    private static volatile int currentLevel = -1;
    private static volatile long absoluteXp = -1L;

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

        Matcher gain = AB_GAIN.matcher(s);
        if (!gain.find()) {
            return;
        }
        double gained;
        try {
            gained = parseNum(gain.group(1));
        } catch (NumberFormatException e) {
            return;
        }
        if (gained <= 0) {
            return;
        }

        synchronized (LOCK) {
            sessionXpGained += gained;
            // Keep the absolute anchor fresh between (cur/max) reads; the fraction below
            // and the tab list re-anchor it to server-truth whenever they appear.
            if (absoluteXp >= 0) {
                absoluteXp += (long) gained;
            }
        }

        // Refine the absolute anchor from the fraction form when present (low/mid
        // levels). The percent form lacks a level here; the tab list covers that.
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

    /** Anchors absolute level/progress from the tab-list Skills widget if present. */
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

    /** Advances the per-second rolling average. Call once per game tick; recomputes once a second. */
    public static void tick() {
        if (!ProfitManager.isProfitTrackingActive()) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            if (absoluteXp < 0) {
                return; // no absolute anchor yet
            }
            if (lastRateMs == 0L) {
                lastRateMs = now;        // seed the per-second baseline
                lastTotalXp = absoluteXp;
                return;
            }
            if (now - lastRateMs < RATE_TICK_MS) {
                return; // one bucket per second
            }
            lastRateMs = now;
            long delta = absoluteXp - lastTotalXp;
            lastTotalXp = absoluteXp;

            if (delta > 0L) {
                graceTimer = RATE_GRACE_SECONDS; // real gain: keep the window alive
                xpGainQueue.addFirst((double) delta);
                active = true;
            } else if (graceTimer > 0) {
                graceTimer--;                    // short pause: pad a zero-second, don't clear
                xpGainQueue.addFirst(0.0);
                active = true;
            } else {
                active = false;                  // idle past grace: freeze the last rate, keep the queue
                return;
            }

            while (xpGainQueue.size() > RATE_WINDOW) {
                xpGainQueue.removeLast();
            }
            double sum = 0.0;
            for (double g : xpGainQueue) {
                sum += g;
            }
            xpPerHour = xpGainQueue.isEmpty() ? 0.0 : sum * 3600.0 / xpGainQueue.size();
        }
    }

    public static void reset() {
        synchronized (LOCK) {
            xpGainQueue.clear();
            lastRateMs = 0L;
            lastTotalXp = -1L;
            graceTimer = 0;
            sessionXpGained = 0.0;
            xpPerHour = 0.0;
            active = false;
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
        return !active;
    }

    public static int getLevel() {
        return currentLevel;
    }

    public static long getXpPerHour() {
        return (long) xpPerHour;
    }

    public static long getSessionXpGained() {
        return (long) sessionXpGained;
    }

    public static long getRemainingToMax() {
        return Math.max(0L, XP_TO_MAX - Math.max(0L, absoluteXp));
    }

    /** Overall progress toward level 60, clamped to [0,1]. */
    public static float getProgressToMax() {
        if (absoluteXp <= 0) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, (float) absoluteXp / (float) XP_TO_MAX));
    }

    /** Estimated milliseconds to reach level 60 at the current rate, or -1 if unknown. */
    public static long getEtaToMaxMs() {
        long rate = getXpPerHour();
        if (rate <= 0 || isMaxed()) {
            return -1L;
        }
        return (long) (getRemainingToMax() / (double) rate * 3_600_000.0);
    }

    /** XP accumulated within the current level. */
    public static long getXpIntoLevel() {
        if (absoluteXp < 0 || currentLevel < 0) {
            return 0L;
        }
        return Math.max(0L, absoluteXp - XP_TO_LEVEL[Math.min(currentLevel, MAX_LEVEL)]);
    }

    /** XP required to advance from the current level to the next. */
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

    /** Estimated milliseconds to reach the next level at the current rate, or -1 if unknown. */
    public static long getEtaToNextLevelMs() {
        long rate = getXpPerHour();
        if (rate <= 0 || currentLevel >= MAX_LEVEL) {
            return -1L;
        }
        return (long) (getRemainingToNextLevel() / (double) rate * 3_600_000.0);
    }

    // -- Internals -------------------------------------------------------------

    private static void setAnchor(int level, long currentXpInLevel) {
        if (level < 0 || level > MAX_LEVEL) {
            return;
        }
        synchronized (LOCK) {
            currentLevel = level;
            absoluteXp = XP_TO_LEVEL[level] + Math.max(0L, currentXpInLevel);
        }
    }

    /** Resolve the level you're currently on from the "needed for next level" value. */
    private static int levelForNeeded(long needed) {
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
