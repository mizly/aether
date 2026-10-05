package dev.aether.macro.fishing;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void theWhipIntervalStaysBetweenHalfASecondAndEightHundred() {
        Random random = new Random(7L);
        AbilitySwapClicker.Timing whip = AbilitySwapClicker.SOUL_WHIP;
        long lo = Long.MAX_VALUE;
        long hi = Long.MIN_VALUE;
        for (int i = 0; i < 5000; i++) {
            long interval = AbilitySwapClicker.nextIntervalMs(random, whip);
            assertTrue(interval >= 500L && interval <= 800L);
            lo = Math.min(lo, interval);
            hi = Math.max(hi, interval);
        }
        assertTrue(lo < 520L && hi > 780L);
    }

    @Test
    void aQuickSwapBackCannotPullTheNextClickInsideTheFloor() {
        assertEquals(1_550L, AbilitySwapClicker.nextClickAt(1_040L, 1_000L, 500L, 550L));
        assertEquals(1_740L, AbilitySwapClicker.nextClickAt(1_240L, 1_000L, 500L, 550L));
    }

    @Test
    void soulWhipClicksStayHalfASecondApart() {
        for (int swap : new int[] {0, 40, 400}) {
            List<Long> clicks = runClicker(AbilitySwapClicker.SOUL_WHIP, new Random(swap), swap, 2, 60_000L);
            assertTrue(clicks.size() > 20);
            for (int i = 1; i < clicks.size(); i++) {
                assertTrue(clicks.get(i) - clicks.get(i - 1) >= 550L, "gap " + (clicks.get(i) - clicks.get(i - 1)));
            }
        }
    }

    @Test
    void theFloorHoldsEvenWithNoDrawOrInterval() {
        AbilitySwapClicker.Timing instant = new AbilitySwapClicker.Timing(0L, 0L, 0L, 0L, 550L);
        List<Long> clicks = runClicker(instant, new Random(1L), 0, 2, 10_000L);
        assertTrue(clicks.size() > 10);
        assertGapsAtLeast(clicks, 550L);
    }

    @Test
    void eachAbilityKeepsItsOwnFloor() {
        for (long floor : new long[] {150L, 550L, 1_000L}) {
            AbilitySwapClicker.Timing instant = new AbilitySwapClicker.Timing(0L, 0L, 0L, 0L, floor);
            for (int back : new int[] {-1, 2}) {
                List<Long> clicks = runClicker(instant, new Random(floor), 0, back, 20_000L);
                assertTrue(clicks.size() > 15);
                assertGapsAtLeast(clicks, floor);
                // nothing else holds the clicks back here, so the floor is what spaces them
                assertTrue(clicks.get(1) - clicks.get(0) < floor + 20L);
            }
        }
    }

    @Test
    void thePresetsKeepTheirFloorsAndIntervals() {
        assertPreset(AbilitySwapClicker.SOUL_WHIP, 550L, 500L, 800L, 2);
        assertPreset(AbilitySwapClicker.HYPERION, 150L, 160L, 280L, -1);
        assertPreset(AbilitySwapClicker.HEALING_WAND, 1_000L, 1_000L, 1_500L, 2);
    }

    @Test
    void swappedIntervalBoundsStillDrawInsideThem() {
        AbilitySwapClicker.Timing swapped = new AbilitySwapClicker.Timing(120L, 45L, 800L, 500L, 550L);
        Random random = new Random(9L);
        for (int i = 0; i < 500; i++) {
            long interval = AbilitySwapClicker.nextIntervalMs(random, swapped);
            assertTrue(interval >= 500L && interval <= 800L);
            long draw = AbilitySwapClicker.nextDrawDelayMs(random, swapped);
            assertTrue(draw >= 45L && draw <= 120L);
        }
    }

    @Test
    void aResetBetweenTargetsKeepsTheFloor() {
        List<Long> clicks = new ArrayList<>();
        long[] now = {0L};
        AbilitySwapClicker clicker = new AbilitySwapClicker(AbilitySwapClicker.SOUL_WHIP, slot -> { },
                () -> clicks.add(now[0]));
        Random random = new Random(3L);
        for (int tick = 0; now[0] < 30_000L; tick++, now[0] += 5L) {
            if (tick % 97 == 0) {
                clicker.reset();
            }
            clicker.tick(now[0], tick, 3, 2, 0, 0, () -> true, random);
        }
        assertTrue(clicks.size() > 20);
        for (int i = 1; i < clicks.size(); i++) {
            assertTrue(clicks.get(i) - clicks.get(i - 1) >= 550L);
        }
    }

    private static void assertPreset(AbilitySwapClicker.Timing timing, long floor, long intervalMin, long intervalMax,
                                     int backSlot) {
        assertEquals(floor, timing.minClickGapMs());
        assertEquals(intervalMin, timing.intervalMinMs());
        assertEquals(intervalMax, timing.intervalMaxMs());
        assertGapsAtLeast(runClicker(timing, new Random(floor), 0, backSlot, 30_000L), floor);
    }

    private static void assertGapsAtLeast(List<Long> clicks, long floor) {
        for (int i = 1; i < clicks.size(); i++) {
            assertTrue(clicks.get(i) - clicks.get(i - 1) >= floor, "gap " + (clicks.get(i) - clicks.get(i - 1)));
        }
    }

    private static List<Long> runClicker(AbilitySwapClicker.Timing timing, Random random, int swapMs, int backSlot,
                                         long untilMs) {
        List<Long> clicks = new ArrayList<>();
        long[] now = {0L};
        AbilitySwapClicker clicker = new AbilitySwapClicker(timing, slot -> { },
                () -> clicks.add(now[0]));
        for (int tick = 0; now[0] < untilMs; tick++, now[0] += 5L) {
            clicker.tick(now[0], tick, 3, backSlot, swapMs, swapMs, () -> true, random);
        }
        return clicks;
    }
}
