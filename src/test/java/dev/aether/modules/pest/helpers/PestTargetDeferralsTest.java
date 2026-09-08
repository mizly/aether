package dev.aether.modules.pest.helpers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PestTargetDeferralsTest {
    @Test
    void aSingleTimeoutOnlyDefersThePestForAWhile() {
        PestTargetDeferrals deferrals = new PestTargetDeferrals();
        deferrals.defer(7);

        assertTrue(deferrals.isDeferred(7));
        assertFalse(deferrals.isPermanentlyDeferred(7));
        assertTrue(deferrals.releaseRetryable());
        assertFalse(deferrals.isDeferred(7));
    }

    @Test
    void givingUpOnAPestSurvivesTheSweepRetry() {
        PestTargetDeferrals deferrals = new PestTargetDeferrals();
        deferrals.defer(7);
        deferrals.deferPermanently(7);

        assertTrue(deferrals.isPermanentlyDeferred(7));
        assertFalse(deferrals.releaseRetryable());
        assertTrue(deferrals.isDeferred(7));
    }

    @Test
    void untouchedPestsAreNeverDeferred() {
        PestTargetDeferrals deferrals = new PestTargetDeferrals();
        deferrals.deferPermanently(7);

        assertFalse(deferrals.isDeferred(8));
        assertFalse(deferrals.isPermanentlyDeferred(8));
    }

    @Test
    void clearingEndsEveryGiveUp() {
        PestTargetDeferrals deferrals = new PestTargetDeferrals();
        deferrals.deferPermanently(7);
        deferrals.clear();

        assertFalse(deferrals.isDeferred(7));
        assertFalse(deferrals.isPermanentlyDeferred(7));
    }
}
