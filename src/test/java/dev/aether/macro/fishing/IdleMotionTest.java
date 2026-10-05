package dev.aether.macro.fishing;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertTrue;

class IdleMotionTest {
    @Test
    void theCursorSitsOffCentreOnTheFloatModel() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        boolean sawOffCentre = false;
        for (int i = 0; i < 500; i++) {
            Vec3 offset = IdleMotion.aimBoxOffset(random);
            assertTrue(IdleMotion.aimBoxOffsetInRange(offset));
            sawOffCentre |= offset.length() > 0.02;
        }
        assertTrue(sawOffCentre);
    }

    @Test
    void theCursorSettlesOntoTheFloatSoonAfterItLands() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 500; i++) {
            long delay = IdleMotion.nextFirstIdleDelayMs(random);
            assertTrue(IdleMotion.firstIdleDelayInRange(delay));
            assertTrue(delay < IdleMotion.nextIdleDelayMs(random));
        }
    }

    @Test
    void theGlanceAtTheFloatIsAFlickRatherThanAGlide() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 500; i++) {
            long turn = IdleMotion.nextIdleTurnMs(random);
            assertTrue(IdleMotion.idleTurnInRange(turn));
            assertTrue(turn >= 100L && turn <= 220L);
        }
    }

    @Test
    void theIdleDriftStaysSmall() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 500; i++) {
            assertTrue(IdleMotion.idleDelayInRange(IdleMotion.nextIdleDelayMs(random)));
            assertTrue(Math.abs(IdleMotion.driftDegrees(random, 2.5f)) <= 2.5f);
        }
    }
}
