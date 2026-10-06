package dev.aether.modules.pest.helpers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PestLifecycleManagerTest {
    @Test
    void retriesSetSpawnOnlyWhileThePendingFarmingSessionIsValid() {
        assertEquals(2, PestLifecycleManager.SETSPAWN_MAX_ATTEMPTS);
        assertTrue(PestLifecycleManager.shouldRetrySetSpawn(
                PestLifecycleManager.Stage.PRE, 4, 4, false, false));
        assertFalse(PestLifecycleManager.shouldRetrySetSpawn(
                PestLifecycleManager.Stage.PRE, 4, 5, false, false));
        assertFalse(PestLifecycleManager.shouldRetrySetSpawn(
                PestLifecycleManager.Stage.PRE, 4, 4, true, false));
        assertFalse(PestLifecycleManager.shouldRetrySetSpawn(
                PestLifecycleManager.Stage.PRE, 4, 4, false, true));
    }

    @Test
    void startsPestCycleOnlyWhenIdleAndNoLoadoutSwapIsInFlight() {
        PestLifecycleManager.Stage idle = PestLifecycleManager.Stage.IDLE;
        assertNull(PestLifecycleManager.startRejectionReason(idle, false, false));
        assertNotNull(PestLifecycleManager.startRejectionReason(PestLifecycleManager.Stage.PRE, false, false));
        assertNotNull(PestLifecycleManager.startRejectionReason(idle, true, false));
        assertNotNull(PestLifecycleManager.startRejectionReason(idle, false, true));
    }

    @Test
    void retriesShortlyAfterSetSpawnFailsUntilItKeepsFailing() {
        long full = 30_000L;
        assertEquals(PestLifecycleManager.SETSPAWN_FAILURE_RETRY_MS,
                PestLifecycleManager.setSpawnFailureCooldownMs(1, full));
        assertEquals(PestLifecycleManager.SETSPAWN_FAILURE_RETRY_MS, PestLifecycleManager.setSpawnFailureCooldownMs(
                PestLifecycleManager.SETSPAWN_FAILURES_BEFORE_FULL_COOLDOWN - 1, full));
        assertEquals(full, PestLifecycleManager.setSpawnFailureCooldownMs(
                PestLifecycleManager.SETSPAWN_FAILURES_BEFORE_FULL_COOLDOWN, full));
    }

    @Test
    void shortensALongHoldToTheTabCatchUpWindowButNeverExtendsIt() {
        long now = 100_000L;
        assertEquals(now + PestLifecycleManager.TAB_CATCHUP_HOLD_MS,
                PestLifecycleManager.shortenedHoldUntil(now + 30_000L, now));
        assertEquals(now + 1_000L, PestLifecycleManager.shortenedHoldUntil(now + 1_000L, now));
    }

    @Test
    void holdsFarmingFromTheFarmingStopUntilThePostStage() {
        assertFalse(PestLifecycleManager.isHoldingFarming(PestLifecycleManager.Stage.IDLE, false));
        assertFalse(PestLifecycleManager.isHoldingFarming(PestLifecycleManager.Stage.PRE, false));
        assertTrue(PestLifecycleManager.isHoldingFarming(PestLifecycleManager.Stage.PRE, true));
        assertTrue(PestLifecycleManager.isHoldingFarming(PestLifecycleManager.Stage.CLEANING, false));
        assertFalse(PestLifecycleManager.isHoldingFarming(PestLifecycleManager.Stage.POST, true));
    }

    @Test
    void skipsDestroyerWhenBallsackLeavesOneOnTheTargetLeaveOnePlot() {
        assertTrue(PestLifecycleManager.shouldSkipCleaningAfterBallsack(1, true, false));
    }

    @Test
    void doesNotSkipDestroyerForOnePestOnANonLeaveOnePlot() {
        assertFalse(PestLifecycleManager.shouldSkipCleaningAfterBallsack(1, false, false));
    }

    @Test
    void skipsDestroyerWhenBallsackKillsEveryPest() {
        assertTrue(PestLifecycleManager.shouldSkipCleaningAfterBallsack(0, false, false));
    }
}
