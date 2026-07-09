package dev.aether.modules.session;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroStateManager;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Tracks active macro time for the current local calendar day.
 */
public final class DailyFarmTimeTracker {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    private static volatile long accumulatedMs = 0L;
    private static volatile long segmentStartMs = 0L;
    private static volatile String trackedDate = "";

    private DailyFarmTimeTracker() {
    }

    public static void syncFromConfig() {
        String today = today();
        String savedDate = AetherConfig.DAILY_FARM_DATE.get();
        if (today.equals(savedDate)) {
            accumulatedMs = Math.max(0L, (long) (double) AetherConfig.DAILY_FARM_ACCUMULATED.get());
        } else {
            accumulatedMs = 0L;
            AetherConfig.DAILY_FARM_DATE.set(today);
            AetherConfig.DAILY_FARM_ACCUMULATED.set(0.0);
            AetherConfig.save();
        }
        trackedDate = today;
        segmentStartMs = MacroStateManager.isMacroRunning() ? System.currentTimeMillis() : 0L;
    }

    public static void onMacroStart() {
        maybeRolloverDate();
        if (segmentStartMs == 0L) {
            segmentStartMs = System.currentTimeMillis();
        }
    }

    public static void onMacroStop() {
        if (segmentStartMs != 0L) {
            accumulatedMs += Math.max(0L, System.currentTimeMillis() - segmentStartMs);
            segmentStartMs = 0L;
            persist(true);
        }
    }

    public static void periodicSave() {
        maybeRolloverDate();
        persist(false);
    }

    public static long getTodayMs() {
        maybeRolloverDate();
        if (segmentStartMs != 0L) {
            return accumulatedMs + Math.max(0L, System.currentTimeMillis() - segmentStartMs);
        }
        return accumulatedMs;
    }

    /**
     * Commits the in-progress segment into the accumulated total and flushes it to disk.
     *
     * <p>Call this on game shutdown. {@link #onMacroStop()} only runs on an explicit macro
     * stop, so a macro that is left running until the game is closed would otherwise lose the
     * current day's un-committed farming time. The segment origin is advanced to now so this
     * is safe to call more than once without double-counting.</p>
     */
    public static void persistNow() {
        maybeRolloverDate();
        if (segmentStartMs != 0L) {
            long now = System.currentTimeMillis();
            accumulatedMs += Math.max(0L, now - segmentStartMs);
            segmentStartMs = now;
        }
        persist(true);
    }

    public static void resetToday() {
        accumulatedMs = 0L;
        trackedDate = today();
        segmentStartMs = MacroStateManager.isMacroRunning() ? System.currentTimeMillis() : 0L;
        persist(true);
    }

    private static void persist(boolean flush) {
        AetherConfig.DAILY_FARM_DATE.set(trackedDate);
        AetherConfig.DAILY_FARM_ACCUMULATED.set((double) getCurrentAccumulatedMs());
        if (flush) {
            AetherConfig.save();
        }
    }

    private static long getCurrentAccumulatedMs() {
        if (segmentStartMs == 0L) {
            return accumulatedMs;
        }
        return accumulatedMs + Math.max(0L, System.currentTimeMillis() - segmentStartMs);
    }

    private static void maybeRolloverDate() {
        String today = today();
        if (today.equals(trackedDate)) {
            return;
        }

        accumulatedMs = 0L;
        trackedDate = today;
        segmentStartMs = MacroStateManager.isMacroRunning() ? System.currentTimeMillis() : 0L;
        persist(true);
    }

    private static String today() {
        return LocalDate.now().format(DATE_FORMAT);
    }
}
