package dev.aether.util;

import dev.aether.config.AetherConfig;
import dev.aether.macro.farming.FarmingMacroManager;
import java.util.ArrayDeque;
import java.util.Deque;

public class BpsTracker {
    private static final Deque<Long> breakTimes = new ArrayDeque<>();
    private static long farmingClockMs = 0;

    public static void onBlockBreak() {
        if (!FarmingMacroManager.isActive()) return;
        var active = FarmingMacroManager.getActiveMacro();
        if (active == null || !active.isFarmingState()) return;

        synchronized (breakTimes) {
            breakTimes.addLast(farmingClockMs);
            cleanup();
        }
    }

    // call every tick
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

    private static void cleanup() {
        long windowMs = AetherConfig.BPS_AVERAGE_WINDOW.get() * 1000L;
        while (!breakTimes.isEmpty() && farmingClockMs - breakTimes.peekFirst() > windowMs) {
            breakTimes.pollFirst();
        }
    }

    public static void reset() {
        synchronized (breakTimes) {
            breakTimes.clear();
            farmingClockMs = 0;
        }
    }

    public static int getBreakCount() {
        synchronized (breakTimes) {
            return breakTimes.size();
        }
    }

    // the configured window or the total farming time, whichever is smaller
    public static float getActualWindowSeconds() {
        float window = (float) AetherConfig.BPS_AVERAGE_WINDOW.get();
        float elapsed = farmingClockMs / 1000.0f;
        return Math.max(0.1f, Math.min(window, elapsed));
    }

    public static float getBps() {
        synchronized (breakTimes) {
            cleanup();
            float window = getActualWindowSeconds();
            return (float) breakTimes.size() / window;
        }
    }
}
