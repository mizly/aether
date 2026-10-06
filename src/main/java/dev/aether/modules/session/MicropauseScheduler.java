package dev.aether.modules.session;

import dev.aether.config.AetherConfig;

import java.util.function.BooleanSupplier;
import java.util.random.RandomGenerator;

// counts farming time only, so pests, visitors and rests never bring the next pause closer
final class MicropauseScheduler {
    enum Action {
        FARM,
        BEGIN_PAUSE,
        PAUSED,
        END_PAUSE
    }

    record Settings(boolean enabled, long intervalMinMs, long intervalMaxMs, long durationMinMs, long durationMaxMs) {
        static Settings of(boolean enabled, int intervalMinMinutes, int intervalMaxMinutes,
                           int durationMinSeconds, int durationMaxSeconds) {
            return new Settings(enabled,
                    intervalMinMinutes * 60_000L,
                    intervalMaxMinutes * 60_000L,
                    durationMinSeconds * 1_000L,
                    durationMaxSeconds * 1_000L);
        }

        static Settings configured() {
            return of(AetherConfig.MICROPAUSE_ENABLED.get(),
                    AetherConfig.MICROPAUSE_INTERVAL_MIN_MINUTES.get(),
                    AetherConfig.MICROPAUSE_INTERVAL_MAX_MINUTES.get(),
                    AetherConfig.MICROPAUSE_DURATION_MIN_SECONDS.get(),
                    AetherConfig.MICROPAUSE_DURATION_MAX_SECONDS.get());
        }
    }

    // a lag spike or a stalled client must not count as minutes of farming
    private static final long MAX_ACCRUAL_STEP_MS = 1_000L;
    // a due pause waits this long into a calm stretch, so it never lines up with a lane switch or a re-enable
    private static final long SETTLE_MIN_MS = 2_000L;
    private static final long SETTLE_MAX_MS = 6_000L;
    private static final long NEVER = Long.MIN_VALUE;

    private boolean armed;
    private boolean paused;
    private long intervalMs;
    private long sampledIntervalMinMs;
    private long sampledIntervalMaxMs;
    private long accruedMs;
    private long lastFarmingAt = NEVER;
    private long readySince = NEVER;
    private long settleMs;
    private long pauseEndsAt;

    void reset() {
        paused = false;
        disarm();
    }

    Action update(long now, boolean farming, BooleanSupplier canStart, Settings settings, RandomGenerator random) {
        if (!settings.enabled()) {
            return stop();
        }
        if (paused) {
            return farming && now < pauseEndsAt ? Action.PAUSED : stop();
        }
        if (!farming) {
            lastFarmingAt = NEVER;
            readySince = NEVER;
            return Action.FARM;
        }

        if (!armed) {
            armed = true;
            accruedMs = 0L;
            sampleInterval(settings, random);
        } else if (settings.intervalMinMs() != sampledIntervalMinMs
                || settings.intervalMaxMs() != sampledIntervalMaxMs) {
            sampleInterval(settings, random);
        }
        if (lastFarmingAt != NEVER) {
            accruedMs += Math.clamp(now - lastFarmingAt, 0L, MAX_ACCRUAL_STEP_MS);
        }
        lastFarmingAt = now;

        if (accruedMs < intervalMs || !canStart.getAsBoolean()) {
            readySince = NEVER;
            return Action.FARM;
        }
        if (readySince == NEVER) {
            readySince = now;
            settleMs = uniform(random, SETTLE_MIN_MS, SETTLE_MAX_MS);
        }
        if (now - readySince < settleMs) {
            return Action.FARM;
        }

        paused = true;
        readySince = NEVER;
        pauseEndsAt = now + uniform(random, settings.durationMinMs(), settings.durationMaxMs());
        return Action.BEGIN_PAUSE;
    }

    boolean isPaused() {
        return paused;
    }

    // -1 until the first farming tick arms the next interval
    long farmingMsUntilDue() {
        return armed ? Math.max(0L, intervalMs - accruedMs) : -1L;
    }

    long pauseRemainingMs(long now) {
        return paused ? Math.max(0L, pauseEndsAt - now) : 0L;
    }

    private Action stop() {
        boolean wasPaused = paused;
        paused = false;
        disarm();
        return wasPaused ? Action.END_PAUSE : Action.FARM;
    }

    private void disarm() {
        armed = false;
        accruedMs = 0L;
        lastFarmingAt = NEVER;
        readySince = NEVER;
    }

    private void sampleInterval(Settings settings, RandomGenerator random) {
        sampledIntervalMinMs = settings.intervalMinMs();
        sampledIntervalMaxMs = settings.intervalMaxMs();
        intervalMs = uniform(random, sampledIntervalMinMs, sampledIntervalMaxMs);
    }

    // inclusive on both ends; equal or swapped bounds are fine
    private static long uniform(RandomGenerator random, long a, long b) {
        long low = Math.min(a, b);
        long high = Math.max(a, b);
        return low + random.nextLong(high - low + 1L);
    }
}
