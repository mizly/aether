package dev.aether.macro.fishing;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HomeKeeperTest {
    @Test
    void standingOnTheStartBlockNeedsNoRouteBackToIt() {
        assertTrue(HomeKeeper.withinOriginBlock(0.0, 0.0, 0.0));
        // the corners and lip of the block itself
        assertTrue(HomeKeeper.withinOriginBlock(0.5, 0.0, 0.5));
        assertTrue(HomeKeeper.withinOriginBlock(-0.5, 0.0, 0.5));
        // a hair above it, mid hop out of the lava
        assertTrue(HomeKeeper.withinOriginBlock(0.0, 0.9, 0.0));
    }

    @Test
    void theNextBlockOverStillEarnsARouteHome() {
        assertFalse(HomeKeeper.withinOriginBlock(0.8, 0.0, 0.0));
        assertFalse(HomeKeeper.withinOriginBlock(0.0, 0.0, -0.8));
        assertFalse(HomeKeeper.withinOriginBlock(0.0, 1.5, 0.0));
        assertFalse(HomeKeeper.withinOriginBlock(0.0, -0.9, 0.0));
    }

    @Test
    void aRefusedRouteBacksOffBeforeTryingAgain() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 500; i++) {
            long delay = HomeKeeper.nextReturnRetryDelayMs(random);
            assertTrue(HomeKeeper.returnRetryDelayInRange(delay));
            // long enough that the jump can lift us out of lava before the next plan
            assertTrue(delay >= 500L && delay <= 900L);
        }
    }

    @Test
    void etherwarpOnlyEarnsItsKeepFromFourBlocksOut() {
        assertFalse(HomeKeeper.shouldEtherwarp(3.9, false, true));
        assertTrue(HomeKeeper.shouldEtherwarp(4.0, false, true));
        assertFalse(HomeKeeper.shouldEtherwarp(40.0, false, false));
    }

    @Test
    void lavaIsWarpedOutOfAsSoonAsThereIsAnywhereToGo() {
        assertFalse(HomeKeeper.shouldEtherwarp(0.9, true, true));
        assertTrue(HomeKeeper.shouldEtherwarp(1.0, true, true));
        assertTrue(HomeKeeper.shouldEtherwarp(2.0, true, true));
    }

    @Test
    void theSwimOutOfLavaWaitsABeatBeforeHoldingJump() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 500; i++) {
            long delay = HomeKeeper.nextLiquidJumpDelayMs(random);
            assertTrue(HomeKeeper.liquidJumpDelayInRange(delay));
            assertTrue(delay >= 100L && delay <= 300L);
        }
    }

    @Test
    void jumpIsHeldOnlyOnceTheSinkingBeatHasPassed() {
        assertFalse(HomeKeeper.shouldHoldLiquidJump(true, 1_000L, 1_200L));
        assertTrue(HomeKeeper.shouldHoldLiquidJump(true, 1_200L, 1_200L));
        assertTrue(HomeKeeper.shouldHoldLiquidJump(true, 9_000L, 1_200L));
    }

    @Test
    void dryLandNeverHoldsTheJumpKey() {
        assertFalse(HomeKeeper.shouldHoldLiquidJump(false, 9_000L, 1_200L));
        assertFalse(HomeKeeper.shouldHoldLiquidJump(true, 9_000L, 0L));
    }
}
