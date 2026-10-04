package dev.aether.util;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RotationUtilsTest {
    private static final Vec3 EYE = new Vec3(12.5, 71.62, -40.25);
    private static final double FOV = 70.0;
    private static final double WIDE = 16.0 / 9.0;

    @Test
    void aPestJustPastStraightDownIsCloseToTheCrosshairThoughItsYawIsHalfATurnOff() {
        Vec3 pest = point(89.3f, 180.0f, 6.0);

        double angle = RotationUtils.angleFromCrosshair(EYE, 0.0f, 89.0f, pest);

        assertEquals(1.7, angle, 0.05);
        assertTrue(angle < 2.0);
        assertFalse(RotationUtils.isLookingAt(0.0f, 89.0f, EYE, pest, 2.0f));
    }

    @Test
    void measuresTheTrueAngleToPointsBesideAndBehind() {
        assertEquals(0.0, RotationUtils.angleFromCrosshair(EYE, 0.0f, 0.0f, EYE.add(0.0, 0.0, 9.0)), 0.05);
        assertEquals(90.0, RotationUtils.angleFromCrosshair(EYE, 0.0f, 0.0f, EYE.add(4.0, 0.0, 0.0)), 0.05);
        assertTrue(RotationUtils.angleFromCrosshair(EYE, 0.0f, 0.0f, EYE.add(0.0, 0.0, -9.0)) > 90.0);
        assertEquals(180.0, RotationUtils.angleFromCrosshair(EYE, 0.0f, 0.0f, EYE.add(0.0, 0.0, -9.0)), 0.05);
        assertEquals(30.0, RotationUtils.angleFromCrosshair(EYE, -40.0f, 0.0f, point(0.0f, -10.0f, 7.0)), 0.05);
        assertEquals(35.0, RotationUtils.angleFromCrosshair(EYE, 50.0f, -20.0f, point(15.0f, 50.0f, 7.0)), 0.05);
    }

    @Test
    void aPointOnTheEyeHasNoAngleAndIsNeverInView() {
        assertEquals(0.0, RotationUtils.angleFromCrosshair(EYE, 37.0f, -12.0f, EYE));
        assertFalse(RotationUtils.isInView(EYE, 37.0f, -12.0f, EYE, FOV, WIDE, 0.0));
    }

    @Test
    void theWindowShapeWidensTheViewSideways() {
        // a 70 degree vertical fov on a 16:9 window reaches about 51.2 degrees to either side
        assertTrue(RotationUtils.isInView(EYE, 0.0f, 0.0f, point(0.0f, 0.0f, 5.0), FOV, WIDE, 5.0));
        assertTrue(RotationUtils.isInView(EYE, 0.0f, 0.0f, point(0.0f, 45.0f, 5.0), FOV, WIDE, 5.0));
        assertTrue(RotationUtils.isInView(EYE, 0.0f, 0.0f, point(0.0f, -45.0f, 5.0), FOV, WIDE, 5.0));
        assertFalse(RotationUtils.isInView(EYE, 0.0f, 0.0f, point(0.0f, 49.0f, 5.0), FOV, WIDE, 5.0));
        assertTrue(RotationUtils.isInView(EYE, 0.0f, 0.0f, point(0.0f, 49.0f, 5.0), FOV, WIDE, 0.0));
        assertFalse(RotationUtils.isInView(EYE, 0.0f, 0.0f, point(0.0f, 49.0f, 5.0), FOV, 1.0, 0.0));
        assertFalse(RotationUtils.isInView(EYE, 0.0f, 0.0f, point(0.0f, 53.0f, 5.0), FOV, WIDE, 0.0));
    }

    @Test
    void theVerticalFovBoundsTheViewUpAndDown() {
        assertTrue(RotationUtils.isInView(EYE, 0.0f, 0.0f, point(28.0f, 0.0f, 5.0), FOV, WIDE, 5.0));
        assertTrue(RotationUtils.isInView(EYE, 0.0f, 0.0f, point(-28.0f, 0.0f, 5.0), FOV, WIDE, 5.0));
        assertFalse(RotationUtils.isInView(EYE, 0.0f, 0.0f, point(33.0f, 0.0f, 5.0), FOV, WIDE, 5.0));
        assertTrue(RotationUtils.isInView(EYE, 0.0f, 0.0f, point(33.0f, 0.0f, 5.0), FOV, WIDE, 0.0));
    }

    @Test
    void aCornerPointIsJudgedOnTheScreenNotOnItsPitch() {
        // 25 degrees down and 40 to the side lands 31.3 degrees down the screen
        Vec3 corner = point(25.0f, 40.0f, 5.0);

        assertTrue(RotationUtils.isInView(EYE, 0.0f, 0.0f, corner, FOV, WIDE, 0.0));
        assertFalse(RotationUtils.isInView(EYE, 0.0f, 0.0f, corner, FOV, WIDE, 5.0));
    }

    @Test
    void followsTheCameraWhereverItPoints() {
        assertTrue(RotationUtils.isInView(EYE, 135.0f, -30.0f, point(-30.0f, 135.0f, 8.0), FOV, WIDE, 5.0));
        assertTrue(RotationUtils.isInView(EYE, 0.0f, 90.0f, EYE.add(0.0, -6.0, 0.0), FOV, WIDE, 5.0));
        assertFalse(RotationUtils.isInView(EYE, 0.0f, 0.0f, EYE.add(0.0, 0.0, -9.0), FOV, WIDE, 0.0));
        assertFalse(RotationUtils.isInView(EYE, 0.0f, 0.0f, EYE.add(0.0, -6.0, 0.0), FOV, WIDE, 0.0));
    }

    private static Vec3 point(float pitch, float yaw, double distance) {
        return EYE.add(Vec3.directionFromRotation(pitch, yaw).scale(distance));
    }
}
