package dev.aether.modules.session;

import dev.aether.modules.session.MicropauseScheduler.Action;
import dev.aether.modules.session.MicropauseScheduler.Settings;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static dev.aether.modules.session.MicropauseScheduler.Action.*;
import static org.junit.jupiter.api.Assertions.*;

class MicropauseSchedulerTest {
    private static final long TICK_MS = 50L;
    private static final BooleanSupplier CALM = () -> true;
    private static final Settings ONE_SECOND = new Settings(true, 1_000L, 1_000L, 1_000L, 1_000L);

    private final MicropauseScheduler scheduler = new MicropauseScheduler();
    private final SplittableRandom random = new SplittableRandom(20_261_004L);
    private long now = 1_000_000L;

    @Test
    void settingsOfConvertsMinutesAndSeconds() {
        Settings settings = Settings.of(true, 3, 10, 4, 12);
        assertTrue(settings.enabled());
        assertEquals(180_000L, settings.intervalMinMs());
        assertEquals(600_000L, settings.intervalMaxMs());
        assertEquals(4_000L, settings.durationMinMs());
        assertEquals(12_000L, settings.durationMaxMs());
        assertFalse(Settings.of(false, 1, 1, 1, 1).enabled());
    }

    @Test
    void equalBoundsPauseAfterTheIntervalAndASettleThenLastExactlyTheDuration() {
        Settings settings = Settings.of(true, 1, 1, 5, 5);
        for (int cycle = 0; cycle < 2; cycle++) {
            assertEquals(FARM, farm(settings));
            assertEquals(60_000L, scheduler.farmingMsUntilDue());
            long beganAfter = farmUntil(BEGIN_PAUSE, settings, 70_000L);
            assertTrue(beganAfter >= 62_000L && beganAfter <= 66_000L, "began after " + beganAfter);
            assertTrue(scheduler.isPaused());
            assertEquals(5_000L, scheduler.pauseRemainingMs(now));

            long pauseStart = now;
            while (now - pauseStart < 4_950L) {
                assertEquals(PAUSED, farm(settings));
            }
            assertEquals(END_PAUSE, farm(settings));
            assertEquals(5_000L, now - pauseStart);
            assertFalse(scheduler.isPaused());
            assertEquals(-1L, scheduler.farmingMsUntilDue());
        }
    }

    @Test
    void sampledIntervalsAndDurationsStayInsideSwappedBounds() {
        Settings swapped = new Settings(true, 5_000L, 2_000L, 900L, 300L);
        long shortestInterval = Long.MAX_VALUE;
        long longestInterval = Long.MIN_VALUE;
        long shortestPause = Long.MAX_VALUE;
        long longestPause = Long.MIN_VALUE;
        for (int draw = 0; draw < 500; draw++) {
            scheduler.reset();
            farm(swapped);
            long interval = scheduler.farmingMsUntilDue();
            assertTrue(interval >= 2_000L && interval <= 5_000L, "interval " + interval);
            farmUntil(BEGIN_PAUSE, swapped, 12_000L);
            long pause = scheduler.pauseRemainingMs(now);
            assertTrue(pause >= 300L && pause <= 900L, "pause " + pause);
            shortestInterval = Math.min(shortestInterval, interval);
            longestInterval = Math.max(longestInterval, interval);
            shortestPause = Math.min(shortestPause, pause);
            longestPause = Math.max(longestPause, pause);
        }
        assertTrue(shortestInterval < 2_300L && longestInterval > 4_700L);
        assertTrue(shortestPause < 360L && longestPause > 840L);
    }

    @Test
    void aDuePauseWaitsForTwoToSixSecondsOfCalm() {
        long shortest = Long.MAX_VALUE;
        long longest = Long.MIN_VALUE;
        for (int draw = 0; draw < 300; draw++) {
            scheduler.reset();
            long[] dueAt = {-1L};
            BooleanSupplier recordDue = () -> {
                if (dueAt[0] < 0L) {
                    dueAt[0] = now;
                }
                return true;
            };
            step(TICK_MS, true, recordDue, ONE_SECOND);
            Action action = FARM;
            for (int i = 0; i < 400 && action != BEGIN_PAUSE; i++) {
                action = step(TICK_MS, true, recordDue, ONE_SECOND);
            }
            assertEquals(BEGIN_PAUSE, action);
            long settle = now - dueAt[0];
            assertTrue(settle >= 2_000L && settle <= 6_000L, "settle " + settle);
            shortest = Math.min(shortest, settle);
            longest = Math.max(longest, settle);
        }
        assertTrue(shortest < 2_500L && longest > 5_500L);
    }

