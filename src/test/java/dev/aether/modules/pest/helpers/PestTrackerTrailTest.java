package dev.aether.modules.pest.helpers;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PestTrackerTrailTest {
    @Test
    void followsTheObservedCurveAndExtendsItsTerminalDirection() {
        PestTrackerTrail trail = new PestTrackerTrail();
        Vec3 origin = new Vec3(100, 80, -100);
        trail.begin(origin, 1_000L);
        for (int i = 0; i <= 20; i++) {
            double t = i / 20.0;
            assertTrue(trail.add(origin.add(20 * t, 8 * t - 4 * t * t, 6 * t * t), 1_000L + i * 50));
        }

        var prediction = trail.prediction(2_100L);
        assertNotNull(prediction);
        assertEquals(21, prediction.observed().size());
        Vec3 end = prediction.observed().getLast();
        assertEquals(end, prediction.extension().getFirst());
        assertTrue(prediction.target().x > end.x);
        assertTrue(prediction.target().z > end.z);
        assertTrue(prediction.target().y < end.y, "The estimate should continue down after the arc's apex");
        assertTrue(prediction.target().distanceTo(end) <= 32.01);
    }

    @Test
    void ignoresDistantStartsDuplicatesUnrelatedJumpsAndBackwardParticles() {
        PestTrackerTrail trail = new PestTrackerTrail();
        trail.begin(Vec3.ZERO, 100L);
        assertFalse(trail.add(new Vec3(20, 0, 0), 110L));
        assertFalse(trail.add(new Vec3(Double.NaN, 0, 0), 110L));
        assertTrue(trail.add(new Vec3(1, 0, 0), 120L));
        assertFalse(trail.add(new Vec3(1, 0, 0), 130L));
        assertTrue(trail.add(new Vec3(2, 0, 0), 140L));
        assertFalse(trail.add(new Vec3(1, 0, 0), 150L));
        assertFalse(trail.add(new Vec3(50, 0, 0), 160L));
        assertTrue(trail.add(new Vec3(3, 0, 0), 170L));
        assertEquals(3, trail.observed(200L).size());
        assertNull(trail.prediction(200L));
    }

    @Test
    void waitsForAQuietTrailAndTimesOutWhenNoParticlesArrive() {
        PestTrackerTrail trail = new PestTrackerTrail();
        trail.begin(Vec3.ZERO, 1_000L);
        assertFalse(trail.isComplete(2_000L));
        assertTrue(trail.isComplete(3_500L));
        for (int i = 0; i < 10; i++) assertTrue(trail.add(new Vec3(i, 0, 0), 1_000L + i * 50));
        assertFalse(trail.isComplete(1_600L));
        assertTrue(trail.isComplete(1_700L));
        assertNotNull(trail.prediction(1_700L));
        assertFalse(trail.add(new Vec3(10, 0, 0), 3_501L));
        assertNull(trail.prediction(6_001L));
        assertTrue(trail.observed(6_001L).isEmpty());
    }

    @Test
    void newUsesAndResetDiscardEarlierEstimates() {
        PestTrackerTrail trail = new PestTrackerTrail();
        trail.begin(Vec3.ZERO, 1_000L);
        for (int i = 0; i < 10; i++) trail.add(new Vec3(i, 0, 0), 1_000L + i * 50);
        assertNotNull(trail.prediction(1_500L));
        assertFalse(trail.belongsTo(1_100L));
        trail.begin(new Vec3(50, 80, 0), 3_000L);
        assertTrue(trail.belongsTo(3_000L));
        assertNull(trail.prediction(3_000L));
        assertFalse(trail.add(new Vec3(10, 0, 0), 3_100L));
        trail.reset();
        assertFalse(trail.belongsTo(0L));
        assertFalse(trail.add(new Vec3(50, 80, 0), 3_200L));
    }

    @Test
    void straightTrailsRemainStraightAndShortTrailsDoNotProduceGuesses() {
        PestTrackerTrail trail = new PestTrackerTrail();
        trail.begin(Vec3.ZERO, 1_000L);
        for (int i = 0; i < 10; i++) trail.add(new Vec3(i * 0.05, 0, 0), 1_000L + i * 50);
        assertNull(trail.prediction(1_500L));
        trail.begin(Vec3.ZERO, 2_000L);
        for (int i = 0; i <= 40; i++) trail.add(new Vec3(i, 0, 0), 2_000L + i * 50);
        var prediction = trail.prediction(4_100L);
        assertNotNull(prediction);
        assertEquals(72, prediction.target().x, 1.0E-6);
        assertEquals(0, prediction.target().y, 1.0E-6);
        assertEquals(0, prediction.target().z, 1.0E-6);
    }
}
