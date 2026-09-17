package dev.aether.modules.profit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SessionProfitHistoryTest {
    @Test
    void clockMovesBetweenTicksAndExcludesPausedAndRecoveryTime() {
        var history = new SessionProfitHistory();
        history.reset(0, true);
        history.record(1_000_000_000L, 100);
        assertEquals(1_016.5, history.snapshot(1_016_500_000L).elapsedMillis());
        history.setRunning(2_000_000_000L, false);
        assertEquals(2_000, history.snapshot(100_000_000_000L).elapsedMillis());
        history.setRunning(100_000_000_000L, true);
        assertEquals(2_500, history.snapshot(100_500_000_000L).elapsedMillis());
    }

    @Test
    void preservesIntraSecondPeaksCostsAndTheFinalTotalInTimeOrder() {
        var history = new SessionProfitHistory();
        history.reset(0, true);
        history.record(100_000_000, 1_000);
        history.record(200_000_000, 100);
        history.record(300_000_000, -500);
        history.record(400_000_000, 20);
        var points = history.snapshot(400_000_000).points();
        assertEquals(1_000, points.stream().mapToLong(SessionProfitHistory.Point::coins).max().orElseThrow());
        assertEquals(-500, points.stream().mapToLong(SessionProfitHistory.Point::coins).min().orElseThrow());
        assertEquals(20, points.getLast().coins());
        for (int i = 1; i < points.size(); i++) assertTrue(points.get(i).timeMillis() >= points.get(i - 1).timeMillis());
        assertThrows(UnsupportedOperationException.class, () -> points.clear());
    }

    @Test
    void historyRemainsBoundedDuringLongSessionsAndHighFrequencyUpdates() {
        var history = new SessionProfitHistory();
        history.reset(0, true);
        for (int i = 1; i <= 1_800_000; i++) history.record(i * 1_000_000L, i % 2 == 0 ? i : -i);
        var snapshot = history.snapshot(1_800_000_000_000L);
        assertTrue(snapshot.points().size() <= 4 * 902);
        assertEquals(1_800_000, snapshot.points().getLast().coins());
        assertTrue(snapshot.points().getFirst().timeMillis() <= 900_000);
        assertTrue(snapshot.points().getFirst().timeMillis() >= 898_000);
    }

    @Test
    void idleGapsPreserveTheLastKnownBalanceWithoutInventingSamples() {
        var history = new SessionProfitHistory();
        history.reset(0, true);
        history.record(1_000_000_000L, 123);
        history.record(7_200_000_000_000L, 123);
        var points = history.snapshot(7_200_000_000_000L).points();
        assertEquals(1, points.size());
        assertEquals(123, points.getLast().coins());
    }

    @Test
    void resettingClearsBalancesAndChangesTheGraphGeneration() {
        var history = new SessionProfitHistory();
        history.reset(0, true);
        history.record(1_000_000_000L, 999);
        var before = history.snapshot(1_000_000_000L);
        history.reset(2_000_000_000L, false);
        var after = history.snapshot(9_000_000_000L);
        assertEquals(0, after.elapsedMillis());
        assertEquals(0, after.points().getLast().coins());
        assertNotEquals(before.generation(), after.generation());
    }

    @Test
    void backwardTimestampsDoNotRewindOrDoubleCountTheClock() {
        var history = new SessionProfitHistory();
        history.reset(0, true);
        history.record(2_000_000_000L, 100);
        history.record(1_000_000_000L, 200);
        assertEquals(2_000, history.snapshot(1_000_000_000L).elapsedMillis());
        assertEquals(3_000, history.snapshot(3_000_000_000L).elapsedMillis());
        assertEquals(200, history.snapshot(3_000_000_000L).points().getLast().coins());
    }
}
