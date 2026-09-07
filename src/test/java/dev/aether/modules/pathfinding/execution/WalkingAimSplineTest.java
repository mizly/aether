package dev.aether.modules.pathfinding.execution;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class WalkingAimSplineTest {
    @Test
    void followsArcDistanceIndependentlyOfWaypointDensity() {
        WalkingAimSpline sparse = new WalkingAimSpline(List.of(Vec3.ZERO, new Vec3(0, 0, 10)));
        WalkingAimSpline dense = new WalkingAimSpline(List.of(Vec3.ZERO, new Vec3(0, 0, 2),
                new Vec3(0, 0, 4), new Vec3(0, 0, 6), new Vec3(0, 0, 10)));
        Vec3 feet = new Vec3(0, 0, 3);
        Vec3 expected = new Vec3(0, 1.62, 6.5);
        assertPoint(expected, sparse.aimPoint(feet, 0, 3.5, 1.62));
        assertPoint(expected, dense.aimPoint(feet, 1, 3.5, 1.62));
    }

    @Test
    void followsRoundedCornersContinuouslyInsteadOfSnappingBetweenNodes() {
        WalkingAimSpline spline = new WalkingAimSpline(List.of(Vec3.ZERO,
                new Vec3(0, 0, 5), new Vec3(5, 0, 5)));
        Vec3 before = spline.aimPoint(new Vec3(0, 0, 3), 0, 1.5, 1.62);
        assertTrue(before.x > 0.0 && before.x < 1.0);
        assertTrue(before.z > 4.0 && before.z < 5.0);
        Vec3 after = spline.aimPoint(new Vec3(0, 0, 3.02), 0, 1.5, 1.62);
        assertTrue(after.distanceTo(before) <= 0.020001);
        assertTrue(after.x > before.x);
        assertEquals(1.62, after.y, 1.0e-9);
    }

    @Test
    void doesNotJumpToANearbyReturningPathSegment() {
        WalkingAimSpline spline = new WalkingAimSpline(List.of(Vec3.ZERO, new Vec3(0, 0, 8),
                new Vec3(1, 0, 8), new Vec3(1, 0, 0)));
        Vec3 aim = spline.aimPoint(new Vec3(0.9, 0, 2), 0, 2.0, 1.62);
        assertPoint(new Vec3(0, 1.62, 4), aim);
    }

    @Test
    void remainsContinuousWhenPursuitAdvancesPastAnUnequalLengthCorner() {
        WalkingAimSpline spline = new WalkingAimSpline(List.of(Vec3.ZERO, new Vec3(0, 0, 8),
                new Vec3(1, 0, 8), new Vec3(1, 0, 12)));
        Vec3 before = spline.aimPoint(new Vec3(0, 0, 7.74), 0, 0.3, 1.62);
        Vec3 after = spline.aimPoint(new Vec3(0, 0, 7.76), 1, 0.3, 1.62);
        assertTrue(before.distanceTo(after) < 0.05, "Camera jumped when the movement waypoint advanced");
    }

    @Test
    void jumpHeightDoesNotPushAimForwardOnAnAscendingPath() {
        List<Vec3> path = List.of(Vec3.ZERO, new Vec3(0, 2, 6));
        Vec3 grounded = new WalkingAimSpline(path).aimPoint(new Vec3(0, 1, 3), 0, 1.5, 1.62);
        Vec3 jumping = new WalkingAimSpline(path).aimPoint(new Vec3(0, 2.2, 3), 0, 1.5, 1.62);
        assertPoint(grounded, jumping);
        assertEquals(1.62 + grounded.z / 3.0, grounded.y, 1.0e-9);
    }

    @Test
    void preservesVerticalSegmentsAndUsesFeetHeightForTheirProgress() {
        WalkingAimSpline spline = new WalkingAimSpline(List.of(Vec3.ZERO, new Vec3(0, 4, 0)));
        assertPoint(new Vec3(0, 4.12, 0), spline.aimPoint(new Vec3(0, 1, 0), 0, 1.5, 1.62));
        assertPoint(new Vec3(0, 5.62, 0), spline.aimPoint(new Vec3(0, 3, 0), 0, 1.5, 1.62));
    }

    @Test
    void aimDoesNotMoveBackwardWhenRecoveryBacksUp() {
        WalkingAimSpline spline = new WalkingAimSpline(List.of(Vec3.ZERO, new Vec3(0, 0, 10)));
        Vec3 forward = spline.aimPoint(new Vec3(0, 0, 4), 0, 2, 1.62);
        Vec3 backup = spline.aimPoint(new Vec3(0, 0, 3.5), 0, 2, 1.62);
        assertPoint(forward, backup);
    }

    @Test
    void leavesPassedSplinePointsBehindOnANearbyReturningLeg() {
        WalkingAimSpline spline = new WalkingAimSpline(List.of(Vec3.ZERO,
                new Vec3(0, 0, 10), new Vec3(0.2, 0, 0)));
        spline.aimPoint(new Vec3(0, 0, 8), 0, 1, 1.62);

        Vec3 aim = spline.aimPoint(new Vec3(0.05, 0, 6), 1, 1, 1.62);

        assertTrue(aim.z < 6, "Aim remained on the already passed incoming leg");
        assertTrue(aim.x > 0.05);
        Vec3 next = spline.aimPoint(new Vec3(0.05, 0, 5), 1, 1, 1.62);
        assertTrue(next.z < aim.z);
    }

    @Test
    void staysOnTheReturningLegWhenItOverlapsAnEarlierLeg() {
        WalkingAimSpline spline = new WalkingAimSpline(List.of(Vec3.ZERO,
                new Vec3(0, 0, 20), Vec3.ZERO));
        spline.aimPoint(new Vec3(0, 0, 18), 0, 0.5, 1.62);

        assertPoint(new Vec3(0, 1.62, 9.5),
                spline.aimPoint(new Vec3(0, 0, 10), 1, 0.5, 1.62));
    }

    @Test
    void followsAFallBelowTheRoundedLedgeInsteadOfLookingBackUp() {
        WalkingAimSpline spline = new WalkingAimSpline(List.of(new Vec3(0, 20, 0), new Vec3(1, 20, 0),
                new Vec3(1, 0, 0), new Vec3(5, 0, 0)));
        Vec3 feet = new Vec3(1, 8, 0);
        Vec3 aim = spline.aimPoint(feet, 1, 2, 1.62);
        assertPoint(new Vec3(1, 7.62, 0), aim);
    }

    @Test
    void followsHeightProgressOnSteepSlopedDrops() {
        WalkingAimSpline spline = new WalkingAimSpline(List.of(new Vec3(0, 20, 0), new Vec3(1, 0, 0)));
        Vec3 first = spline.aimPoint(new Vec3(0.2, 12, 0), 0, 2, 1.62);
        Vec3 second = spline.aimPoint(new Vec3(0.2, 6, 0), 0, 2, 1.62);
        assertTrue(first.y < 13.62);
        assertTrue(second.y < 7.62);
        assertTrue(second.y < first.y - 5);
    }

    @Test
    void projectsThroughoutAVerticalFallStackWhileMovementCorrectsLateralDrift() {
        List<Vec3> points = IntStream.rangeClosed(0, 40)
                .mapToObj(i -> new Vec3(0.5, 40 - i, 0.5)).toList();
        WalkingAimSpline spline = new WalkingAimSpline(points);
        Vec3 first = spline.aimPoint(new Vec3(1.3, 25, 0.5), 0, 2, 1.62);
        Vec3 second = spline.aimPoint(new Vec3(1.1, 10, 0.5), 0, 2, 1.62);
        assertPoint(new Vec3(0.5, 24.62, 0.5), first);
        assertPoint(new Vec3(0.5, 9.62, 0.5), second);
    }

    @Test
    void followsAFullVerticalFallAndTurnsAlongTheLanding() {
        List<Vec3> points = new ArrayList<>();
        points.add(new Vec3(-0.5, 40, 0.5));
        for (int height = 40; height >= 0; height--) points.add(new Vec3(0.5, height, 0.5));
        points.add(new Vec3(5.5, 0, 0.5));
        WalkingRoute route = new WalkingRoute(points);
        WalkingAimSpline spline = new WalkingAimSpline(points);
        int segment = 0;
        for (double height = 38; height > 2; height -= 3.1) {
            Vec3 feet = new Vec3(0.95, height, 0.8);
            segment = route.advance(feet, segment);
            Vec3 aim = spline.aimPoint(feet, segment, 2, 1.62);
            assertTrue(aim.y < feet.y + 1.62, "Aim remained above the falling player at " + feet);
            assertTrue(aim.y > feet.y - 1);
        }
        Vec3 feet = new Vec3(0.95, 0, 0.8);
        segment = route.advance(feet, segment);
        Vec3 aim = spline.aimPoint(feet, segment, 2, 1.62);
        assertTrue(aim.x > feet.x);
        assertEquals(1.62, aim.y, 1.0e-6);
    }

    @Test
    void doesNotProjectPastALandingIntoAnotherDrop() {
        WalkingAimSpline spline = new WalkingAimSpline(List.of(new Vec3(0, 30, 0), new Vec3(0, 25, 0),
                new Vec3(0, 20, 0), new Vec3(5, 20, 0), new Vec3(5, 0, 0)));
        Vec3 aim = spline.aimPoint(new Vec3(0.5, 5, 0), 0, 2, 1.62);
        assertTrue(aim.x <= 3.0 + 1.0e-6, "Aim projected beyond the first landing: " + aim);
        assertEquals(21.62, aim.y, 1.0e-6);
    }

    @Test
    void clampsAtTheEndpointAndHandlesDegeneratePaths() {
        Vec3 end = new Vec3(2, 3, 4);
        WalkingAimSpline single = new WalkingAimSpline(List.of(end));
        assertPoint(end.add(0, 1.62, 0), single.aimPoint(Vec3.ZERO, 0, 8, 1.62));
        WalkingAimSpline duplicates = new WalkingAimSpline(List.of(end, end, end));
        assertPoint(end.add(0, 1.62, 0), duplicates.aimPoint(end, 1, 8, 1.62));
        WalkingAimSpline empty = new WalkingAimSpline(List.of());
        assertPoint(end.add(0, 1.62, 0), empty.aimPoint(end, 0, 8, 1.62));
        assertTrue(empty.points(1.62).isEmpty());
    }

    @Test
    void splineRemainsWithinPathBoundsThroughElevationChanges() {
        WalkingAimSpline spline = new WalkingAimSpline(List.of(Vec3.ZERO, new Vec3(0, 1, 4),
                new Vec3(4, 2, 4), new Vec3(4, 3, 0)));
        double previousHeight = 0.0;
        for (Vec3 point : spline.points(0.0)) {
            assertTrue(point.x >= 0.0 && point.x <= 4.0 + 1.0e-9);
            assertTrue(point.z >= 0.0 && point.z <= 4.0 + 1.0e-9);
            assertTrue(point.y >= previousHeight - 1.0e-9);
            previousHeight = point.y;
        }
        assertEquals(3.0, previousHeight, 1.0e-9);
    }

    private static void assertPoint(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x, actual.x, 1.0e-6);
        assertEquals(expected.y, actual.y, 1.0e-6);
        assertEquals(expected.z, actual.z, 1.0e-6);
    }
}
