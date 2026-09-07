package dev.aether.modules.farming;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class MousematCooldownTest {
    @Test
    void waitsOnlyForTheRemainingCooldownAcrossRestores() {
        var clock = new AtomicLong();
        var cooldown = new MousematCooldown(clock::get);
        assertTrue(cooldown.await(() -> false, millis -> fail("First use must not wait")));

        cooldown.recordUse();
        clock.set(TimeUnit.SECONDS.toNanos(1));
        var waitedMillis = new AtomicLong();
        assertTrue(cooldown.await(() -> false, millis -> {
            waitedMillis.addAndGet(millis);
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
            return true;
        }));
        assertEquals(2_000, waitedMillis.get());
        assertEquals(0, cooldown.remainingMillis());

        cooldown.recordUse();
        assertEquals(3_000, cooldown.remainingMillis());
    }

    @Test
    void keepsFractionalMillisecondsOnCooldown() {
        var clock = new AtomicLong(-TimeUnit.SECONDS.toNanos(10));
        var cooldown = new MousematCooldown(clock::get);
        cooldown.recordUse();
        clock.addAndGet(TimeUnit.SECONDS.toNanos(3) - 1);
        assertEquals(1, cooldown.remainingMillis());
        clock.incrementAndGet();
        assertEquals(0, cooldown.remainingMillis());
    }

    @Test
    void abortsPromptlyWithoutClearingTheCooldown() {
        var clock = new AtomicLong();
        var cooldown = new MousematCooldown(clock::get);
        var abort = new AtomicBoolean();
        cooldown.recordUse();

        assertFalse(cooldown.await(abort::get, millis -> {
            assertTrue(millis <= 50);
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
            abort.set(true);
            return true;
        }));
        assertEquals(2_950, cooldown.remainingMillis());
        assertFalse(cooldown.await(() -> false, millis -> false));
        assertEquals(2_950, cooldown.remainingMillis());
    }

    @Test
    void noticesAnotherUseWhileWaiting() {
        var clock = new AtomicLong();
        var cooldown = new MousematCooldown(clock::get);
        cooldown.recordUse();

        assertTrue(cooldown.await(() -> false, millis -> {
            long now = clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
            if (now == TimeUnit.SECONDS.toNanos(1)) cooldown.recordUse();
            return true;
        }));
        assertEquals(TimeUnit.SECONDS.toNanos(4), clock.get());
    }
}
