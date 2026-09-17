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
}
