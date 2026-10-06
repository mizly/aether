package dev.aether.modules.pest.helpers;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class PestSightingsTest {
    private static final long NOW = 1_000_000L;

    @Test
    void eachPestIsRememberedWhereItWasLastSeen() {
        PestSightings sightings = new PestSightings();
        sightings.record(7, new Vec3(1, 70, 3), NOW);
        sightings.record(7, new Vec3(4, 72, 6), NOW + 500);
        sightings.record(8, new Vec3(-9, 68, 2), NOW + 100);

        PestSightings.Sighting seven = sightings.lastSeen(7, NOW + 2_000, 8_000);
        assertNotNull(seven);
        assertEquals(new Vec3(4, 72, 6), seven.eye());
        assertEquals(1_500L, seven.ageMs());
        PestSightings.Sighting eight = sightings.lastSeen(8, NOW + 2_000, 8_000);
        assertNotNull(eight);
        assertEquals(new Vec3(-9, 68, 2), eight.eye());
        assertEquals(1_900L, eight.ageMs());
        assertNull(sightings.lastSeen(9, NOW + 2_000, 8_000));
    }

    @Test
    void aSightingOlderThanTheAskedWindowIsNotRecalled() {
        PestSightings sightings = new PestSightings();
        sightings.record(7, Vec3.ZERO, NOW);

        PestSightings.Sighting edge = sightings.lastSeen(7, NOW + 8_000, 8_000);
        assertNotNull(edge);
        assertEquals(8_000L, edge.ageMs());
        assertNull(sightings.lastSeen(7, NOW + 8_001, 8_000));
        assertNotNull(sightings.lastSeen(7, NOW + 8_001, 30_000));
    }

    @Test
    void pruningForgetsWhatWasSeenOverThirtySecondsAgo() {
        PestSightings sightings = new PestSightings();
        sightings.record(1, Vec3.ZERO, NOW);
        sightings.record(2, Vec3.ZERO, NOW + 10_000);

        sightings.prune(NOW + 30_000);
        assertNotNull(sightings.lastSeen(1, NOW + 30_000, Long.MAX_VALUE));

        sightings.prune(NOW + 30_001);
        assertNull(sightings.lastSeen(1, NOW + 30_001, Long.MAX_VALUE));
        PestSightings.Sighting kept = sightings.lastSeen(2, NOW + 30_001, Long.MAX_VALUE);
        assertNotNull(kept);
        assertEquals(20_001L, kept.ageMs());
    }

    @Test
    void clearingForgetsEveryPest() {
        PestSightings sightings = new PestSightings();
        sightings.record(1, Vec3.ZERO, NOW);
        sightings.record(2, Vec3.ZERO, NOW);

        sightings.clear();

        assertNull(sightings.lastSeen(1, NOW, Long.MAX_VALUE));
        assertNull(sightings.lastSeen(2, NOW, Long.MAX_VALUE));
    }

    @Test
    void aClockThatStepsBackReadsAsAFreshSighting() {
        PestSightings sightings = new PestSightings();
        sightings.record(1, Vec3.ZERO, NOW);

        PestSightings.Sighting sighting = sightings.lastSeen(1, NOW - 50, 8_000);
        assertNotNull(sighting);
        assertEquals(0L, sighting.ageMs());
    }
}
