package dev.aether.modules.pathfinding.rotation.strategy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TrackingRotationStrategyTest {
    @Test
    void tracksAcrossYawWrapUsingTheShortestTurn() {
        var rotation = TrackingRotationStrategy.step(179, 0, -179, 0, 240, 0.05);
        assertTrue(rotation.yaw > 179 && rotation.yaw < 181);
    }

    @Test
    void limitsCombinedTurnSpeed() {
        var rotation = TrackingRotationStrategy.step(0, 0, 120, 80, 240, 0.05);
        assertEquals(12.0, Math.hypot(rotation.yaw, rotation.pitch), 0.001);
    }

    @Test
    void respondsToRetargetingWithoutRestartingAnEase() {
        var first = TrackingRotationStrategy.step(0, 0, 90, 0, 240, 0.05);
        var second = TrackingRotationStrategy.step(first.yaw, first.pitch, -90, 0, 240, 0.05);
        assertTrue(first.yaw > 0);
        assertTrue(second.yaw < first.yaw);
    }

    @Test
    void convergesWithoutOvershootingAndClampsPitch() {
        var rotation = TrackingRotationStrategy.step(0, 80, 1, 100, 240, 0.05);
        assertTrue(rotation.yaw > 0 && rotation.yaw < 1);
        assertTrue(rotation.pitch > 80 && rotation.pitch <= 90);
        assertEquals(10, TrackingRotationStrategy.step(10, 20, 90, 80, 240, 0).yaw);
    }
}
