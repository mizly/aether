package dev.aether.modules.pathfinding.execution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlightProgressTrackerTest {
    @Test
    void backAndForthMovementDoesNotKeepAStalledRouteAlive() {
        var progress = new FlightProgressTracker();
        assertEquals(0, progress.stalledFor(1, 5, 0));
        for (int tick = 1; tick <= 80; tick++) {
            assertEquals(tick * 50L, progress.stalledFor(1, tick % 2 == 0 ? 5 : 5.3, tick * 50L));
        }
    }

    @Test
    void movingAwayFromTheWaypointDoesNotCountAsProgress() {
        var progress = new FlightProgressTracker();
        progress.stalledFor(1, 5, 1000);
        assertEquals(3500, progress.stalledFor(1, 8, 4500));
    }

    @Test
    void cumulativeSlowMovementTowardTheWaypointKeepsTheRouteAlive() {
        var progress = new FlightProgressTracker();
        for (int tick = 0; tick <= 100; tick++) {
            assertTrue(progress.stalledFor(1, 5 - tick * 0.01, tick * 50L) < 1000);
        }
    }

    @Test
    void advancingToANewWaypointStartsANewProgressWindow() {
        var progress = new FlightProgressTracker();
        progress.stalledFor(1, 1, 1000);
        assertEquals(0, progress.stalledFor(2, 10, 4000));
        assertEquals(1000, progress.stalledFor(2, 10, 5000));
    }

    @Test
    void restartingTheSameRouteResetsItsProgress() {
        var progress = new FlightProgressTracker();
        progress.stalledFor(1, 1, 1000);
        progress.reset();
        assertEquals(0, progress.stalledFor(1, 5, 4000));
        assertEquals(1000, progress.stalledFor(1, 5, 5000));
    }
}