    @Test
    void aBrokenCalmStretchRestartsTheSettle() {
        boolean[] calm = {true};
        BooleanSupplier canStart = () -> calm[0];
        step(TICK_MS, true, canStart, ONE_SECOND);
        for (int i = 0; i < 20 + 38; i++) {
            assertEquals(FARM, step(TICK_MS, true, canStart, ONE_SECOND));
        }
        calm[0] = false;
        assertEquals(FARM, step(TICK_MS, true, canStart, ONE_SECOND));
        calm[0] = true;
        long calmAgainAt = now + TICK_MS;
        Action action;
        do {
            action = step(TICK_MS, true, canStart, ONE_SECOND);
        } while (action == FARM && now - calmAgainAt < 10_000L);
        assertEquals(BEGIN_PAUSE, action);
        assertTrue(now - calmAgainAt >= 2_000L, "began " + (now - calmAgainAt) + " ms after the calm came back");
    }

    @Test
    void canStartIsOnlyAskedOnceThePauseIsDue() {
        AtomicInteger asked = new AtomicInteger();
        BooleanSupplier counting = () -> {
            asked.incrementAndGet();
            return true;
        };
        Settings settings = new Settings(true, 10_000L, 10_000L, 1_000L, 1_000L);
        step(TICK_MS, true, counting, settings);
        for (int i = 0; i < 199; i++) {
            assertEquals(FARM, step(TICK_MS, true, counting, settings));
        }
        assertEquals(0, asked.get());
        assertEquals(FARM, step(TICK_MS, true, counting, settings));
        assertEquals(1, asked.get());
    }

    @Test
    void aLongGapBetweenFarmingTicksAccruesOnlyOneSecond() {
        Settings settings = Settings.of(true, 1, 1, 5, 5);
        farm(settings);
        step(10_000L, true, CALM, settings);
        assertEquals(59_000L, scheduler.farmingMsUntilDue());
    }

    @Test
    void timeAwayFromFarmingDoesNotAccrue() {
        Settings settings = Settings.of(true, 1, 1, 5, 5);
        farm(settings);
        for (int i = 0; i < 20; i++) {
            farm(settings);
        }
        assertEquals(59_000L, scheduler.farmingMsUntilDue());
        for (int i = 0; i < 12_000; i++) {
            assertEquals(FARM, step(TICK_MS, false, () -> fail("asked while not farming"), settings));
        }
        assertEquals(59_000L, scheduler.farmingMsUntilDue());
        farm(settings);
        assertEquals(59_000L, scheduler.farmingMsUntilDue());
        farm(settings);
        assertEquals(58_950L, scheduler.farmingMsUntilDue());
    }

    @Test
    void aDuePauseIsDeferredNotDroppedWhileItCannotStart() {
        farm(ONE_SECOND);
        for (int i = 0; i < 12_000; i++) {
            assertEquals(FARM, step(TICK_MS, true, () -> false, ONE_SECOND));
        }
        assertEquals(0L, scheduler.farmingMsUntilDue());
        assertFalse(scheduler.isPaused());
        long beganAfter = farmUntil(BEGIN_PAUSE, ONE_SECOND, 7_000L);
        assertTrue(beganAfter >= 2_000L && beganAfter <= 6_050L, "began after " + beganAfter);
    }

    @Test
    void disabledNeverPausesOrAsks() {
        Settings disabled = new Settings(false, 1_000L, 1_000L, 1_000L, 1_000L);
        for (int i = 0; i < 12_000; i++) {
            assertEquals(FARM, step(TICK_MS, true, () -> fail("asked while disabled"), disabled));
        }
        assertFalse(scheduler.isPaused());
        assertEquals(-1L, scheduler.farmingMsUntilDue());
    }

    @Test
    void disablingMidPauseEndsItOnce() {
        farm(ONE_SECOND);
        farmUntil(BEGIN_PAUSE, ONE_SECOND, 8_000L);
        Settings disabled = new Settings(false, 1_000L, 1_000L, 1_000L, 1_000L);
        assertEquals(END_PAUSE, step(TICK_MS, true, CALM, disabled));
        assertEquals(FARM, step(TICK_MS, true, CALM, disabled));
        assertFalse(scheduler.isPaused());
    }

