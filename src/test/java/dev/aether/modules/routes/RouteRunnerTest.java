package dev.aether.modules.routes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteRunnerTest {

    @Test
    void standingOnTheLipOfAWaypointStillCountsAsThere() {
        assertTrue(RouteRunner.isWithinWaypoint(0.6, 0.0, -0.6, 0.7));
        assertTrue(RouteRunner.isWithinWaypoint(0.0, 1.0, 0.0, 0.7));
    }

    @Test
    void aBlockOverOrAFallBelowIsNotThere() {
        assertFalse(RouteRunner.isWithinWaypoint(0.9, 0.0, 0.0, 0.7));
        assertFalse(RouteRunner.isWithinWaypoint(0.0, -1.5, 0.0, 0.7));
    }

    @Test
    void aWalkPassingThroughAWaypointCountsFromFurtherOut() {
        assertTrue(RouteRunner.isWithinWaypoint(1.2, 0.0, 0.0, 1.3));
    }

    @Test
    void centringAimsAtTheMiddleOfTheBlockStoodOn() {
        net.minecraft.world.phys.Vec3 offset = BlockCentering.offsetToCentre(
                new net.minecraft.world.phys.Vec3(-650.95, 97.0, -90.1), new net.minecraft.core.BlockPos(-651, 96, -91));

        assertTrue(Math.abs(offset.x - 0.45) < 1.0e-9);
        assertTrue(Math.abs(offset.z + 0.4) < 1.0e-9);
        assertTrue(offset.y == 0.0);
    }

    @Test
    void onlyAPlayerInsideTheToleranceCountsAsCentred() {
        net.minecraft.core.BlockPos block = new net.minecraft.core.BlockPos(-694, 120, 78);

        assertTrue(BlockCentering.isCentred(new net.minecraft.world.phys.Vec3(-693.5, 120.0, 78.5), block));
        assertTrue(BlockCentering.isCentred(new net.minecraft.world.phys.Vec3(-693.42, 121.0, 78.42), block));
        assertFalse(BlockCentering.isCentred(new net.minecraft.world.phys.Vec3(-693.35, 120.0, 78.5), block));
    }
}
