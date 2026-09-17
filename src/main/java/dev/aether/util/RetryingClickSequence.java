package dev.aether.util;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

public final class RetryingClickSequence {
    private static final long POLL_INTERVAL_MS = 50L;

    private RetryingClickSequence() {
    }

    public record Stage(String name, BooleanSupplier completed, BooleanSupplier click, long timeoutMs) {
    }

    public static boolean run(List<Stage> stages, long clickDelayMs, long retryDelayMs, BooleanSupplier cancelled,
                              LongConsumer sleeper, Consumer<String> logger) {
        return run(stages, clickDelayMs, retryDelayMs, cancelled, System::currentTimeMillis, sleeper, logger);
    }

    static boolean run(List<Stage> stages, long clickDelayMs, long retryDelayMs, BooleanSupplier cancelled,
                       LongSupplier clock, LongConsumer sleeper, Consumer<String> logger) {
        long normalizedRetryDelay = Math.max(POLL_INTERVAL_MS, retryDelayMs);
        for (Stage stage : stages) {
            sleeper.accept(Math.max(0L, clickDelayMs));
            long deadline = clock.getAsLong() + stage.timeoutMs();
            long nextClickAt = 0L;
            int attempts = 0;
            while (!cancelled.getAsBoolean() && !Thread.currentThread().isInterrupted()
                    && clock.getAsLong() < deadline) {
                if (stage.completed().getAsBoolean()) {
                    break;
                }
                long now = clock.getAsLong();
                if (now >= nextClickAt && stage.click().getAsBoolean()) {
                    attempts++;
                    logger.accept("Clicked " + stage.name() + " (attempt " + attempts + ")");
                    nextClickAt = now + normalizedRetryDelay;
                    if (stage.completed().getAsBoolean()) {
                        break;
                    }
                }
                sleeper.accept(POLL_INTERVAL_MS);
            }
            if (!stage.completed().getAsBoolean()) {
                logger.accept("Timed out waiting for " + stage.name() + " after " + attempts + " click attempts");
                return false;
            }
        }
        return true;
    }
}
