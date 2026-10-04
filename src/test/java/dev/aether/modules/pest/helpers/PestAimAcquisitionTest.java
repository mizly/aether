package dev.aether.modules.pest.helpers;

import dev.aether.modules.pest.helpers.PestAimAcquisition.Phase;
import dev.aether.modules.pest.helpers.PestAimAcquisition.Step;
import dev.aether.modules.rotation.HumanFlick;
import dev.aether.util.RotationUtils;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PestAimAcquisitionTest {
    private static final long NOW = 1_000_000L;

    @Test
    void aPestAlreadyNearTheCrosshairIsPickedUpAQuarterSooner() {
        SplittableRandom random = new SplittableRandom(3);

        assertEquals(200L, PestAimAcquisition.perceiveMs(random, 1.0, 200, 200, 90.0));
        assertEquals(200L, PestAimAcquisition.perceiveMs(random, 1.0, 200, 200, 25.5));
        assertEquals(150L, PestAimAcquisition.perceiveMs(random, 1.0, 200, 200, 25.0));
        assertEquals(150L, PestAimAcquisition.perceiveMs(random, 1.0, 200, 200, 3.0));
        assertEquals(230L, PestAimAcquisition.perceiveMs(random, 1.15, 200, 200, 40.0));
    }

    @Test
    void reactionsStayInsideTheConfiguredRangeWhicheverWayRoundItIs() {
        SplittableRandom random = new SplittableRandom(5);
        for (int i = 0; i < 500; i++) {
            long reaction = PestAimAcquisition.perceiveMs(random, 1.0, 150, 320, 180.0);
            assertTrue(reaction >= 150L && reaction <= 320L, "reaction " + reaction);
            long swapped = PestAimAcquisition.perceiveMs(random, 1.0, 320, 150, 180.0);
            assertTrue(swapped >= 150L && swapped <= 320L, "swapped " + swapped);
        }
        assertEquals(0L, PestAimAcquisition.perceiveMs(random, 1.1, 0, 0, 180.0));
    }

    @Test
    void theLookAfterAFlickIsShortAndKeepsTheRunsPace() {
        SplittableRandom random = new SplittableRandom(9);
        for (double tempo : new double[]{0.90, 1.0, 1.15}) {
            for (int i = 0; i < 500; i++) {
                long react = PestAimAcquisition.reactMs(random, tempo);
                assertTrue(react >= Math.floor(20.0 * tempo) && react <= Math.ceil(60.0 * tempo),
                        tempo + " -> " + react);
            }
        }
    }

    @Test
    void onlyATurnWorthAFlickAndClearOfThePolesGetsOne() {
        assertTrue(PestAimAcquisition.wantsFlick(10.5, 10.0, 30.0f, 5.0));
        assertFalse(PestAimAcquisition.wantsFlick(10.0, 10.0, 30.0f, 5.0));
        assertTrue(PestAimAcquisition.wantsFlick(5.0, 4.0, 30.0f, 5.0));
        assertTrue(PestAimAcquisition.wantsFlick(120.0, 10.0, 75.0f, 5.0));
        assertFalse(PestAimAcquisition.wantsFlick(120.0, 10.0, 76.0f, 5.0));
        assertFalse(PestAimAcquisition.wantsFlick(120.0, 10.0, -76.0f, 5.0));
        assertTrue(PestAimAcquisition.wantsFlick(120.0, 10.0, 30.0f, 1.2));
        assertFalse(PestAimAcquisition.wantsFlick(120.0, 10.0, 30.0f, 1.1));
    }

    @Test
    void aMissIsFollowedUpOnlyPastTenDegreesAndAtMostTwice() {
        assertTrue(PestAimAcquisition.needsFollowUp(10.5, 0));
        assertTrue(PestAimAcquisition.needsFollowUp(30.0, 1));
        assertFalse(PestAimAcquisition.needsFollowUp(30.0, 2));
        assertFalse(PestAimAcquisition.needsFollowUp(10.0, 0));
        assertFalse(PestAimAcquisition.needsFollowUp(4.5, 0));
    }

    @Test
    void thePhasesAdvanceOnTheClockAndOnTheFlick() {
        assertEquals(Step.HOLD, PestAimAcquisition.step(Phase.PERCEIVE, NOW + 199, NOW, NOW + 200, false));
        assertEquals(Step.DECIDE, PestAimAcquisition.step(Phase.PERCEIVE, NOW + 200, NOW, NOW + 200, false));
        assertEquals(Step.HOLD, PestAimAcquisition.step(Phase.FLICK, NOW + 900, NOW, NOW + 200, true));
        assertEquals(Step.LANDED, PestAimAcquisition.step(Phase.FLICK, NOW + 900, NOW, NOW + 200, false));
        assertEquals(Step.HOLD, PestAimAcquisition.step(Phase.REACT, NOW + 949, NOW, NOW + 950, false));
        assertEquals(Step.REASSESS, PestAimAcquisition.step(Phase.REACT, NOW + 950, NOW, NOW + 950, false));
        assertEquals(Step.HOLD, PestAimAcquisition.step(Phase.IDLE, NOW + 60_000, NOW, NOW + 200, false));
    }

    @Test
    void noTurnOwnsTheCameraForMoreThanThreeSeconds() {
        assertEquals(Step.HOLD, PestAimAcquisition.step(Phase.FLICK, NOW + 3_000, NOW, NOW, true));
        for (Phase phase : List.of(Phase.PERCEIVE, Phase.FLICK, Phase.REACT)) {
            assertEquals(Step.EXPIRED, PestAimAcquisition.step(phase, NOW + 3_001, NOW, NOW + 5_000, true));
        }
    }

    @Test
    void aFarPestIsANarrowerTarget() {
        assertEquals(7.15, PestAimAcquisition.targetWidthDegrees(0.5, 4.0), 0.01);
        assertEquals(1.15, PestAimAcquisition.targetWidthDegrees(0.5, 25.0), 0.01);
        assertEquals(8.0, PestAimAcquisition.targetWidthDegrees(0.5, 1.0));
        assertEquals(1.0, PestAimAcquisition.targetWidthDegrees(0.5, 40.0));
        assertEquals(1.0, PestAimAcquisition.targetWidthDegrees(0.0, 4.0));
    }

    @Test
    void theStyleReadsPercentagesAndRestsAnywhereOnThePest() {
        HumanFlick.Style style = PestAimAcquisition.style(90.0, 40, 5, 12, 450.0, 4.0);

        assertEquals(90.0, style.overshootFromDegrees());
        assertEquals(0.40, style.overshootChance(), 1.0e-9);
        assertEquals(0.05, style.overshootMinFraction(), 1.0e-9);
        assertEquals(0.12, style.overshootMaxFraction(), 1.0e-9);
        assertEquals(450.0, style.turnSpeedCap());
        assertEquals(4.0, style.targetWidthDegrees());
        assertEquals(0.6, style.finalSpreadDegrees(), 1.0e-9);
        assertTrue(style.stagedCorrections());

        HumanFlick.Style swapped = PestAimAcquisition.style(90.0, 40, 12, 5, 450.0, 1.0);
        assertEquals(0.05, swapped.overshootMinFraction(), 1.0e-9);
        assertEquals(0.12, swapped.overshootMaxFraction(), 1.0e-9);
        assertEquals(0.25, swapped.finalSpreadDegrees(), 1.0e-9);

        HumanFlick.Style wide = PestAimAcquisition.style(30.0, 100, 1, 1, 60.0, 8.0);
        assertEquals(1.0, wide.overshootChance());
        assertEquals(1.2, wide.finalSpreadDegrees(), 1.0e-9);
    }

    @Test
    void eachRunGetsItsOwnPace() {
        PestAimAcquisition acquisition = new PestAimAcquisition();
        assertEquals(1.0, acquisition.tempo());

        SplittableRandom random = new SplittableRandom(13);
        double slowest = Double.MAX_VALUE;
        double fastest = 0.0;
        for (int i = 0; i < 500; i++) {
            acquisition.newRun(random);
            assertTrue(acquisition.tempo() >= 0.90 && acquisition.tempo() < 1.15);
            slowest = Math.min(slowest, acquisition.tempo());
            fastest = Math.max(fastest, acquisition.tempo());
        }
        assertTrue(fastest - slowest > 0.2);
    }

    @Test
    void aReactionHoldsTheCameraForItsPestUntilReset() {
        PestAimAcquisition acquisition = new PestAimAcquisition();
        assertFalse(acquisition.isHolding());

        acquisition.perceive(42, PestAimAcquisition.AimKind.VACUUM, NOW, 200L);

        assertEquals(Phase.PERCEIVE, acquisition.phase());
        assertTrue(acquisition.isHolding());
        assertFalse(acquisition.ownsCamera(null));

        acquisition.reset();

        assertEquals(Phase.IDLE, acquisition.phase());
        assertFalse(acquisition.isHolding());
        assertEquals(0L, acquisition.completedAt());
    }

    @Test
    void aPestSeenInTheLastEightSecondsIsRecalledButNotOnASecondLook() {
        PestSightings sightings = new PestSightings();
        sightings.record(7, new Vec3(3, 70, 4), NOW);

        PestSightings.Sighting recalled = PestAimAcquisition.recall(sightings, 7, false, NOW + 8_000);
        assertNotNull(recalled);
        assertEquals(new Vec3(3, 70, 4), recalled.eye());
        assertEquals(8_000L, recalled.ageMs());
        assertNull(PestAimAcquisition.recall(sightings, 7, false, NOW + 8_001));
        assertNull(PestAimAcquisition.recall(sightings, 7, true, NOW + 1_000));
        assertNull(PestAimAcquisition.recall(sightings, 8, false, NOW + 1_000));
    }

    @Test
    void aMemoryGrowsVaguerWithAgeAndAnUnseenPestIsOnlyARoughBearing() {
        assertEquals(6.0, PestAimAcquisition.memoryErrorDegrees(seenAgo(0L), 6.0), 1.0e-9);
        assertEquals(7.5, PestAimAcquisition.memoryErrorDegrees(seenAgo(2_000L), 6.0), 1.0e-9);
        assertEquals(9.0, PestAimAcquisition.memoryErrorDegrees(seenAgo(4_000L), 6.0), 1.0e-9);
        assertEquals(12.0, PestAimAcquisition.memoryErrorDegrees(seenAgo(8_000L), 6.0), 1.0e-9);
        assertEquals(12.0, PestAimAcquisition.memoryErrorDegrees(seenAgo(20_000L), 6.0), 1.0e-9);
        assertEquals(0.0, PestAimAcquisition.memoryErrorDegrees(seenAgo(5_000L), 0.0), 1.0e-9);

        assertEquals(9.0, PestAimAcquisition.memoryErrorDegrees(null, 6.0), 1.0e-9);
        assertEquals(30.0, PestAimAcquisition.memoryErrorDegrees(null, 20.0), 1.0e-9);
        assertEquals(5.0, PestAimAcquisition.memoryErrorDegrees(null, 2.0), 1.0e-9);
        assertEquals(5.0, PestAimAcquisition.memoryErrorDegrees(null, 0.0), 1.0e-9);
    }

    @Test
    void aRememberedBearingMissesMostlySidewaysAndNeverWildly() {
        SplittableRandom random = new SplittableRandom(17);
        double yawSquares = 0.0;
        double pitchSquares = 0.0;
        for (int i = 0; i < 500; i++) {
            RotationUtils.Rotation guess = PestAimAcquisition.misjudge(
                    new RotationUtils.Rotation(120.0f, 20.0f), 10.0, random);
            double yawError = guess.yaw - 120.0;
            double pitchError = guess.pitch - 20.0;
            assertTrue(Math.abs(yawError) <= 25.0 + 1.0e-4, "yaw " + yawError);
            assertTrue(Math.abs(pitchError) <= 6.0 + 1.0e-4, "pitch " + pitchError);
            yawSquares += yawError * yawError;
            pitchSquares += pitchError * pitchError;
        }
        double yawSpread = Math.sqrt(yawSquares / 500);
        double pitchSpread = Math.sqrt(pitchSquares / 500);
        assertTrue(yawSpread > 8.5 && yawSpread < 11.5, "yaw spread " + yawSpread);
        assertTrue(pitchSpread > 2.5 && pitchSpread < 3.5, "pitch spread " + pitchSpread);
    }

    @Test
    void aRememberedLookStaysClearOfThePoles() {
        SplittableRandom random = new SplittableRandom(23);
        boolean clamped = false;
        for (int i = 0; i < 500; i++) {
            RotationUtils.Rotation up = PestAimAcquisition.misjudge(new RotationUtils.Rotation(0.0f, -83.0f), 20.0,
                    random);
            RotationUtils.Rotation down = PestAimAcquisition.misjudge(new RotationUtils.Rotation(0.0f, 83.0f), 20.0,
                    random);
            assertTrue(up.pitch >= -85.0f && up.pitch <= 85.0f, "up " + up.pitch);
            assertTrue(down.pitch >= -85.0f && down.pitch <= 85.0f, "down " + down.pitch);
            clamped |= up.pitch == -85.0f || down.pitch == 85.0f;
        }
        assertTrue(clamped);

        RotationUtils.Rotation exact = PestAimAcquisition.misjudge(new RotationUtils.Rotation(33.0f, -12.0f), 0.0,
                random);
        assertEquals(33.0f, exact.yaw);
        assertEquals(-12.0f, exact.pitch);
    }

    @Test
    void aRememberedSwingIsFollowedByAShortSearch() {
        SplittableRandom random = new SplittableRandom(29);
        for (double tempo : new double[]{0.90, 1.0, 1.15}) {
            for (int i = 0; i < 500; i++) {
                long search = PestAimAcquisition.searchMs(random, tempo);
                assertTrue(search >= Math.floor(60.0 * tempo) && search <= Math.ceil(160.0 * tempo),
                        tempo + " -> " + search);
                assertTrue(search > PestAimAcquisition.reactMs(random, tempo));
            }
        }
    }

    @Test
    void onlyAMostlySidewaysTurnMayOvershoot() {
        assertFalse(PestAimAcquisition.mostlyVertical(0.0f, 30.0f, new RotationUtils.Rotation(170.0f, 20.0f)));
        assertFalse(PestAimAcquisition.mostlyVertical(170.0f, 0.0f, new RotationUtils.Rotation(-170.0f, 15.0f)));
        assertTrue(PestAimAcquisition.mostlyVertical(0.0f, 35.0f, new RotationUtils.Rotation(20.0f, -60.0f)));
        assertTrue(PestAimAcquisition.mostlyVertical(179.0f, -40.0f, new RotationUtils.Rotation(-179.0f, 10.0f)));
    }

    @Test
    void aRememberedSwingLandsExactlyWhereThePlayerThinksThePestIs() {
        HumanFlick.Style live = PestAimAcquisition.style(90.0, 40, 5, 12, 450.0, 4.0);

        HumanFlick.Style remembered = PestAimAcquisition.withoutSpread(live);

        assertEquals(0.0, remembered.finalSpreadDegrees());
        assertEquals(live, new HumanFlick.Style(remembered.overshootFromDegrees(), remembered.overshootChance(),
                remembered.overshootMinFraction(), remembered.overshootMaxFraction(), remembered.turnSpeedCap(),
                remembered.targetWidthDegrees(), live.finalSpreadDegrees(), remembered.stagedCorrections()));
    }

    private static PestSightings.Sighting seenAgo(long ageMs) {
        return new PestSightings.Sighting(Vec3.ZERO, ageMs);
    }
}
