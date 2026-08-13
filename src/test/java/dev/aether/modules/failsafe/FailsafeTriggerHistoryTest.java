package dev.aether.modules.failsafe;

import dev.aether.telemetry.ban.BanDisconnectContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailsafeTriggerHistoryTest {
    private static final long DISCONNECT_NANOS = 9_000_000_000_000L;

    private static long secondsBefore(long seconds) {
        return DISCONNECT_NANOS - seconds * 1_000_000_000L;
    }

    @BeforeEach
    void resetHistory() {
        FailsafeTriggerHistory.clearForTesting();
    }

    @Test
    void failsafeThirtySecondsBeforeDisconnectCountsAsRecent() {
        FailsafeTriggerHistory.recordTriggerAt(secondsBefore(30), "Rotation changed unexpectedly.");

        BanDisconnectContext context = BanDisconnectContext.capture(true, DISCONNECT_NANOS);

        assertTrue(context.failsafeTriggeredWithinLast5Minutes());
        assertEquals("Rotation changed unexpectedly.", context.lastTriggeredFailsafe());
    }

    @Test
    void failsafeJustInsideTheWindowCountsAsRecent() {
        FailsafeTriggerHistory.recordTriggerAt(secondsBefore(4 * 60 + 59), "Ghost block detected.");

        assertTrue(BanDisconnectContext.capture(true, DISCONNECT_NANOS).failsafeTriggeredWithinLast5Minutes());
    }

    @Test
    void failsafeExactlyAtTheBoundaryStillCounts() {
        FailsafeTriggerHistory.recordTriggerAt(secondsBefore(BanDisconnectContext.FAILSAFE_WINDOW_SECONDS), "Bps failsafe.");

        assertTrue(BanDisconnectContext.capture(true, DISCONNECT_NANOS).failsafeTriggeredWithinLast5Minutes());
    }

    @Test
    void failsafeOlderThanFiveMinutesIsNotRecent() {
        FailsafeTriggerHistory.recordTriggerAt(secondsBefore(5 * 60 + 1), "Dirt check failed.");

        assertFalse(BanDisconnectContext.capture(true, DISCONNECT_NANOS).failsafeTriggeredWithinLast5Minutes());
    }

    @Test
    void noFailsafeEverTriggeredIsNotRecent() {
        BanDisconnectContext context = BanDisconnectContext.capture(true, DISCONNECT_NANOS);

        assertFalse(context.failsafeTriggeredWithinLast5Minutes());
        assertEquals("", context.lastTriggeredFailsafe());
    }

    @Test
    void failsafeBetweenFiveAndTenMinutesKeepsTheBanReportable() {
        FailsafeTriggerHistory.recordTriggerAt(secondsBefore(7 * 60), "Rotation changed unexpectedly.");

        BanDisconnectContext context = BanDisconnectContext.capture(false, DISCONNECT_NANOS);

        assertFalse(context.failsafeTriggeredWithinLast5Minutes());
        assertTrue(context.failsafeTriggeredWithinLast10Minutes());
        assertTrue(context.eligibleForReport());
    }

    @Test
    void failsafeOlderThanTenMinutesWithNoMacroIsNotReportable() {
        FailsafeTriggerHistory.recordTriggerAt(secondsBefore(BanDisconnectContext.ELIGIBILITY_WINDOW_SECONDS + 1), "Bps failsafe.");

        BanDisconnectContext context = BanDisconnectContext.capture(false, DISCONNECT_NANOS);

        assertFalse(context.failsafeTriggeredWithinLast10Minutes());
        assertFalse(context.eligibleForReport());
    }

    @Test
    void runningMacroIsReportableWithNoFailsafeAtAll() {
        assertTrue(BanDisconnectContext.capture(true, DISCONNECT_NANOS).eligibleForReport());
    }
}
