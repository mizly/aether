package dev.aether.modules.pathfinding.execution;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WalkingMotionTest {
    @Test
    void keepsWorldMovementFixedWhileLookingAtATargetInAnyDirection() {
        Vec3 destination = new Vec3(0, 0, 8);
        assertEquals(new WalkingMotion.Input(1, 0), WalkingMotion.horizontalInput(destination, 0, 0.1));
        assertEquals(new WalkingMotion.Input(0, -1), WalkingMotion.horizontalInput(destination, 90, 0.1));
        assertEquals(new WalkingMotion.Input(-1, 0), WalkingMotion.horizontalInput(destination, 180, 0.1));
        assertEquals(new WalkingMotion.Input(0, 1), WalkingMotion.horizontalInput(destination, 270, 0.1));
    }

    @Test
    void rightStrafeFollowsMinecraftYawConvention() {
        assertEquals(new Vec3(-1, 0, 0), WalkingMotion.direction(new WalkingMotion.Input(0, 1), 0));
        assertEquals(new WalkingMotion.Input(0, 1),
                WalkingMotion.horizontalInput(new Vec3(-4, 0, 0), 0, 0.1));
        assertEquals(new WalkingMotion.Input(0, -1),
                WalkingMotion.horizontalInput(new Vec3(4, 0, 0), 0, 0.1));
    }

    @Test
    void picksTheNearestOfEightHeadingsThroughoutARotation() {
        for (int yaw = -360; yaw <= 360; yaw += 15) {
            for (int bearing = 0; bearing < 360; bearing++) {
                double radians = Math.toRadians(bearing);
                Vec3 desired = new Vec3(Math.cos(radians), 0, Math.sin(radians));
                WalkingMotion.Input input = WalkingMotion.horizontalInput(desired, yaw, 0.1);
                Vec3 actual = WalkingMotion.direction(input, yaw);
                assertTrue(actual.dot(desired) >= Math.cos(Math.toRadians(22.5)) - 1.0e-9,
                        "Excess steering error at yaw " + yaw + ", bearing " + bearing);
                assertTrue(Math.abs(input.forward()) <= 1);
                assertTrue(Math.abs(input.right()) <= 1);
                assertEquals(1.0, actual.length(), 1.0e-9);
            }
        }
    }

    @Test
    void avoidsDiagonalInputForSmallHeadingErrors() {
        assertEquals(new WalkingMotion.Input(1, 0),
                WalkingMotion.horizontalInput(new Vec3(-0.2, 0, 1), 0, 0.1));
        assertEquals(new WalkingMotion.Input(1, 1),
                WalkingMotion.horizontalInput(new Vec3(-0.5, 0, 1), 0, 0.1));
    }

    @Test
    void releasesInputInsideDeadzoneOrForInvalidDirections() {
        WalkingMotion.Input released = new WalkingMotion.Input(0, 0);
        assertEquals(released, WalkingMotion.horizontalInput(new Vec3(0.04, 10, 0.02), 0, 0.1));
        assertEquals(released, WalkingMotion.horizontalInput(Vec3.ZERO, 0, 0));
        assertEquals(released, WalkingMotion.horizontalInput(new Vec3(Double.NaN, 0, 1), 0, 0.1));
        assertEquals(released, WalkingMotion.horizontalInput(new Vec3(0, 0, 1), Float.NaN, 0.1));
        assertEquals(Vec3.ZERO, WalkingMotion.direction(released, 90));
    }
}
