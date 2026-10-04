package dev.aether.macro.fishing;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatchWatchTest {
    @Test
    void readsTheCatchMarkerThroughDecoration() {
        assertTrue(CatchWatch.isCatchMarker(CatchWatch.stripFormatting("§c§l!!")));
        assertTrue(CatchWatch.isCatchMarker(CatchWatch.stripFormatting("  §l!!  ")));
        assertFalse(CatchWatch.isCatchMarker(CatchWatch.stripFormatting("§e§l?")));
        assertFalse(CatchWatch.isCatchMarker(""));
    }

    @Test
    void doesNotReadTheWaitingMarkerAsACatch() {
        assertTrue(CatchWatch.isBiteMarker(CatchWatch.stripFormatting("§e§l?")));
        assertFalse(CatchWatch.isBiteMarker(CatchWatch.stripFormatting("§c§l!!")));
    }

    @Test
    void doesNotConfuseAHealthPlateWithTheCatchMarker() {
        assertFalse(CatchWatch.isCatchMarker(CatchWatch.stripFormatting("§c1,000§4❤")));
    }

    @Test
    void onlyACatchThatSurfacedAfterTheReelIsTargeted() {
        Set<Integer> beforeReel = Set.of(11, 22, 33);
        assertFalse(CatchWatch.shouldAcceptTarget(22, beforeReel));
        assertTrue(CatchWatch.shouldAcceptTarget(44, beforeReel));
    }
}
