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
    void theSwingCadenceIsWallClockSoItIsTheSameAtAnyFrameRate() {
        assertFalse(StriderFishingMacro.attackReady(1_000L, 1_000L));
        assertFalse(StriderFishingMacro.attackReady(1_500L, 1_000L));
        assertTrue(StriderFishingMacro.attackReady(1_550L, 1_000L));
        assertTrue(StriderFishingMacro.attackReady(9_000L, 1_000L));
    }

    @Test
    void sneakIsDroppedInLiquidUnlessItIsAskedToContinue() {
        assertTrue(StriderFishingMacro.sneakAllowedInLiquid(false, false));
        assertFalse(StriderFishingMacro.sneakAllowedInLiquid(true, false));
        assertTrue(StriderFishingMacro.sneakAllowedInLiquid(true, true));
        assertTrue(StriderFishingMacro.sneakAllowedInLiquid(false, true));
    }
}