    @Test
    void farmingThatStopsMidPauseEndsItOnceAndTheNextIntervalIsFresh() {
        Settings settings = new Settings(true, 1_000L, 1_000L, 30_000L, 30_000L);
        farm(settings);
        farmUntil(BEGIN_PAUSE, settings, 8_000L);
        assertEquals(PAUSED, farm(settings));
        assertEquals(END_PAUSE, step(TICK_MS, false, CALM, settings));
        assertEquals(FARM, step(TICK_MS, false, CALM, settings));
        assertEquals(FARM, step(TICK_MS, false, CALM, settings));
        assertEquals(-1L, scheduler.farmingMsUntilDue());
        assertEquals(FARM, farm(settings));
        assertEquals(1_000L, scheduler.farmingMsUntilDue());
    }

    @Test
    void resetDropsAPauseAndOnlyAFarmingTickArmsAgain() {
        Settings settings = new Settings(true, 1_000L, 1_000L, 30_000L, 30_000L);
        farm(settings);
        farmUntil(BEGIN_PAUSE, settings, 8_000L);
        scheduler.reset();
        assertFalse(scheduler.isPaused());
        assertEquals(0L, scheduler.pauseRemainingMs(now));
        assertEquals(-1L, scheduler.farmingMsUntilDue());
        assertEquals(FARM, step(TICK_MS, false, CALM, settings));
        assertEquals(-1L, scheduler.farmingMsUntilDue());
        assertEquals(FARM, farm(settings));
        assertEquals(1_000L, scheduler.farmingMsUntilDue());
    }

    @Test
    void changingTheIntervalBoundsResamplesThePendingIntervalAndKeepsFarmingTime() {
        Settings tenMinutes = Settings.of(true, 10, 10, 5, 5);
        farm(tenMinutes);
        for (int i = 0; i < 120; i++) {
            step(1_000L, true, CALM, tenMinutes);
        }
        assertEquals(480_000L, scheduler.farmingMsUntilDue());

        Settings twoMinutes = Settings.of(true, 2, 2, 5, 5);
        assertEquals(FARM, step(1_000L, true, CALM, twoMinutes));
        assertEquals(0L, scheduler.farmingMsUntilDue());
        long beganAfter = farmUntil(BEGIN_PAUSE, twoMinutes, 7_000L);
        assertTrue(beganAfter >= 2_000L && beganAfter <= 6_050L, "began after " + beganAfter);
    }

    @Test
    void wideningTheIntervalBoundsKeepsTheFarmingTimeAlreadyAccrued() {
        Settings oneMinute = Settings.of(true, 1, 1, 5, 5);
        farm(oneMinute);
        for (int i = 0; i < 30; i++) {
            step(1_000L, true, CALM, oneMinute);
        }
        Settings fiveMinutes = Settings.of(true, 5, 5, 5, 5);
        assertEquals(FARM, step(1_000L, true, CALM, fiveMinutes));
        assertEquals(300_000L - 31_000L, scheduler.farmingMsUntilDue());
    }

    @Test
    void thePendingIntervalStaysPutWhileTheBoundsAreUnchanged() {
        farm(Settings.of(true, 1, 10, 5, 5));
        long due = scheduler.farmingMsUntilDue();
        for (int i = 1; i <= 100; i++) {
            farm(Settings.of(true, 1, 10, 5, 5));
            assertEquals(due - i * TICK_MS, scheduler.farmingMsUntilDue());
        }
    }

    @Test
    void aFarmingDropDuringTheSettleStartsItAgain() {
        farm(ONE_SECOND);
        for (int i = 0; i < 25; i++) {
            assertEquals(FARM, farm(ONE_SECOND));
        }
        for (int i = 0; i < 200; i++) {
            assertEquals(FARM, step(TICK_MS, false, CALM, ONE_SECOND));
        }
        long beganAfter = farmUntil(BEGIN_PAUSE, ONE_SECOND, 7_000L);
        assertTrue(beganAfter >= 2_050L && beganAfter <= 6_050L, "began after " + beganAfter);
    }

    private Action farm(Settings settings) {
        return step(TICK_MS, true, CALM, settings);
    }

    private Action step(long elapsedMs, boolean farming, BooleanSupplier canStart, Settings settings) {
        now += elapsedMs;
        return scheduler.update(now, farming, canStart, settings, random);
    }

    // farms tick by tick and returns how long it took for the action to show up
    private long farmUntil(Action expected, Settings settings, long limitMs) {
        long start = now;
        while (now - start < limitMs) {
            if (farm(settings) == expected) {
                return now - start;
            }
        }
        return fail("no " + expected + " within " + limitMs + " ms");
    }
}
