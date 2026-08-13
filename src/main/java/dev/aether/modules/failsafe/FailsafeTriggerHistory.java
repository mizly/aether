package dev.aether.modules.failsafe;

// Not cleared by FailsafeManager.reset(): a STOP-action failsafe stops the macro, which
// resets the failsafes, and that would erase the very trigger we need to report.
public final class FailsafeTriggerHistory {
    private static volatile boolean triggered;
    private static volatile long lastTriggerNanos;
    private static volatile String lastTriggerDetails = "";

    private FailsafeTriggerHistory() {
    }

    public static void recordTrigger(String details) {
        recordTriggerAt(System.nanoTime(), details);
    }

    static void recordTriggerAt(long nanos, String details) {
        lastTriggerNanos = nanos;
        lastTriggerDetails = details == null ? "" : details.trim();
        triggered = true;
    }

    public static boolean triggeredWithin(long nowNanos, long windowNanos) {
        if (!triggered) {
            return false;
        }
        long elapsed = nowNanos - lastTriggerNanos;
        return elapsed >= 0L && elapsed <= windowNanos;
    }

    public static String getLastTriggerDetails() {
        return triggered ? lastTriggerDetails : "";
    }

    static void clearForTesting() {
        triggered = false;
        lastTriggerNanos = 0L;
        lastTriggerDetails = "";
    }
}
