package dev.aether.util;

import dev.aether.config.AetherConfig;
import dev.aether.macro.FarmingMacroManager;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Tracks blocks broken per second during the farming stage.
 */
public class BpsTracker {
    private static final Deque<Long> breakTimes = new ArrayDeque<>();
    private static long farmingClockMs = 0;

    /**
     * Called when a block is successfully broken.
     */
    public static void onBlockBreak() {
        if (!FarmingMacroManager.isActive()) return;
        var active = FarmingMacroManager.getActiveMacro();
        if (active == null || !active.isFarmingState()) return;

        synchronized (breakTimes) {
            breakTimes.addLast(farmingClockMs);
            cleanup();
        }
    }

    /**
     * Advances the farming clock if currently in farming state.
     * Call this every tick.
     */
    public static void tick() {
        if (FarmingMacroManager.isActive()) {
            var active = FarmingMacroManager.getActiveMacro();
            if (active != null && active.isFarmingState()) {
                farmingClockMs += 50; // 20 ticks per second = 50ms per tick
            }
        }
        synchronized (breakTimes) {
            cleanup();
        }
    }

    /**
     * Removes timestamps older than the configured window.
     */
    private static void cleanup() {
        long windowMs = AetherConfig.BPS_AVERAGE_WINDOW.get() * 1000L;
        while (!breakTimes.isEmpty() && farmingClockMs - breakTimes.peekFirst() > windowMs) {
            breakTimes.pollFirst();
        }
    }

    /**
     * Resets the farming clock and break history.
     */
    public static void reset() {
        synchronized (breakTimes) {
            breakTimes.clear();
            farmingClockMs = 0;
        }
    }

    /**
     * Returns the number of breaks in the current window.
     */
    public static int getBreakCount() {
        synchronized (breakTimes) {
            return breakTimes.size();
        }
    }

    /**
     * Returns the actual duration of the current window (in seconds),
     * which is either the configured amount or the total farming time, whichever is smaller.
     */
    public static float getActualWindowSeconds() {
        float window = (float) AetherConfig.BPS_AVERAGE_WINDOW.get();
        float elapsed = farmingClockMs / 1000.0f;
        return Math.max(0.1f, Math.min(window, elapsed));
    }

    /**
     * Returns the average BPS over the window.
     */
    public static float getBps() {
        synchronized (breakTimes) {
            cleanup();
            float window = getActualWindowSeconds();
            return (float) breakTimes.size() / window;
        }
    }
}
