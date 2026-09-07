package dev.aether.modules.pathfinding.execution;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FlightMotionTest {
    @Test
    void brakesWhenTheCoastWouldPassRightThroughTheGoal() {
        assertTrue(FlightMotion.shouldCoast(new Vec3(0, 0, 4), new Vec3(0, 0, 0.8), 0.5, 2));
        assertFalse(FlightMotion.shouldCoast(new Vec3(0, 0, 4), new Vec3(0, 0, 0.1), 0.5, 2));
    }

    @Test
    void coastingAwayOrBesideTheGoalDoesNotCountAsArrival() {
        assertFalse(FlightMotion.shouldCoast(new Vec3(0, 0, 4), new Vec3(0, 0, -0.8), 0.5, 2));
        assertFalse(FlightMotion.shouldCoast(new Vec3(2, 0, 4), new Vec3(0, 0, 0.8), 0.5, 2));
        assertFalse(FlightMotion.shouldCoast(new Vec3(0, 0, 4), Vec3.ZERO, 0.5, 2));
    }

    @Test
    void brakesAgainstWorldMomentumEvenDuringAHalfTurn() {
        Vec3 momentum = new Vec3(0.3, 0, 0.8);
        assertEquals(new FlightMotion.Input(-1, -1), FlightMotion.horizontalInput(Vec3.ZERO, momentum, 0));
        assertEquals(new FlightMotion.Input(1, 1), FlightMotion.horizontalInput(Vec3.ZERO, momentum, 180));
        assertEquals(new FlightMotion.Input(1, -1), FlightMotion.horizontalInput(Vec3.ZERO, momentum, 90));
    }

    @Test
    void doesNotReverseForSmallResidualDrift() {
        assertEquals(new FlightMotion.Input(0, 0),
                FlightMotion.horizontalInput(Vec3.ZERO, new Vec3(0.03, 0, -0.04), 0));
    }

    @Test
    void matchesAMovingPestInsteadOfTreatingAllPlayerSpeedAsClosingSpeed() {
        Vec3 targetVelocity = new Vec3(0, 0, 0.2);
        Vec3 desired = FlightMotion.approachVelocity(new Vec3(0, 0, 8), targetVelocity, 5, 0.35, 2);
        assertTrue(desired.z > targetVelocity.z);
        assertTrue(desired.horizontalDistance() <= 0.35);
        assertEquals(targetVelocity,
                FlightMotion.approachVelocity(new Vec3(0, 0, 5), targetVelocity, 5, 0.35, 2));
    }

    @Test
    void extraLookaheadSlowsTheApproachEarlier() {
        Vec3 offset = new Vec3(0, 0, 8);
        assertTrue(FlightMotion.approachVelocity(offset, Vec3.ZERO, 5, 0.8, 6).z
                < FlightMotion.approachVelocity(offset, Vec3.ZERO, 5, 0.8, 0).z);
    }

    @Test
    void releasesAltitudeInputBeforeCoastingPastTheTargetHeight() {
        assertEquals(0, FlightMotion.verticalInput(1, 0.4, 0.5));
        assertEquals(0, FlightMotion.verticalInput(-1, -0.4, 0.5));
        assertEquals(1, FlightMotion.verticalInput(2, 0, 0.5));
        assertEquals(-1, FlightMotion.verticalInput(-2, 0, 0.5));
    }

    @Test
    void fastApproachesSettleInFrontOfThePestAcrossFlightSpeeds() {
        for (double acceleration : new double[]{0.05, 0.1, 0.2}) {
            for (double initialSpeed : new double[]{0.0, 0.8, 1.5}) {
                double player = 0;
                double speed = initialSpeed;
                double targetDistance = Math.max(12, 5 + initialSpeed * FlightMotion.coastTicks(2) + 2);
                double nearest = targetDistance;
                for (int tick = 0; tick < 200; tick++) {
                    Vec3 desired = FlightMotion.approachVelocity(new Vec3(0, 0, targetDistance - player),
                            Vec3.ZERO, 5, 0.35, 2);
                    int input = FlightMotion.horizontalInput(desired, new Vec3(0, 0, speed), 0).forward();
                    if (tick >= 180) {
                        assertEquals(0, input, "Kept alternating movement keys after arrival");
                    }
                    speed += input * acceleration;
                    player += speed;
                    speed *= 0.91;
                    nearest = Math.min(nearest, targetDistance - player);
                }
                assertTrue(nearest > 3.0, "Passed too close at acceleration " + acceleration + ", speed " + initialSpeed);
                assertTrue(targetDistance - player < 7.0, "Failed to reach vacuum range");
                assertTrue(Math.abs(speed) < 0.08, "Did not settle");
            }
        }
    }

    @Test
    void followsAPestMovingAwayWithoutLosingRange() {
        double player = 0;
        double pest = 10;
        double speed = 0.8;
        for (int tick = 0; tick < 200; tick++) {
            Vec3 desired = FlightMotion.approachVelocity(new Vec3(0, 0, pest - player),
                    new Vec3(0, 0, 0.12), 5, 0.35, 2);
            int input = FlightMotion.horizontalInput(desired, new Vec3(0, 0, speed), 0).forward();
            speed += input * 0.1;
            player += speed;
            speed *= 0.91;
            pest += 0.12;
            assertTrue(pest - player > 3);
        }
        assertTrue(pest - player < 7);
    }
}
