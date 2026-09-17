package dev.aether.modules.rotation;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HumanFlickTest {

    @RepeatedTest(50)
    void aFlickAlwaysFinishesExactlyOnTheTarget() {
        HumanFlick.Plan plan = HumanFlick.plan(170f, 10f, -160f, 35f, 1_000L, ThreadLocalRandom.current());

        float[] end = HumanFlick.sample(plan, plan.endMs() + 5L);

        assertEquals(200f, end[0], 1.0e-3f);
        assertEquals(35f, end[1], 1.0e-3f);
    }

    @RepeatedTest(50)
    void theThrowStopsOffTargetAndTheCorrectionFinishesIt() {
        HumanFlick.Plan plan = HumanFlick.plan(0f, 0f, 60f, 0f, 0L, ThreadLocalRandom.current());

        assertNotNull(plan.correction());
        float[] afterThrow = HumanFlick.sample(plan, plan.main().endMs());
        assertTrue(Math.abs(afterThrow[0] - 60f) > 0.0f);
        assertTrue(Math.abs(afterThrow[0] - 60f) < 60f * 0.08f);
    }

    @Test
    void tinyAdjustmentsSkipTheCorrection() {
        HumanFlick.Plan plan = HumanFlick.plan(0f, 0f, 1f, 0.5f, 0L, ThreadLocalRandom.current());

        assertNull(plan.correction());
    }

    @RepeatedTest(20)
    void biggerTurnsTakeLongerButStayQuick() {
        long small = HumanFlick.mainDurationMs(3.0, ThreadLocalRandom.current());
        long large = HumanFlick.mainDurationMs(150.0, ThreadLocalRandom.current());

        assertTrue(small >= 70L && small <= 140L, "small " + small);
        assertTrue(large <= 320L, "large " + large);
        assertTrue(large > small);
    }

    @Test
    void minimumJerkStartsAndEndsAtRest() {
        assertEquals(0.0, HumanFlick.minimumJerk(0.0), 1.0e-9);
        assertEquals(1.0, HumanFlick.minimumJerk(1.0), 1.0e-9);
        assertEquals(0.5, HumanFlick.minimumJerk(0.5), 1.0e-9);
    }
}
