package dev.aether.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
