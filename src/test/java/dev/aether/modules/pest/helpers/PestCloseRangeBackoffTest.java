package dev.aether.modules.pest.helpers;

import dev.aether.modules.pathfinding.execution.FlightMotion;
import dev.aether.util.GardenPlots;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PestCloseRangeBackoffTest {
    private static final double PLAYER_EYE = 1.62;
    private static final double BAT_EYE = 0.45;
    private static final double SILVERFISH_EYE = 0.13;
    private static final double FLY_SPEED = 0.05;
    private static final FlightMotion.Input BACK = new FlightMotion.Input(-1, 0);
    private static final FlightMotion.Input BACK_LEFT = new FlightMotion.Input(-1, -1);
    private static final FlightMotion.Input BACK_RIGHT = new FlightMotion.Input(-1, 1);

    @Test
    void theHoverDropComesFromTheRealEyeHeights() {
        assertEquals(4.17, PestCloseRangeBackoff.hoverDrop(PLAYER_EYE, BAT_EYE), 1e-9);
        assertEquals(4.49, PestCloseRangeBackoff.hoverDrop(PLAYER_EYE, SILVERFISH_EYE), 1e-9);
    }

    @Test
    void aLevelLandingJustShortStillBacksUpForTheHover() {
        double hover = PestCloseRangeBackoff.hoverDrop(PLAYER_EYE, BAT_EYE);
        double comfort = PestCloseRangeBackoff.comfortDegrees(55, 25, 70);
        assertEquals(54.0, comfort, 1e-9);

        double required = PestCloseRangeBackoff.requiredHorizontal(0.0, hover, comfort, 5.0);

        assertEquals(4.17 / Math.tan(Math.toRadians(49)), required, 1e-9);
        assertEquals(required, PestCloseRangeBackoff.requiredHorizontal(-1.0, hover, comfort, 5.0), 1e-9);
        assertEquals(required, PestCloseRangeBackoff.requiredHorizontal(2.0, hover, comfort, 5.0), 1e-9);
        assertTrue(PestCloseRangeBackoff.worthBackingUp(required, 1.0));
        assertFalse(PestCloseRangeBackoff.worthBackingUp(required, required - 0.7));
    }

    @Test
    void theSettledHoverKeepsThePestOnScreenAtAnySetting() {
        for (int bucket = 10; bucket <= 40; bucket += 5) {
            for (double fov : new double[]{50, 70, 90}) {
                for (double slider : new double[]{30, 55, 80}) {
                    for (double pestEye : new double[]{SILVERFISH_EYE, BAT_EYE}) {
                        double hover = PestCloseRangeBackoff.hoverDrop(PLAYER_EYE, pestEye);
                        double comfort = PestCloseRangeBackoff.comfortDegrees(slider, bucket, fov);
                        double required = PestCloseRangeBackoff.requiredHorizontal(
                                0.0, hover, comfort, Double.MAX_VALUE);
                        double depression = Math.toDegrees(Math.atan2(hover, required));

                        assertTrue(depression - bucket <= fov / 2.0 - 6.0 + 1e-6,
                                bucket + " " + fov + " " + slider + " " + pestEye + " -> " + depression);
                    }
                }
            }
        }
    }

    @Test
    void aShallowComfortAngleStillLeavesFifteenDegrees() {
        assertEquals(4.17 / Math.tan(Math.toRadians(15)),
                PestCloseRangeBackoff.requiredHorizontal(0.0, 4.17, 10.0, Double.MAX_VALUE), 1e-9);
    }

    @Test
    void theBackUpStopsAtTheCap() {
        assertEquals(2.65, PestCloseRangeBackoff.requiredHorizontal(0.0, 4.17, 54.0, 2.65), 1e-9);
        assertEquals(5.0, PestCloseRangeBackoff.requiredHorizontal(-8.0, 4.17, 54.0, 5.0), 1e-9);
    }

    @Test
    void straightBackIsTheSKeyAtEveryHeading() {
        for (float yaw : new float[]{0f, 37.5f, 90f, 179.9f, -179.9f, 359f, 1234.5f}) {
            double radians = Math.toRadians(yaw);
            Vec3 look = new Vec3(-Math.sin(radians), 0.0, Math.cos(radians));

            PestCloseRangeBackoff.Retreat retreat = PestCloseRangeBackoff.chooseRetreat(
                    Vec3.ZERO, look.add(0, -3, 0), yaw, 3.6, null, (start, end) -> true);

            assertNotNull(retreat, "yaw " + yaw);
            assertEquals(BACK, retreat.keys(), "yaw " + yaw);
            assertEquals(-1.0, retreat.direction().dot(look), 1e-6, "yaw " + yaw);
            assertEquals(BACK, FlightMotion.horizontalInput(retreat.direction().scale(0.3), Vec3.ZERO, yaw),
                    "yaw " + yaw);
        }
    }

    @Test
    void straightBackComesFirstAndTheDiagonalsAfter() {
        assertEquals(List.of(BACK, BACK_LEFT, BACK_RIGHT), PestCloseRangeBackoff.RETREAT_KEYS);
    }

    @Test
    void aPestStraightBelowIsStableUnderJitter() {
        for (float yaw : new float[]{0f, 90f, -135f}) {
            Vec3 direction = FlightMotion.acceleration(BACK, yaw, 1.0);
            assertEquals(3.6, PestCloseRangeBackoff.travelFor(new Vec3(0, -4, 0), direction, 3.6), 1e-6);
            for (double dx : new double[]{-0.05, 0.05}) {
                for (double dz : new double[]{-0.05, 0.05}) {
                    Vec3 offset = new Vec3(dx, -4, dz);

                    assertFalse(PestCloseRangeBackoff.isBehind(offset, yaw), yaw + " " + offset);
                    assertEquals(3.6, PestCloseRangeBackoff.travelFor(offset, direction, 3.6), 0.08,
                            yaw + " " + offset);
                }
            }
        }
    }

    @Test
    void aPestBehindIsATurnNotABackUp() {
        assertTrue(PestCloseRangeBackoff.isBehind(new Vec3(0, -3, -1.5), 0f));
        assertFalse(PestCloseRangeBackoff.isBehind(new Vec3(0, -3, -0.4), 0f));
        assertFalse(PestCloseRangeBackoff.isBehind(new Vec3(1.5, -3, 0), 0f));
        assertTrue(PestCloseRangeBackoff.isBehind(new Vec3(1.5, -3, 0), 90f));
    }

    @Test
    void theTravelLeavesThePestAtTheRequiredDistance() {
        Vec3 direction = FlightMotion.acceleration(BACK, 0f, 1.0);
        for (Vec3 offset : List.of(new Vec3(0, -3, 1), new Vec3(1.5, -3, 0), new Vec3(0.4, 2, 0.3), Vec3.ZERO)) {
            double travel = PestCloseRangeBackoff.travelFor(offset, direction, 3.6);

            assertEquals(3.6, offset.subtract(direction.scale(travel)).horizontalDistance(), 1e-9, offset.toString());
        }
        assertEquals(2.6, PestCloseRangeBackoff.travelFor(new Vec3(0, -3, 1), direction, 3.6), 1e-9);
        assertEquals(Math.sqrt(3.6 * 3.6 - 1.5 * 1.5),
                PestCloseRangeBackoff.travelFor(new Vec3(1.5, -3, 0), direction, 3.6), 1e-9);
    }

    @Test
    void theWayBackIsStraightWhenItIsClear() {
        Vec3 from = new Vec3(0, 70, 0);
        List<Vec3> asked = new ArrayList<>();

        PestCloseRangeBackoff.Retreat retreat = PestCloseRangeBackoff.chooseRetreat(
                from, new Vec3(0, -3, 1), 0f, 3.6, GardenPlots.boundsForGrid(0, 0),
                (start, end) -> asked.add(end));

        assertNotNull(retreat);
        assertEquals(BACK, retreat.keys());
        assertEquals(2.6, retreat.travel(), 1e-9);
        // the corridor runs on past the stop to cover the coast after the brake
        assertEquals(1, asked.size());
        assertEquals(0.0, asked.getFirst().distanceTo(new Vec3(0, 70, -3.35)), 1e-9);
    }

    @Test
    void aBlockedWayBackFallsBackToTheDiagonalsInOrder() {
        Vec3 from = new Vec3(0, 70, 0);
        Vec3 offset = new Vec3(0, -3, 1);

        PestCloseRangeBackoff.Retreat left = PestCloseRangeBackoff.chooseRetreat(
                from, offset, 0f, 3.6, null, (start, end) -> Math.abs(end.x) > 0.5);
        PestCloseRangeBackoff.Retreat right = PestCloseRangeBackoff.chooseRetreat(
                from, offset, 0f, 3.6, null, (start, end) -> end.x < -0.5);
        PestCloseRangeBackoff.Retreat none = PestCloseRangeBackoff.chooseRetreat(
                from, offset, 0f, 3.6, null, (start, end) -> false);

        assertNotNull(left);
        assertEquals(BACK_LEFT, left.keys());
        assertEquals(3.6, offset.subtract(left.direction().scale(left.travel())).horizontalDistance(), 1e-6);
        assertNotNull(right);
        assertEquals(BACK_RIGHT, right.keys());
        assertNull(none);
    }

    @Test
    void theWayBackStaysAWholeBlockInsideThePlot() {
        GardenPlots.Bounds plot = GardenPlots.boundsForGrid(0, 0);
        Vec3 from = new Vec3(45, 70, 0);
        float yaw = 45f;
        double radians = Math.toRadians(yaw);
        Vec3 offset = new Vec3(-Math.sin(radians), -3, Math.cos(radians));
        List<Vec3> asked = new ArrayList<>();

        PestCloseRangeBackoff.Retreat retreat = PestCloseRangeBackoff.chooseRetreat(
                from, offset, yaw, 3.6, plot, (start, end) -> asked.add(end));

        assertNotNull(retreat);
        assertEquals(BACK_RIGHT, retreat.keys());
        assertEquals(1, asked.size());
        assertTrue(plot.contains(asked.getFirst().x, asked.getFirst().z, -1.0));
        assertNull(PestCloseRangeBackoff.chooseRetreat(
                new Vec3(46.5, 70, -46.5), offset, yaw, 3.6, plot, (start, end) -> true));
    }

    @Test
    void itHoldsBackThenBrakesNearThePlan() {
        for (double travel : new double[]{0.75, 1, 1.5, 2.5, 3.5, 4.5, 5}) {
            Vec3 direction = FlightMotion.acceleration(BACK, 0f, 1.0);
            PestCloseRangeBackoff backoff = new PestCloseRangeBackoff();
            backoff.begin(7, Vec3.ZERO, new PestCloseRangeBackoff.Retreat(BACK, direction, travel), 99.0, 0, 0);
            double position = 0.0;
            double speed = 0.0;
            StringBuilder keys = new StringBuilder();
            int ticks = 0;
            for (int tick = 1; ; tick++) {
                FlightMotion.Input input = backoff.step(tick, direction.scale(position), direction.scale(speed),
                        0.0, false, FLY_SPEED);
                if (input == null) {
                    break;
                }
                keys.append(keyName(input));
                double moved = speed - input.forward() * FLY_SPEED;
                position += moved;
                speed = moved * 0.91;
                ticks++;
            }
            for (int coast = 0; coast < 100; coast++) {
                position += speed;
                speed *= 0.91;
            }

            assertEquals(travel, position, 0.5, travel + " " + keys);
            assertTrue(ticks <= 30, travel + " took " + ticks);
            assertTrue(keys.toString().matches("S+W*"), travel + " " + keys);
        }
    }

    @Test
    void itWaitsAMomentBeforePullingBack() {
        PestCloseRangeBackoff backoff = new PestCloseRangeBackoff();
        Vec3 direction = FlightMotion.acceleration(BACK, 0f, 1.0);
        backoff.begin(7, Vec3.ZERO, new PestCloseRangeBackoff.Retreat(BACK, direction, 3.0), 3.6, 100, 3);

        assertEquals(PestCloseRangeBackoff.Phase.REACT, backoff.phase());
        assertEquals(PestCloseRangeBackoff.RELEASED, backoff.step(101, Vec3.ZERO, Vec3.ZERO, 0.5, false, FLY_SPEED));
        assertEquals(PestCloseRangeBackoff.RELEASED, backoff.step(102, Vec3.ZERO, Vec3.ZERO, 0.5, false, FLY_SPEED));
        assertEquals(BACK, backoff.step(103, Vec3.ZERO, Vec3.ZERO, 0.5, false, FLY_SPEED));
        assertEquals(PestCloseRangeBackoff.Phase.DRIVE, backoff.phase());
    }

    @Test
    void reactionsLastTwoToFiveTicks() {
        SplittableRandom random = new SplittableRandom(11);
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            int ticks = PestCloseRangeBackoff.reactTicks(random);
            assertTrue(ticks >= 2 && ticks <= 5, "ticks " + ticks);
            seen.add(ticks);
        }
        assertEquals(Set.of(2, 3, 4, 5), seen);
    }

    @Test
    void aWallOrTheDistanceReachedBrakesWithTheOppositeKeys() {
        Vec3 direction = FlightMotion.acceleration(BACK_LEFT, 0f, 1.0);
        PestCloseRangeBackoff.Retreat retreat = new PestCloseRangeBackoff.Retreat(BACK_LEFT, direction, 3.0);

        PestCloseRangeBackoff walled = new PestCloseRangeBackoff();
        walled.begin(7, Vec3.ZERO, retreat, 3.6, 0, 0);
        assertEquals(new FlightMotion.Input(1, 1),
                walled.step(1, Vec3.ZERO, direction.scale(0.2), 1.0, true, FLY_SPEED));
        assertEquals(PestCloseRangeBackoff.Phase.BRAKE, walled.phase());
        assertNull(walled.step(2, Vec3.ZERO, direction.scale(0.02), 1.0, false, FLY_SPEED));

        PestCloseRangeBackoff arrived = new PestCloseRangeBackoff();
        arrived.begin(7, Vec3.ZERO, retreat, 3.6, 0, 0);
        assertEquals(new FlightMotion.Input(1, 1),
                arrived.step(1, Vec3.ZERO, direction.scale(0.2), 3.6, false, FLY_SPEED));
        assertNull(arrived.step(2, Vec3.ZERO, Vec3.ZERO, 3.6, false, FLY_SPEED));
    }

    @Test
    void theBackUpGivesUpAfterFortyTicks() {
        PestCloseRangeBackoff backoff = new PestCloseRangeBackoff();
        Vec3 direction = FlightMotion.acceleration(BACK, 0f, 1.0);
        backoff.begin(7, Vec3.ZERO, new PestCloseRangeBackoff.Retreat(BACK, direction, 3.0), 3.6, 1000, 0);

        assertEquals(BACK, backoff.step(1040, Vec3.ZERO, Vec3.ZERO, 0.5, false, FLY_SPEED));
        assertNull(backoff.step(1041, Vec3.ZERO, Vec3.ZERO, 0.5, false, FLY_SPEED));
    }

    @Test
    void brakeDistanceGrowsWithSpeed() {
        assertEquals(0.0, PestCloseRangeBackoff.brakeDistance(0.0, FLY_SPEED), 1e-12);
        double fromThreeTenths = PestCloseRangeBackoff.brakeDistance(0.3, FLY_SPEED);
        assertTrue(fromThreeTenths > 0.55 && fromThreeTenths < 0.65, "brake " + fromThreeTenths);
        double previous = 0.0;
        for (int i = 0; i <= 500; i++) {
            double distance = PestCloseRangeBackoff.brakeDistance(i / 1000.0, FLY_SPEED);
            assertTrue(distance >= previous - 1e-12, "speed " + i / 1000.0);
            previous = distance;
        }
    }

    @Test
    void theCameraSettlesOnTheHoverPitchOverThePestAndLooksUpFromBelow() {
        assertEquals(32f, PestCloseRangeBackoff.settledPitch(-3.0, 3.6, 32f));
        assertEquals(-Math.toDegrees(Math.atan2(3.0, 3.6)), PestCloseRangeBackoff.settledPitch(3.0, 3.6, 32f), 1e-4);
    }

    @Test
    void anArrivalWaitsForATurnButNotForever() {
        PestCloseRangeBackoff backoff = new PestCloseRangeBackoff();

        assertFalse(backoff.armExpired(100));
        assertFalse(backoff.armExpired(130));
        assertTrue(backoff.armExpired(131));
        backoff.holdArm(140);
        assertFalse(backoff.armExpired(170));
        assertTrue(backoff.armExpired(171));

        assertTrue(backoff.requestTurn());
        assertFalse(backoff.requestTurn());
        assertTrue(backoff.turnRequested());
        backoff.disarm();
        assertFalse(backoff.turnRequested());
        assertFalse(backoff.armExpired(500));
    }

    @Test
    void aBackUpCutShortHandsItsKeysOverOnce() {
        PestCloseRangeBackoff backoff = new PestCloseRangeBackoff();
        Vec3 direction = FlightMotion.acceleration(BACK, 0f, 1.0);
        PestCloseRangeBackoff.Retreat retreat = new PestCloseRangeBackoff.Retreat(BACK, direction, 3.0);
        backoff.begin(7, Vec3.ZERO, retreat, 3.6, 0, 3);

        assertTrue(backoff.isFor(7));
        assertFalse(backoff.isFor(8));
        assertFalse(backoff.takeAbandonedKeys());

        backoff.cancel();

        assertFalse(backoff.isActive());
        assertFalse(backoff.isFor(7));
        assertTrue(backoff.takeAbandonedKeys());
        assertFalse(backoff.takeAbandonedKeys());

        backoff.begin(7, Vec3.ZERO, retreat, 3.6, 0, 3);
        backoff.reset();

        assertFalse(backoff.holdsKeys());
        assertFalse(backoff.takeAbandonedKeys());
    }

    private static String keyName(FlightMotion.Input input) {
        if (input.forward() < 0) {
            return "S";
        }
        return input.forward() > 0 ? "W" : ".";
    }
}
