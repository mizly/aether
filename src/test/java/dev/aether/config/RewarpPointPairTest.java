package dev.aether.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RewarpPointPairTest {
    @Test
    void snapsCoordinatesToContainingBlockCenter() {
        assertEquals(-233.5, RewarpPointPair.snapToBlockCenter(-233.247));
        assertEquals(-1.5, RewarpPointPair.snapToBlockCenter(-1.5));
        assertEquals(-0.5, RewarpPointPair.snapToBlockCenter(-1.0));
        assertEquals(0.5, RewarpPointPair.snapToBlockCenter(0.0));
        assertEquals(1.5, RewarpPointPair.snapToBlockCenter(1.0));
    }

    @Test
    void snappingAnExistingBlockCenterDoesNotMoveIt() {
        double center = -233.5;

        for (int i = 0; i < 10; i++) {
            center = RewarpPointPair.snapToBlockCenter(center);
        }

        assertEquals(-233.5, center);
    }

    @Test
    void aotvAlignIsOffByDefaultAndRoundTrips() {
        RewarpPointPair pair = RewarpPointPair.defaultPair(0);
        assertFalse(pair.aotvAlign);

        pair.aotvAlign = true;
        RewarpPointPair restored = new RewarpPointPair(pair.toString(), 0);

        assertTrue(restored.aotvAlign);
    }

    @Test
    void readsLegacyV4AotvAlignValue() {
        RewarpPointPair pair = new RewarpPointPair(
                "v4:UmV3YXJwIDE:0.5:70.0:0.5:true:true:10.5:70.0:10.5:true:true:FLY:0:false:true",
                0);

        assertTrue(pair.aotvAlign);
    }

    @Test
    void reverseDirectionIsOffByDefaultAndRoundTrips() {
        RewarpPointPair pair = RewarpPointPair.defaultPair(0);
        assertFalse(pair.reverseDirection);

        pair.reverseDirection = true;
        pair.aotvAlign = true;
        pair.holdWUntilWall = true;
        pair.rewarpMode = RewarpMode.WARP_GARDEN;
        RewarpPointPair restored = new RewarpPointPair(pair.toString(), 0);

        assertTrue(restored.reverseDirection);
        assertTrue(restored.aotvAlign);
        assertTrue(restored.holdWUntilWall);
        assertEquals(RewarpMode.WARP_GARDEN, restored.rewarpMode);
    }

    @Test
    void legacyV6KeepsDirectionAndExistingOptions() {
        RewarpPointPair pair = new RewarpPointPair(
                "v6:UmV3YXJwIDE:0.5:70.0:0.5:true:true:10.5:70.0:10.5:true:true:PLOT_TP:5:true:true",
                0);

        assertFalse(pair.reverseDirection);
        assertTrue(pair.aotvAlign);
        assertTrue(pair.holdWUntilWall);
        assertEquals(RewarpMode.PLOT_TP, pair.rewarpMode);
        assertEquals("5", pair.plotTpNumber);
    }
}
