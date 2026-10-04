package dev.aether.modules.profit.helpers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActivityRateTrackerTest {
    @Test
    void aSessionCountIsScaledToAnHour() {
        assertEquals(120L, ActivityRateTracker.perHour(60L, 1_800_000L));
        assertEquals(60L, ActivityRateTracker.perHour(60L, 3_600_000L));
    }

    @Test
    void noRunningTimeMeansNoRate() {
        assertEquals(0L, ActivityRateTracker.perHour(25L, 0L));
    }
}
