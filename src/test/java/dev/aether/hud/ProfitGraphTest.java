package dev.aether.hud;

import dev.aether.modules.profit.SessionProfitHistory;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProfitGraphTest {
    @Test
    void convertsVisibleSessionBalancesToCoinsPerHour() {
        var snapshot = new SessionProfitHistory.Snapshot(120_000, List.of(
                new SessionProfitHistory.Point(0, 0),
                new SessionProfitHistory.Point(30_000, 1_000),
                new SessionProfitHistory.Point(80_000, -200)), 1);
        var points = ProfitGraph.visibleRates(snapshot, 60_000);
        assertEquals(List.of(new ProfitGraph.RatePoint(60_000, 60_000),
                new ProfitGraph.RatePoint(80_000, 45_000),
                new ProfitGraph.RatePoint(80_000, -9_000),
                new ProfitGraph.RatePoint(120_000, -6_000)), points);
    }

    @Test
    void shortSessionsHaveNoInventedHistoryBeforeTheSession() {
        var history = new SessionProfitHistory();
        history.reset(0, true);
        history.record(1_000_000_000L, 50);
        var points = ProfitGraph.visibleRates(history.snapshot(2_000_000_000L), 300_000);
        assertEquals(0, points.getFirst().timeMillis());
        assertEquals(new ProfitGraph.RatePoint(2_000, 90_000), points.getLast());
    }

    @Test
    void rateFallsAsActiveTimePassesWithoutNewProfit() {
        var snapshot = new SessionProfitHistory.Snapshot(7_200_000,
                List.of(new SessionProfitHistory.Point(3_600_000, 1_000)), 1);
        var points = ProfitGraph.visibleRates(snapshot, 7_200_000);
        assertEquals(new ProfitGraph.RatePoint(3_600_000, 0), points.get(1));
        assertEquals(new ProfitGraph.RatePoint(3_600_000, 1_000), points.get(2));
        assertEquals(new ProfitGraph.RatePoint(7_200_000, 500), points.getLast());
    }

    @Test
    void scalesEmptyFlatNegativeAndLargeBalancesWithoutClippingOrDuplicateLabels() {
        for (double[] range : new double[][]{{0, 0}, {-10, -10}, {20, 30}, {-900, 200},
                {1e12, 1e12 + 10}, {Long.MAX_VALUE, Long.MAX_VALUE}, {Long.MIN_VALUE, Long.MAX_VALUE}}) {
            var bounds = ProfitGraphScale.target(range[0], range[1]);
            assertTrue(bounds.max() > bounds.min());
            assertTrue(bounds.min() <= range[0]);
            assertTrue(bounds.max() >= range[1]);
            assertTrue(Double.isFinite(bounds.fraction(range[0])));
            double step = ProfitGraphScale.tickStep(bounds.max() - bounds.min());
            double offset = ProfitGraphScale.offset(bounds);
            var labels = new HashSet<String>();
            for (double tick = Math.ceil(bounds.min() / step) * step; tick <= bounds.max(); tick += step) {
                assertTrue(labels.add(ProfitGraphScale.label(tick - offset, step)), "Distinct axis ticks need distinct labels");
            }
        }
    }

    @Test
    void scaleAnimationIsIndependentOfFrameRateAndContainsSuddenSpikes() {
        var lowFps = new ProfitGraphScale();
        var highFps = new ProfitGraphScale();
        lowFps.update(-1_000, 10_000, 0, true);
        highFps.update(-1_000, 10_000, 0, true);
        var low = lowFps.update(0, 100, 1_000_000_000L, false);
        ProfitGraphScale.Bounds high = null;
        for (int i = 1; i <= 100; i++) high = highFps.update(0, 100, i * 10_000_000L, false);
        assertEquals(low.min(), high.min(), 1e-8);
        assertEquals(low.max(), high.max(), 1e-8);
        var spike = highFps.update(-50_000, 1_000_000, 1_016_000_000L, false);
        assertTrue(spike.min() <= -50_000);
        assertTrue(spike.max() >= 1_000_000);
    }
}
