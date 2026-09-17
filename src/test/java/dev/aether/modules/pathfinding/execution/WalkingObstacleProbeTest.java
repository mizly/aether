package dev.aether.modules.pathfinding.execution;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class WalkingObstacleProbeTest {
    private static final AABB PLAYER = new AABB(-0.3, 0, -0.3, 0.3, 1.8, 0.3);
    private static final Vec3 FORWARD = new Vec3(0, 0, 1);

    @Test
    void detectsFullBlocksBeforeHorizontalCollision() {
        var result = probe(Vec3.ZERO, new AABB(-0.5, 0, 0.9, 0.5, 1, 1.9));
        assertTrue(result.jumpRequired());
        assertEquals(0.6, result.clearance(), 0.001);
        assertEquals(1.0, result.obstacleHeight());
    }

    @Test
    void projectsMovementSpeedToFireEarlier() {
        AABB obstacle = new AABB(-0.5, 0, 1.5, 0.5, 1, 2.5);
        assertFalse(probe(Vec3.ZERO, obstacle).obstacleAhead());
        assertTrue(probe(new Vec3(0, 0, 0.35), obstacle).jumpRequired());
        assertFalse(probe(new Vec3(0, 0, -0.35), obstacle).obstacleAhead());
        assertFalse(probe(new Vec3(0.35, 0, 0), obstacle).obstacleAhead());
    }

    @Test
    void probesPlayerEdgesWhenTheCenterRayMisses() {
        var result = probe(Vec3.ZERO, new AABB(0.2, 0, 0.8, 1.2, 1, 1.8));
        assertTrue(result.jumpRequired());
    }

    @Test
    void probesWorldMovementDirectionIndependentlyOfViewYaw() {
        var world = world(new AABB(0.8, 0, -0.5, 1.8, 1, 0.5));
        assertTrue(WalkingObstacleProbe.probe(world, PLAYER, new Vec3(1, 0, 0),
                Vec3.ZERO, 0.6, 1.2, 0.75, 2).jumpRequired());
        assertFalse(WalkingObstacleProbe.probe(world, PLAYER, FORWARD,
                Vec3.ZERO, 0.6, 1.2, 0.75, 2).obstacleAhead());
    }

    @Test
    void walksSlabsAndTheFirstStairTread() {
        assertFalse(probe(Vec3.ZERO, new AABB(-0.5, 0, 0.7, 0.5, 0.5, 1.7)).obstacleAhead());
        assertFalse(probe(Vec3.ZERO,
                new AABB(-0.5, 0, 0.6, 0.5, 0.5, 1.6),
                new AABB(-0.5, 0.5, 1.1, 0.5, 1, 1.6)).obstacleAhead());
    }

    @Test
    void respectsActualStepHeight() {
        var world = world(new AABB(-0.5, 0, 0.7, 0.5, 0.5, 1.7));
        assertTrue(WalkingObstacleProbe.probe(world, PLAYER, FORWARD,
                Vec3.ZERO, 0.3, 1.2, 0.75, 2).jumpRequired());
    }

    @Test
    void detectsStackedSlabsAsOneObstacle() {
        var result = probe(Vec3.ZERO,
                new AABB(-0.5, 0, 0.7, 0.5, 0.5, 1.7),
                new AABB(-0.5, 0.5, 0.7, 0.5, 1, 1.7));
        assertTrue(result.jumpRequired());
        assertEquals(1.0, result.obstacleHeight());
    }

    @Test
    void detectsObstaclesBeginningAboveTheFeet() {
        var result = probe(Vec3.ZERO, new AABB(-0.5, 0.5, 0.7, 0.5, 1, 1.7));
        assertTrue(result.jumpRequired());
        assertEquals(1.0, result.obstacleHeight());
    }

    @Test
    void detectsTheMiddleOfThePlayerFootprint() {
        var result = probe(Vec3.ZERO, new AABB(0.08, 0, 0.7, 0.18, 1, 1.7));
        assertTrue(result.jumpRequired());
    }

    @Test
    void detectsATallObstacleBehindAWalkableSlab() {
        var result = probe(Vec3.ZERO,
                new AABB(-0.5, 0, 0.6, 0.5, 0.5, 1.6),
                new AABB(-0.5, 0.5, 1, 0.5, 1.5, 2));
        assertTrue(result.obstacleAhead());
        assertFalse(result.jumpRequired());
        assertEquals(1.5, result.obstacleHeight());
    }

    @Test
    void walksSuccessiveStairTreadsWithinThePredictedMovement() {
        var result = probe(new Vec3(0, 0, 0.4),
                new AABB(-0.5, 0, 0.6, 0.5, 0.5, 1.6),
                new AABB(-0.5, 0.5, 1.1, 0.5, 1, 2.1),
                new AABB(-0.5, 1, 1.6, 0.5, 1.5, 2.6));
        assertFalse(result.obstacleAhead());
    }

    @Test
    void detectsBlocksAlreadyTouchingThePlayer() {
        var result = probe(Vec3.ZERO, new AABB(-0.5, 0, 0.3, 0.5, 1, 1.3));
        assertTrue(result.jumpRequired());
        assertEquals(0, result.clearance(), 0.001);
    }

    @Test
    void doesNotCarryStepHeightAcrossAnUnsupportedGap() {
        var result = probe(new Vec3(0, 0, 0.4),
                new AABB(-0.5, 0, 0.4, 0.5, 0.5, 0.5),
                new AABB(-0.5, 0, 1.2, 0.5, 1, 2.2));
        assertTrue(result.jumpRequired());
        assertEquals(1.0, result.obstacleHeight());
    }

    @Test
    void excludesParallelWallsThatOnlyTouchThePlayer() {
        var result = probe(Vec3.ZERO, new AABB(0.3, 0, -0.5, 1.3, 2, 2));
        assertFalse(result.obstacleAhead());
    }

    @Test
    void sweepsDiagonallyInBothDirections() {
        var northeast = world(new AABB(0.7, 0, 0.7, 1.7, 1, 1.7));
        var southwest = world(new AABB(-1.7, 0, -1.7, -0.7, 1, -0.7));
        assertTrue(WalkingObstacleProbe.probe(northeast, PLAYER, new Vec3(1, 0, 1),
                Vec3.ZERO, 0.6, 1.2, 0.75, 2).jumpRequired());
        assertTrue(WalkingObstacleProbe.probe(southwest, PLAYER, new Vec3(-1, 0, -1),
                Vec3.ZERO, 0.6, 1.2, 0.75, 2).jumpRequired());
    }

    @Test
    void rejectsSteppingIntoALowCeiling() {
        var result = probe(Vec3.ZERO,
                new AABB(-0.5, 0, 0.6, 0.5, 0.5, 1.6),
                new AABB(-0.5, 2, 0.6, 0.5, 3, 1.6));
        assertTrue(result.obstacleAhead());
        assertFalse(result.headroomClear());
        assertFalse(result.jumpRequired());
    }

    @Test
    void rejectsFencesTallWallsAndLowCeilings() {
        assertFalse(probe(Vec3.ZERO, new AABB(-0.5, 0, 0.7, 0.5, 1.5, 1.7)).jumpRequired());
        assertFalse(probe(Vec3.ZERO, new AABB(-0.5, 0, 0.7, 0.5, 2, 1.7)).jumpRequired());
        var ceiling = probe(Vec3.ZERO,
                new AABB(-0.5, 0, 0.7, 0.5, 1, 1.7),
                new AABB(-1, 2, -1, 1, 3, 2));
        assertTrue(ceiling.obstacleAhead());
        assertFalse(ceiling.headroomClear());
        assertFalse(ceiling.jumpRequired());
    }

    @Test
    void checksHeadroomAboveTheLandingEdge() {
        var result = probe(Vec3.ZERO,
                new AABB(-0.5, 0, 0.7, 0.5, 1, 1.7),
                new AABB(-0.5, 2, 0.7, 0.5, 3, 1.7));
        assertFalse(result.jumpRequired());
        assertFalse(result.headroomClear());
    }

    @Test
    void checksFractionalFeetHeightAndBoostedJumpLimits() {
        var world = world(new AABB(-0.5, 0.5, 0.7, 0.5, 1.5, 1.7));
        var result = WalkingObstacleProbe.probe(world, PLAYER.move(0, 0.5, 0), FORWARD,
                Vec3.ZERO, 0.6, 1.2, 0.75, 2);
        assertTrue(result.jumpRequired());
        assertEquals(1.0, result.obstacleHeight());
        var highWorld = world(new AABB(-0.5, 0, 0.7, 0.5, 2, 1.7));
        assertFalse(WalkingObstacleProbe.probe(highWorld, PLAYER, FORWARD,
                Vec3.ZERO, 0.6, 1.2, 0.75, 2).jumpRequired());
        assertTrue(WalkingObstacleProbe.probe(highWorld, PLAYER, FORWARD,
                Vec3.ZERO, 0.6, 2.2, 0.75, 2).jumpRequired());
    }

    @Test
    void jumpsFromCarpetOntoACounterBetweenPostsAndFlowerpots() {
        var world = world(
                new AABB(-2, 0, -1, 2, 0.0625, 0.6),
                new AABB(-1.5, 0, 0.6, 1.5, 1, 1.6),
                new AABB(-2.5, 0, 0.6, -1.5, 4, 1.6),
                new AABB(1.5, 0, 0.6, 2.5, 4, 1.6),
                new AABB(-1.1875, 1, 0.9125, -0.8125, 1.375, 1.2875),
                new AABB(0.8125, 1, 0.9125, 1.1875, 1.375, 1.2875),
                new AABB(-2.5, 3, -1, 2.5, 4, 1.6));
        var result = WalkingObstacleProbe.probe(world, PLAYER.move(0, 0.0625, 0), FORWARD,
                Vec3.ZERO, 0.6, 1.125, 0.35, 2);
        assertTrue(result.obstacleAhead());
        assertTrue(result.jumpRequired());
        assertTrue(result.headroomClear());
        assertEquals(0.9375, result.obstacleHeight());
    }

    @Test
    void rejectsACarpetToCounterJumpBlockedByALowBeam() {
        var world = world(
                new AABB(-2, 0, -1, 2, 0.0625, 0.6),
                new AABB(-1.5, 0, 0.6, 1.5, 1, 1.6),
                new AABB(-2.5, 2.5, -1, 2.5, 3.5, 1.6));
        var result = WalkingObstacleProbe.probe(world, PLAYER.move(0, 0.0625, 0), FORWARD,
                Vec3.ZERO, 0.6, 1.125, 0.35, 2);
        assertTrue(result.obstacleAhead());
        assertFalse(result.jumpRequired());
        assertFalse(result.headroomClear());
    }

    @Test
    void calculatesVanillaAndBoostedJumpApex() {
        assertEquals(1.2522, WalkingObstacleProbe.jumpHeight(0.42, 0.08), 0.0001);
        assertTrue(WalkingObstacleProbe.jumpHeight(0.62, 0.08) > 2.0);
        assertTrue(WalkingObstacleProbe.jumpHeight(0.21, 0.08) < 0.6);
    }

    @Test
    void doesNotJumpWithoutMovement() {
        assertFalse(WalkingObstacleProbe.probe(world(new AABB(-0.5, 0, 0.7, 0.5, 1, 1.7)),
                PLAYER, Vec3.ZERO, Vec3.ZERO, 0.6, 1.2, 0.75, 2).jumpRequired());
    }

    private static WalkingObstacleProbe.Result probe(Vec3 velocity, AABB... obstacles) {
        return WalkingObstacleProbe.probe(world(obstacles), PLAYER, FORWARD, velocity,
                0.6, 1.2, 0.75, 2);
    }

    private static WalkingObstacleProbe.CollisionSpace world(AABB... obstacles) {
        return new WalkingObstacleProbe.CollisionSpace() {
            @Override
            public Iterable<AABB> collisions(AABB bounds) {
                return Arrays.stream(obstacles).filter(bounds::intersects).toList();
            }

            @Override
            public boolean clear(AABB bounds) {
                return Arrays.stream(obstacles).noneMatch(bounds::intersects);
            }
        };
    }
}
