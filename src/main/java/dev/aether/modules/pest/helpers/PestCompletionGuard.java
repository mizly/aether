package dev.aether.modules.pest.helpers;

// the tab list lags right after a spawn/cleaning handoff, so ignore finish-level readings during startup and then need consecutive confirmations
public final class PestCompletionGuard {
    public static final long STARTUP_FINISH_GRACE_MS = 5_000L;
    public static final int TAB_FINISH_CONFIRM_TICKS = 10;

    private PestCompletionGuard() {
    }

    public static boolean isInStartupGrace(long activatedAtMs) {
        return isInStartupGrace(activatedAtMs, System.currentTimeMillis());
    }

    static boolean isInStartupGrace(long activatedAtMs, long nowMs) {
        return nowMs - activatedAtMs < STARTUP_FINISH_GRACE_MS;
    }

    public static boolean isStartupGraceElapsed(long activatedAtMs) {
        return !isInStartupGrace(activatedAtMs);
    }

    public static boolean shouldAcceptFinishReading(long activatedAtMs, boolean allowDuringStartup) {
        return allowDuringStartup || isStartupGraceElapsed(activatedAtMs);
    }

    public static boolean isConfirmed(int finishReadingTicks) {
        return finishReadingTicks >= TAB_FINISH_CONFIRM_TICKS;
    }
}
