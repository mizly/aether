package dev.aether.modules.pest.helpers;

import net.minecraft.world.phys.Vec3;
import net.fabricmc.loader.api.FabricLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class PestFlightControllerTest {
    @BeforeAll
    static void configureLoader() throws Exception {
        var loader = FabricLoader.getInstance();
        var configDir = loader.getClass().getDeclaredField("configDir");
        configDir.setAccessible(true);
        if (configDir.get(loader) == null) configDir.set(loader, Files.createTempDirectory("aether-flight-test"));
    }

    @Test
    void brakingKeepsTheCapturedApproachUntilTheTargetIsReset() {
        PestFlightController controller = new PestFlightController();
        double fastHandoff = controller.handoffDistance(new Vec3(0, 0, 1.5), 12);
        assertTrue(fastHandoff > 20);
        assertEquals(fastHandoff, controller.handoffDistance(Vec3.ZERO, 12));
        controller.reset();
        assertEquals(12, controller.handoffDistance(Vec3.ZERO, 12));
        assertEquals(15, controller.handoffDistance(Vec3.ZERO, 15));
        assertEquals(12, controller.handoffDistance(Vec3.ZERO, 5));
    }

    @Test
    void estimatesTargetMotionOncePerTickFromPositions() {
        PestFlightController controller = new PestFlightController();
        assertEquals(Vec3.ZERO, controller.sampleVelocity(1, Vec3.ZERO, 10));
        Vec3 estimate = controller.sampleVelocity(1, new Vec3(0, 0, 0.2), 11);
        assertTrue(estimate.z > 0 && estimate.z < 0.2);
        assertEquals(estimate, controller.sampleVelocity(1, new Vec3(0, 0, 0.2), 11));
        assertTrue(controller.sampleVelocity(1, new Vec3(0, 0, 0.4), 12).z > estimate.z);
    }

    @Test
    void dropsOldMotionWhenTargetsChangeTeleportOrSamplesGoStale() {
        PestFlightController controller = new PestFlightController();
        controller.sampleVelocity(1, Vec3.ZERO, 10);
        controller.sampleVelocity(1, new Vec3(0, 0, 0.2), 11);
        assertEquals(Vec3.ZERO, controller.sampleVelocity(2, new Vec3(0, 0, 0.4), 12));
        assertEquals(Vec3.ZERO, controller.sampleVelocity(2, new Vec3(0, 0, 10), 13));
        controller.sampleVelocity(2, new Vec3(0, 0, 10.2), 14);
        assertEquals(Vec3.ZERO, controller.sampleVelocity(2, new Vec3(0, 0, 10.4), 30));
        controller.reset();
        assertEquals(Vec3.ZERO, controller.sampleVelocity(2, new Vec3(0, 0, 10.6), 31));
    }

    @Test
    void reservesVacuumRangeForAltitudeAndWeakerVacuums() {
        assertEquals(5, PestFlightController.followDistance(5, 7.5, 3));
        assertTrue(PestFlightController.followDistance(5, 5, 3) < 3);
        assertEquals(0, PestFlightController.followDistance(5, 5, 6));
        double horizontal = PestFlightController.followDistance(7, 7.5, -4);
        assertEquals(6.5, Math.hypot(horizontal, 4), 1.0e-6);
    }

    @Test
    void waitsForTheCameraBeforeAcceleratingTowardAPest() {
        assertTrue(PestFlightController.facesTarget(new Vec3(0, 0, 5), 0));
        assertFalse(PestFlightController.facesTarget(new Vec3(0, 0, 5), 180));
        assertFalse(PestFlightController.facesTarget(new Vec3(5, 0, 0), 0));
        assertFalse(PestFlightController.facesTarget(Vec3.ZERO, 0));
    }

    @Test
    void walkingSettlesWithoutReversingAtDifferentGroundSpeeds() {
        for (double acceleration : new double[]{0.1, 0.3, 0.6}) {
            PestFlightController controller = new PestFlightController();
            double position = 0;
            double velocity = 0;
            int starts = 0;
            int previous = 0;
            for (int tick = 0; tick < 150; tick++) {
                int input = controller.walkingInput(new Vec3(0, 0, 10 - position), 0, 5, true).forward();
                assertTrue(input >= 0, "Walking must not brake with S");
                if (input > 0 && previous == 0) starts++;
                previous = input;
                velocity += input * acceleration;
                position += velocity;
                velocity *= 0.546;
            }
            assertEquals(1, starts, "A stationary pest must not cause repeated W taps");
            assertTrue(10 - position <= 5);
            assertTrue(10 - position > 0, "Overshot the pest");
            assertTrue(Math.abs(velocity) < 1.0e-6);
        }
    }

    @Test
    void walkingHoldsThroughRangeJitterThenFollowsAnEscapingPest() {
        PestFlightController controller = new PestFlightController();
        assertEquals(1, controller.walkingInput(new Vec3(0, 0, 8), 0, 5, true).forward());
        assertEquals(1, controller.walkingInput(new Vec3(0, 0, 5.2), 0, 5, true).forward());
        assertEquals(0, controller.walkingInput(new Vec3(0, 0, 5), 0, 5, true).forward());
        for (double distance : new double[]{4.9, 5.1, 5.5, 4.8, 5.7}) {
            assertEquals(0, controller.walkingInput(new Vec3(0, 0, distance), 0, 5, true).forward());
        }
        assertEquals(1, controller.walkingInput(new Vec3(0, 0, 5.8), 0, 5, true).forward());
    }

    @Test
    void walkingKeepsAMovingPestWithinVacuumRange() {
        PestFlightController controller = new PestFlightController();
        double position = 0;
        double velocity = 0;
        double pest = 6;
        for (int tick = 0; tick < 200; tick++) {
            int input = controller.walkingInput(new Vec3(0, 0, pest - position), 0, 5, true).forward();
            assertTrue(input >= 0);
            velocity += input * 0.2;
            position += velocity;
            velocity *= 0.546;
            pest += 0.15;
            assertTrue(pest - position < 7.5, "Lost vacuum range while following");
            assertTrue(pest - position > 0);
        }
    }

    @Test
    void walkingReleasesMovementWhenBlockedOrFacingAway() {
        PestFlightController controller = new PestFlightController();
        Vec3 offset = new Vec3(0, 0, 8);
        assertEquals(1, controller.walkingInput(offset, 0, 5, true).forward());
        assertEquals(0, controller.walkingInput(offset, 0, 5, false).forward());
        assertEquals(0, controller.walkingInput(offset, 180, 5, true).forward());
        assertEquals(0, controller.walkingInput(new Vec3(0, 0, 5.2), 0, 5, true).forward());
    }

    @Test
    void walkingApproachDoesNotCarryOverToAnotherTarget() {
        PestFlightController controller = new PestFlightController();
        controller.sampleVelocity(1, new Vec3(0, 0, 8), 10);
        controller.walkingInput(new Vec3(0, 0, 8), 0, 5, true);
        controller.sampleVelocity(2, new Vec3(0, 0, 5.2), 11);
        assertEquals(0, controller.walkingInput(new Vec3(0, 0, 5.2), 0, 5, true).forward());
        controller.walkingInput(new Vec3(0, 0, 8), 0, 5, true);
        controller.reset();
        assertEquals(0, controller.walkingInput(new Vec3(0, 0, 5.2), 0, 5, true).forward());
    }
}
