package dev.aether.telemetry.ban;

import dev.aether.modules.failsafe.FailsafeTriggerHistory;

public record BanDisconnectContext(
        boolean macroActiveAtDisconnect,
        boolean failsafeTriggeredWithinLast5Minutes,
        boolean failsafeTriggeredWithinLast10Minutes,
        String lastTriggeredFailsafe
) {
    public static final long FAILSAFE_WINDOW_SECONDS = 5L * 60L;
    public static final long FAILSAFE_WINDOW_NANOS = FAILSAFE_WINDOW_SECONDS * 1_000_000_000L;
    public static final long ELIGIBILITY_WINDOW_SECONDS = 10L * 60L;
    public static final long ELIGIBILITY_WINDOW_NANOS = ELIGIBILITY_WINDOW_SECONDS * 1_000_000_000L;

    public BanDisconnectContext {
        lastTriggeredFailsafe = lastTriggeredFailsafe == null ? "" : lastTriggeredFailsafe;
    }

    public static BanDisconnectContext capture(boolean macroActive) {
        return capture(macroActive, System.nanoTime());
    }

    public static BanDisconnectContext capture(boolean macroActive, long nowNanos) {
        return new BanDisconnectContext(
                macroActive,
                FailsafeTriggerHistory.triggeredWithin(nowNanos, FAILSAFE_WINDOW_NANOS),
                FailsafeTriggerHistory.triggeredWithin(nowNanos, ELIGIBILITY_WINDOW_NANOS),
                FailsafeTriggerHistory.getLastTriggerDetails());
    }

    // a ban with no macro and no failsafe in the last 10 minutes is not macro-related, so it stays local.
    public boolean eligibleForReport() {
        return macroActiveAtDisconnect || failsafeTriggeredWithinLast10Minutes;
    }
}
