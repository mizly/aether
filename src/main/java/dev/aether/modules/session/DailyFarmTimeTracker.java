package dev.aether.modules.session;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroStateManager;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

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

    // call on shutdown: onMacroStop only runs on an explicit stop, so a macro left running until the game closes would lose the day's uncommitted time
    // the segment origin moves to now, so calling twice cannot double-count
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
