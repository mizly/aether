package dev.aether.modules.pathfinding.execution;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class WalkingRouteTest {
    @Test
    void measuresProgressInBlocksOnLongSegments() {
        WalkingRoute route = new WalkingRoute(List.of(Vec3.ZERO, new Vec3(20, 0, 0)));
        assertEquals(0.1, route.progress(new Vec3(0.1, 0, 0), 0), 1.0e-8);
        assertEquals(5.0, route.progress(new Vec3(5, 0, 0), 0), 1.0e-8);
    }

    @Test
    void measuresDriftFromSegmentsInsteadOfSparseWaypoints() {
        WalkingRoute route = new WalkingRoute(List.of(Vec3.ZERO, new Vec3(20, 0, 0)));
        assertEquals(0.0, route.distance(new Vec3(10, 0, 0), 0), 1.0e-8);
        assertEquals(2.0, route.distance(new Vec3(10, 0, 2), 0), 1.0e-8);
    }

    @Test
    void cannotSkipAnAscentOrFinishOnTheWrongFloor() {
        WalkingRoute route = new WalkingRoute(List.of(Vec3.ZERO, new Vec3(1, 1, 0), new Vec3(2, 1, 0)));
        assertEquals(0, route.advance(new Vec3(1.1, 0, 0), 0));
        assertEquals(1, route.advance(new Vec3(1.1, 1, 0), 0));
        assertFalse(WalkingRoute.reachedGoal(new Vec3(2, 0, 0), new Vec3(2, 1, 0), 1.2));
    }

    @Test
    void preservesVerticalSegmentsUntilTheirHeightIsReached() {
        WalkingRoute route = new WalkingRoute(List.of(Vec3.ZERO, new Vec3(0, 3, 0), new Vec3(1, 3, 0)));
        assertEquals(0, route.advance(Vec3.ZERO, 0));
        assertEquals(1.5, route.progress(new Vec3(0, 1.5, 0), 0), 1.0e-8);
        assertEquals(1, route.advance(new Vec3(0, 3, 0), 0));
    }

    @Test
    void rejectsPassingAWaypointFromFarOffTheRoute() {
        WalkingRoute route = new WalkingRoute(List.of(Vec3.ZERO, new Vec3(3, 0, 0), new Vec3(3, 0, 3)));
        assertEquals(0, route.advance(new Vec3(3.1, 0, 2), 0));
        assertEquals(1, route.advance(new Vec3(3.1, 0, 0.1), 0));
    }

    @Test
    void advancesPastTheLedgeWhenAlreadyFallingDownThePlannedDrop() {
        WalkingRoute route = new WalkingRoute(List.of(new Vec3(0, 20, 0), new Vec3(1, 20, 0),
                new Vec3(2, 0, 0), new Vec3(6, 0, 0)));
        assertEquals(1, route.advance(new Vec3(1.7, 8, 0), 0));
        assertEquals(1, route.advance(new Vec3(2, 20, 0), 0));
        assertEquals(2, route.advance(new Vec3(2, 0, 0), 1));
    }

    @Test
    void advancesWhenAFallPassesAWaypointBetweenTicks() {
        WalkingRoute route = new WalkingRoute(List.of(new Vec3(0, 20, 0), new Vec3(0, 15, 0),
                new Vec3(0, 5, 0), new Vec3(1, 5, 0)));
        assertEquals(1, route.advance(new Vec3(0, 10, 0), 0));
    }

    @Test
    void advancesThroughVerticalFallNodesWithLateralDrift() {
        List<Vec3> points = IntStream.rangeClosed(0, 40)
                .mapToObj(i -> new Vec3(0.5, 40 - i, 0.5)).toList();
        WalkingRoute route = new WalkingRoute(points);
        int segment = 0;
        double previousProgress = -1;
        for (double height = 39.2; height >= 0.0; height -= 2.8) {
            Vec3 feet = new Vec3(0.95, height, 0.8);
            segment = route.advance(feet, segment);
            assertTrue(points.get(segment).y <= height + 1,
                    "A passed fall node held pursuit above " + feet);
            double progress = route.progress(feet, segment);
            assertTrue(progress > previousProgress);
            previousProgress = progress;
        }
    }

    @Test
    void leavesTheLedgeBehindAfterFallingBeforeReachingItsCenter() {
        WalkingRoute route = new WalkingRoute(List.of(new Vec3(0.5, 20, 0.5), new Vec3(1.5, 20, 0.5),
                new Vec3(1.5, 19, 0.5), new Vec3(1.5, 18, 0.5), new Vec3(1.5, 0, 0.5)));
        assertEquals(3, route.advance(new Vec3(1.3, 10, 0.8), 0));
    }

    @Test
    void continuesAlongTheLandingAfterAnOffCenterVerticalFall() {
        WalkingRoute route = new WalkingRoute(List.of(new Vec3(0.5, 3, 0.5), new Vec3(0.5, 2, 0.5),
                new Vec3(0.5, 1, 0.5), new Vec3(0.5, 0, 0.5), new Vec3(4.5, 0, 0.5)));
        Vec3 feet = new Vec3(0.95, 0, 0.8);
        int segment = route.advance(feet, 0);
        assertEquals(3, segment);
        assertTrue(route.steeringTarget(feet, segment, 1).x > feet.x);
    }

    @Test
    void doesNotAdvanceAClimbOrAnUnrelatedFallUsingLateralTolerance() {
        WalkingRoute climb = new WalkingRoute(List.of(Vec3.ZERO, new Vec3(0, 5, 0), new Vec3(1, 5, 0)));
        assertEquals(0, climb.advance(new Vec3(0.6, 5, 0), 0));
        WalkingRoute fall = new WalkingRoute(List.of(new Vec3(0, 5, 0), Vec3.ZERO, new Vec3(1, 0, 0)));
        assertEquals(0, fall.advance(new Vec3(2, 0, 0), 0));
    }

    @Test
    void advancesPastStairsWhileAboveTheirNominalHeight() {
        WalkingRoute route = new WalkingRoute(List.of(Vec3.ZERO, new Vec3(0, 1, 1),
                new Vec3(0, 2, 2), new Vec3(0, 3, 3)));
        assertEquals(1, route.advance(new Vec3(0, 2.1, 1.05), 0));
    }

    @Test
    void doesNotSkipAnUpperFloorWithoutAPlannedDrop() {
        WalkingRoute route = new WalkingRoute(List.of(new Vec3(0, 20, 0), new Vec3(1, 20, 0),
                new Vec3(2, 20, 0)));
        assertEquals(0, route.advance(new Vec3(1, 0, 0), 0));
    }

    @Test
    void measuresProgressThroughoutASteepDropWithoutHorizontalMovement() {
        WalkingRoute route = new WalkingRoute(List.of(new Vec3(0, 20, 0), new Vec3(1, 0, 0)));
        double early = route.progress(new Vec3(0.7, 15, 0), 0);
        double late = route.progress(new Vec3(0.7, 5, 0), 0);
        assertTrue(late > early + 9.0);
    }

    @Test
    void cannotBypassAnUnclimbedStepThatHasADropBehindIt() {
        WalkingRoute route = new WalkingRoute(List.of(Vec3.ZERO, new Vec3(0, 1, 1), new Vec3(0, 0, 2)));
        assertEquals(0, route.advance(new Vec3(0, 0, 1), 0));
    }

    @Test
    void skipsPassedStraightWaypointsAfterAFastStep() {
        WalkingRoute route = new WalkingRoute(List.of(Vec3.ZERO, new Vec3(0, 1, 1),
                new Vec3(0, 2, 2), new Vec3(0, 3, 3), new Vec3(0, 4, 4)));
        assertEquals(3, route.advance(new Vec3(0.1, 3.5, 3.5), 0));
    }

    @Test
    void steersForwardThroughoutStairsDespiteSidewaysOffsetsAndJumpHeight() {
        WalkingRoute route = new WalkingRoute(List.of(Vec3.ZERO, new Vec3(0, 1, 1),
                new Vec3(0, 2, 2), new Vec3(0, 3, 3), new Vec3(0, 4, 4), new Vec3(0, 5, 5)));
        int segment = 0;
        for (double z = 0.1; z < 4.0; z += 0.15) {
            Vec3 feet = new Vec3(0.3, Math.floor(z) + 1.1, z);
            segment = route.advance(feet, segment);
            Vec3 offset = route.steeringTarget(feet, segment, 1.0).subtract(feet);
            assertEquals(new WalkingMotion.Input(1, 0), WalkingMotion.horizontalInput(offset, 0, 0.08),
                    "Stair steering reversed or strafed at " + feet);
        }
    }

    @Test
    void steeringDoesNotCutAcrossCornersOrContinueBeyondADropLanding() {
        Vec3 corner = new Vec3(0, 1, 1);
        WalkingRoute stairs = new WalkingRoute(List.of(Vec3.ZERO, corner, new Vec3(1, 2, 1)));
        assertEquals(corner, stairs.steeringTarget(new Vec3(0, 0.5, 0.8), 0, 1.0));

        Vec3 landing = new Vec3(1, 0, 0);
        WalkingRoute drop = new WalkingRoute(List.of(new Vec3(0, 20, 0), landing, new Vec3(5, 0, 0)));
        assertEquals(landing, drop.steeringTarget(new Vec3(1, 10, 0), 0, 1.0));
    }

    @Test
    void steeringPreservesVerticalClimbsAndStopsAtTheEndpoint() {
        Vec3 top = new Vec3(0, 3, 0);
        Vec3 end = new Vec3(1, 3, 0);
        WalkingRoute route = new WalkingRoute(List.of(Vec3.ZERO, top, end));
        assertEquals(top, route.steeringTarget(new Vec3(0, 1, 0), 0, 1.0));
        assertEquals(end, route.steeringTarget(end, 2, 1.0));
    }

    @Test
    void preciseGoalsHonorTheRequestedHorizontalTolerance() {
        assertFalse(WalkingRoute.reachedGoal(new Vec3(0.4, 0, 0), Vec3.ZERO, 0.25));
        assertTrue(WalkingRoute.reachedGoal(new Vec3(0.2, 0, 0), Vec3.ZERO, 0.25));
        assertTrue(WalkingRoute.reachedGoal(new Vec3(0.2, -0.5, 0), Vec3.ZERO, 0.25));
    }

    @Test
    void partialEndpointsCannotAuthorizeMovementToTheOriginalGoal() {
        WalkingRoute route = new WalkingRoute(List.of(new Vec3(0.5, 0, 0.5), new Vec3(3.5, 0, 0.5)));
        assertFalse(route.endsAt(new Vec3(5.1, 0, 0.5)));
        assertFalse(route.endsAt(new Vec3(3.5, 1, 0.5)));
        assertTrue(route.endsAt(new Vec3(3.9, 0, 0.1)));
    }
}
