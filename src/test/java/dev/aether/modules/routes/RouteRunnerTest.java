package dev.aether.modules.routes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteRunnerTest {

    @Test
    void standingOnTheLipOfAWaypointStillCountsAsThere() {
        assertTrue(RouteRunner.isWithinWaypoint(0.6, 0.0, -0.6, 0.7));
        assertTrue(RouteRunner.isWithinWaypoint(0.0, 1.0, 0.0, 0.7));
    }

    @Test
    void aBlockOverOrAFallBelowIsNotThere() {
        assertFalse(RouteRunner.isWithinWaypoint(0.9, 0.0, 0.0, 0.7));
        assertFalse(RouteRunner.isWithinWaypoint(0.0, -1.5, 0.0, 0.7));
    }

    @Test
    void aWalkPassingThroughAWaypointCountsFromFurtherOut() {
        assertTrue(RouteRunner.isWithinWaypoint(1.2, 0.0, 0.0, 1.3));
    }
}
