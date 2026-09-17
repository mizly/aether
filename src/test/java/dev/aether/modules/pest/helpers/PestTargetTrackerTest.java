package dev.aether.modules.pest.helpers;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PestTargetTrackerTest {
    @Test
    void choosesNearestFromCurrentPositionInsteadOfOriginalQueueOrder() {
        Vec3 first = new Vec3(1, 80, 0);
        Vec3 second = new Vec3(14, 80, 0);
        Vec3 third = new Vec3(8, 80, 0);
        var queue = new ArrayDeque<>(List.of(first, second, third));
        assertSame(first, PestTargetTracker.nearestQueuedTarget(queue, target -> true,
                new Vec3(0, 80, 0)::distanceToSqr));
        assertSame(third, PestTargetTracker.nearestQueuedTarget(queue, target -> true,
                new Vec3(9, 80, 0)::distanceToSqr));
        assertEquals(3, queue.size());
    }

    @Test
    void prunesIneligibleTargetsAndKeepsEqualDistanceOrderStable() {
        var queue = new ArrayDeque<>(List.of(0, 3, -3, 12, 1));
        assertEquals(3, PestTargetTracker.nearestQueuedTarget(queue, target -> target != 0 && target != 1,
                target -> target * target));
        assertEquals(List.of(3, -3, 12), List.copyOf(queue));
        assertNull(PestTargetTracker.nearestQueuedTarget(queue, target -> false, target -> 0));
        assertTrue(queue.isEmpty());
    }

    @Test
    void includesVerticalDistanceAndRechecksMovingPests() {
        double[] first = {2, 30};
        double[] second = {9, 0};
        var queue = new ArrayDeque<>(List.of(first, second));
        assertSame(second, PestTargetTracker.nearestQueuedTarget(queue, target -> true,
                target -> target[0] * target[0] + target[1] * target[1]));
        first[1] = 0;
        assertSame(first, PestTargetTracker.nearestQueuedTarget(queue, target -> true,
                target -> target[0] * target[0] + target[1] * target[1]));
    }
}
