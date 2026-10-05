package dev.aether.macro.fishing;

import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.random.RandomGenerator;

// right clicks an item kept in another slot: select it, a draw beat, the click, then the key back to the other slot
final class AbilitySwapClicker {

    // now and then the key back is fumbled a little late, the way a real hand misses the rhythm
    private static final int HESITATE_ONE_IN = 12;
    private static final long HESITATE_MIN_MS = 30L;
    private static final long HESITATE_MAX_MS = 90L;

    // the hotbar key goes down a beat before the right click, never on the same frame; the interval runs
    // from the key going back (or the click, with nothing to go back to) and the floor from click to click
    record Timing(long drawMinMs, long drawMaxMs, long intervalMinMs, long intervalMaxMs, long minClickGapMs) {
    }

    // the whip's swing runs about half a second, and a click inside it reads as a macro
    static final Timing SOUL_WHIP = new Timing(45L, 120L, 500L, 800L, 550L);
    // wither impact has a 0.15 s cooldown, so a faster click is a wasted one
    static final Timing HYPERION = new Timing(45L, 120L, 160L, 280L, 150L);
    // a heal takes a moment to show, so a second click inside a second is a wasted one
    static final Timing HEALING_WAND = new Timing(45L, 120L, 1_000L, 1_500L, 1_000L);

    private final Timing timing;
    private final IntConsumer select;
    private final Runnable click;
    private long clickAt;
    private long swapAt;
    private long nextAt;
    private long lastClickAt;
    private int clickTick;

    AbilitySwapClicker(Timing timing, IntConsumer select, Runnable click) {
        this.timing = timing;
        this.select = select;
        this.click = click;
    }

    // true on the tick the click goes out; a back slot of -1 keeps the item in hand after it
    boolean tick(long now, int tick, int abilitySlot, int backSlot, int swapMinMs, int swapMaxMs,
                 BooleanSupplier aimReady, RandomGenerator random) {
        if (swapAt != 0L) {
            // the click is only sent on the tick after it was queued, and the swap must not beat it there
            if (now >= swapAt && tick > clickTick) {
                select.accept(backSlot);
                swapAt = 0L;
                nextAt = nextClickAt(now, lastClickAt, nextIntervalMs(random, timing), timing.minClickGapMs());
            }
            return false;
        }

        if (clickAt != 0L) {
            if (now < clickAt) {
                return false;
            }
            click.run();
            clickAt = 0L;
            lastClickAt = now;
            clickTick = tick;
            if (backSlot < 0) {
                nextAt = nextClickAt(now, now, nextIntervalMs(random, timing), timing.minClickGapMs());
            } else {
                swapAt = now + nextSwapDelayMs(random, swapMinMs, swapMaxMs);
            }
            return true;
        }

        if (now < nextAt || !aimReady.getAsBoolean()) {
            return false;
        }
        select.accept(abilitySlot);
        clickAt = now + nextDrawDelayMs(random, timing);
        return false;
    }

    // a click or a swap back still pending, so stopping here would leave the item in hand
    boolean midUse() {
        return clickAt != 0L || swapAt != 0L;
    }

    // a new target drops the rhythm but not the floor since the last click
    void reset() {
        clickAt = 0L;
        swapAt = 0L;
        nextAt = lastClickAt == 0L ? 0L : lastClickAt + timing.minClickGapMs();
    }

    // two uniforms averaged make a triangle, so most swaps land mid range and the edges stay rare
    static long nextSwapDelayMs(RandomGenerator random, int min, int max) {
        int lo = Math.min(min, max);
        int hi = Math.max(min, max);
        double t = (random.nextDouble() + random.nextDouble()) / 2.0;
        long delay = Math.round(lo + (hi - lo) * t);
        if (random.nextInt(HESITATE_ONE_IN) == 0) {
            delay += random.nextLong(HESITATE_MIN_MS, HESITATE_MAX_MS + 1);
        }
        return delay;
    }

    static boolean swapDelayInRange(long delay, int min, int max) {
        return delay >= Math.min(min, max) && delay <= Math.max(min, max) + HESITATE_MAX_MS;
    }

    static long nextDrawDelayMs(RandomGenerator random, Timing timing) {
        return between(random, timing.drawMinMs(), timing.drawMaxMs());
    }

    static boolean drawDelayInRange(long delay, Timing timing) {
        return inRange(delay, timing.drawMinMs(), timing.drawMaxMs());
    }

    static long nextIntervalMs(RandomGenerator random, Timing timing) {
        return between(random, timing.intervalMinMs(), timing.intervalMaxMs());
    }

    // the interval runs from the key going back, but a quick swap back must not pull the next click in
    static long nextClickAt(long swapAt, long clickAt, long intervalMs, long minClickGapMs) {
        return Math.max(swapAt + intervalMs, clickAt + minClickGapMs);
    }

    static boolean intervalInRange(long delay, Timing timing) {
        return inRange(delay, timing.intervalMinMs(), timing.intervalMaxMs());
    }

    private static long between(RandomGenerator random, long a, long b) {
        return random.nextLong(Math.min(a, b), Math.max(a, b) + 1);
    }

    private static boolean inRange(long value, long a, long b) {
        return value >= Math.min(a, b) && value <= Math.max(a, b);
    }
}
