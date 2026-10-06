package dev.aether.macro.fishing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WandHealerTest {
    private static final long LONG_AGO = 60_000L;

    @Test
    void theWandGoesOutBelowTheThresholdWithTheLineIn() {
        assertTrue(WandHealer.shouldHeal(0.4, 0.5, false, LONG_AGO, LONG_AGO));
        assertFalse(WandHealer.shouldHeal(0.5, 0.5, false, LONG_AGO, LONG_AGO));
        assertFalse(WandHealer.shouldHeal(0.9, 0.5, false, LONG_AGO, LONG_AGO));
    }

    @Test
    void theWandNeverGoesOutWithTheLineOut() {
        assertFalse(WandHealer.shouldHeal(0.1, 0.5, true, LONG_AGO, LONG_AGO));
    }

    @Test
    void theHyperionHoldsTheWandBackForFiveSecondsUnlessHealthIsUnderAQuarter() {
        assertFalse(WandHealer.shouldHeal(0.4, 0.5, false, 4_999L, LONG_AGO));
        assertTrue(WandHealer.shouldHeal(0.4, 0.5, false, 5_000L, LONG_AGO));
        assertTrue(WandHealer.shouldHeal(0.2, 0.5, false, 0L, LONG_AGO));
    }

    @Test
    void aClickIsGivenASecondAndAHalfToShowBeforeTheNext() {
        assertFalse(WandHealer.shouldHeal(0.2, 0.5, false, LONG_AGO, 1_499L));
        assertTrue(WandHealer.shouldHeal(0.2, 0.5, false, LONG_AGO, 1_500L));
    }

    @Test
    void aHealThatShowedWaitsOutTheHealAndThreeFailedRetriesWaitSixSeconds() {
        assertEquals(15_000L, WandHealer.nextWandAllowedAt(true, 0, 10_000L, 11_500L));
        assertEquals(11_500L, WandHealer.nextWandAllowedAt(false, 1, 10_000L, 11_500L));
        assertEquals(11_500L, WandHealer.nextWandAllowedAt(false, 3, 10_000L, 11_500L));
        assertEquals(17_500L, WandHealer.nextWandAllowedAt(false, 4, 10_000L, 11_500L));
    }
}
