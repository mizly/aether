package dev.aether.macro.fishing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StriderFishingMacroTest {
    @Test
    void readsTheCatchMarkerThroughDecoration() {
        assertTrue(StriderFishingMacro.isCatchMarker(
                StriderFishingMacro.stripFormatting("§c§l!!")));
        assertTrue(StriderFishingMacro.isCatchMarker(
                StriderFishingMacro.stripFormatting("  §l!!  ")));
        assertFalse(StriderFishingMacro.isCatchMarker(
                StriderFishingMacro.stripFormatting("§e§l?")));
        assertFalse(StriderFishingMacro.isCatchMarker(""));
    }

    @Test
    void doesNotReadTheWaitingMarkerAsACatch() {
        assertTrue(StriderFishingMacro.isBiteMarker(
                StriderFishingMacro.stripFormatting("§e§l?")));
        assertFalse(StriderFishingMacro.isBiteMarker(
                StriderFishingMacro.stripFormatting("§c§l!!")));
    }

    @Test
    void doesNotConfuseAHealthPlateWithTheCatchMarker() {
        assertFalse(StriderFishingMacro.isCatchMarker(
                StriderFishingMacro.stripFormatting("§c1,000§4❤")));
    }

    @Test
    void approachesAndBacksOffWithoutStrafing() {
        assertEquals(1, StriderFishingMacro.followDirection(4.0, 1.5, 0));
        assertEquals(0, StriderFishingMacro.followDirection(1.5, 1.5, 0));
        assertEquals(-1, StriderFishingMacro.followDirection(0.5, 1.5, 0));
    }

    @Test
    void aMoveInProgressRunsToTheKillDistanceInsteadOfTheBandEdge() {
        assertEquals(1, StriderFishingMacro.followDirection(1.7, 1.5, 1));
        assertEquals(0, StriderFishingMacro.followDirection(1.4, 1.5, 1));
        assertEquals(-1, StriderFishingMacro.followDirection(1.3, 1.5, -1));
        assertEquals(0, StriderFishingMacro.followDirection(1.6, 1.5, -1));
    }

    @Test
    void anOvershootInsideTheBandDoesNotAnswerWithTheOppositeKey() {
        assertEquals(0, StriderFishingMacro.followDirection(1.3, 1.5, 0));
        assertEquals(0, StriderFishingMacro.followDirection(1.7, 1.5, 0));
    }

    @Test
    void theSwingCadenceStaysBetweenThreeAndSixClicksASecond() {
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 500; i++) {
            long delay = StriderFishingMacro.nextAttackDelayMs(random);
            assertTrue(StriderFishingMacro.attackDelayInRange(delay));
            double cps = 1000.0 / delay;
            assertTrue(cps >= 3.0 && cps <= 6.0);
        }
    }

    @Test
    void theWayHomeStartsJustAfterTheCatchDies() {
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 500; i++) {
            long delay = StriderFishingMacro.nextReturnDelayMs(random);
            assertTrue(StriderFishingMacro.returnDelayInRange(delay));
            assertTrue(delay >= 75L && delay <= 175L);
        }
    }

    @Test
    void anEmptyCatchWaitsLongerBeforeTheNextCastThanAKill() {
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 500; i++) {
            long delay = StriderFishingMacro.nextEmptyCatchDelayMs(random);
            assertTrue(StriderFishingMacro.emptyCatchDelayInRange(delay));
            assertTrue(delay >= 1_500L && delay <= 3_500L);
            assertTrue(delay > StriderFishingMacro.nextReturnDelayMs(random));
        }
    }

    @Test
    void etherwarpOnlyEarnsItsKeepFromFourBlocksOut() {
        assertFalse(StriderFishingMacro.shouldEtherwarp(3.9, false, true));
        assertTrue(StriderFishingMacro.shouldEtherwarp(4.0, false, true));
        assertFalse(StriderFishingMacro.shouldEtherwarp(40.0, false, false));
    }

    @Test
    void lavaIsWarpedOutOfAsSoonAsThereIsAnywhereToGo() {
        assertFalse(StriderFishingMacro.shouldEtherwarp(0.9, true, true));
        assertTrue(StriderFishingMacro.shouldEtherwarp(1.0, true, true));
        assertTrue(StriderFishingMacro.shouldEtherwarp(2.0, true, true));
    }

    @Test
    void onlyACatchThatSurfacedAfterTheReelIsTargeted() {
        java.util.Set<Integer> beforeReel = java.util.Set.of(11, 22, 33);
        assertFalse(StriderFishingMacro.shouldAcceptTarget(22, beforeReel));
        assertTrue(StriderFishingMacro.shouldAcceptTarget(44, beforeReel));
    }

    @Test
    void theIdleDriftStaysSmallAndUnhurried() {
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 500; i++) {
            assertTrue(StriderFishingMacro.idleDelayInRange(StriderFishingMacro.nextIdleDelayMs(random)));
            assertTrue(Math.abs(StriderFishingMacro.driftDegrees(random, 2.5f)) <= 2.5f);
        }
    }

    @Test
    void theWalkHomeLooksWhereItIsHeaded() {
        assertEquals(0.0f, StriderFishingMacro.travelYawDegrees(0.0, 0.5, 123.0f), 0.001f);
        assertEquals(90.0f, StriderFishingMacro.travelYawDegrees(-0.5, 0.0, 123.0f), 0.001f);
        assertEquals(-90.0f, StriderFishingMacro.travelYawDegrees(0.5, 0.0, 123.0f), 0.001f);
    }

    @Test
    void aStandstillKeepsTheCurrentFacingInsteadOfSnapping() {
        assertEquals(123.0f, StriderFishingMacro.travelYawDegrees(0.0, 0.0, 123.0f), 0.001f);
    }

    @Test
    void theWalkHomeLooksOnlyVerySlightlyDown() {
        double degrees = Math.toDegrees(Math.atan2(StriderFishingMacro.lookDrop(), 6.0));
        assertTrue(degrees > 4.0 && degrees < 6.0);
    }

    @Test
    void sneakIsDroppedInLiquidUnlessItIsAskedToContinue() {
        assertTrue(StriderFishingMacro.sneakAllowedInLiquid(false, false));
        assertFalse(StriderFishingMacro.sneakAllowedInLiquid(true, false));
        assertTrue(StriderFishingMacro.sneakAllowedInLiquid(true, true));
        assertTrue(StriderFishingMacro.sneakAllowedInLiquid(false, true));
    }
}
