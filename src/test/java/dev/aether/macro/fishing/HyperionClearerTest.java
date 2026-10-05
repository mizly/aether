package dev.aether.macro.fishing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HyperionClearerTest {
    @Test
    void theBladeOnlyGoesOutOnceTheLookHasLandedOnTheFloorWithTheUseKeyUp() {
        assertTrue(HyperionClearer.shouldClickHyperion(true, false, true));
        assertFalse(HyperionClearer.shouldClickHyperion(false, false, true));
        assertFalse(HyperionClearer.shouldClickHyperion(true, true, true));
        assertFalse(HyperionClearer.shouldClickHyperion(true, false, false));
    }

    @Test
    void twelveClicksOrEightSecondsWithoutAKillGiveUp() {
        assertFalse(HyperionClearer.hyperionGivenUp(0, 0L));
        assertFalse(HyperionClearer.hyperionGivenUp(11, 7_999L));
        assertTrue(HyperionClearer.hyperionGivenUp(12, 0L));
        assertTrue(HyperionClearer.hyperionGivenUp(0, 8_000L));
    }
}
