package dev.aether.modules.profit.helpers;

import dev.aether.macro.MacroStateManager;
import dev.aether.modules.profit.ProfitManager;

import java.util.concurrent.atomic.AtomicLong;

// session totals averaged over the macro's running time, the same clock coins per hour uses
public final class ActivityRateTracker {

    private static final AtomicLong mobsKilled = new AtomicLong();
    private static final AtomicLong blocksBroken = new AtomicLong();

    private ActivityRateTracker() {
    }

    public static void onMobKilled() {
        if (ProfitManager.isProfitTrackingActive()) {
            mobsKilled.incrementAndGet();
        }
    }

    public static void onBlockBroken() {
        if (ProfitManager.isProfitTrackingActive()) {
            blocksBroken.incrementAndGet();
        }
    }

    public static void reset() {
        mobsKilled.set(0L);
        blocksBroken.set(0L);
    }

    public static long getMobsKilled() {
        return mobsKilled.get();
    }

    public static long getBlocksBroken() {
        return blocksBroken.get();
    }

    public static long getMobsPerHour() {
        return perHour(mobsKilled.get(), MacroStateManager.getSessionRunningTime());
    }

    public static long getBlocksPerHour() {
        return perHour(blocksBroken.get(), MacroStateManager.getSessionRunningTime());
    }

    static long perHour(long count, long sessionMs) {
        return sessionMs > 0 ? (long) (count * 3_600_000.0 / sessionMs) : 0L;
    }
}
