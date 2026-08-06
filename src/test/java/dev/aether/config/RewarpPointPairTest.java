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
}
