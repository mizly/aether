package dev.aether.macro.fishing;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AbilitySwapClickerTest {
    @Test
    void theWeaponSwapLandsInsideTheConfiguredWindowPlusTheOddFumble() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        boolean sawVariety = false;
        long first = AbilitySwapClicker.nextSwapDelayMs(random, 40, 130);
        for (int i = 0; i < 2000; i++) {
            long delay = AbilitySwapClicker.nextSwapDelayMs(random, 40, 130);
            assertTrue(AbilitySwapClicker.swapDelayInRange(delay, 40, 130));
            assertTrue(delay >= 40L && delay <= 220L);
            sawVariety |= delay != first;
        }
        assertTrue(sawVariety);
    }

    @Test
    void swappedSwapBoundsStillProduceAValidDelay() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 500; i++) {
            long delay = AbilitySwapClicker.nextSwapDelayMs(random, 130, 40);
            assertTrue(AbilitySwapClicker.swapDelayInRange(delay, 40, 130));
        }
    }

    @Test
    void theWhipIsDrawnABeatBeforeTheClickAndSwungOnALooseRhythm() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        AbilitySwapClicker.Timing whip = AbilitySwapClicker.SOUL_WHIP;
        for (int i = 0; i < 500; i++) {
            long draw = AbilitySwapClicker.nextDrawDelayMs(random, whip);
            assertTrue(AbilitySwapClicker.drawDelayInRange(draw, whip));
            assertTrue(draw >= 45L);
            assertTrue(AbilitySwapClicker.intervalInRange(AbilitySwapClicker.nextIntervalMs(random, whip), whip));
        }
    }
}
