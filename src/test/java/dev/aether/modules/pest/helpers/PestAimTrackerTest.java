package dev.aether.modules.pest.helpers;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PestAimTrackerTest {
    @Test
    void aBodySpotNeverLeavesTheHitbox() {
        SplittableRandom random = new SplittableRandom(5);
        double[][] pests = {{0.5, 0.9, 0.45}, {0.4, 0.3, 0.13}};
        for (double[] pest : pests) {
            double width = pest[0];
            double height = pest[1];
            double eyeHeight = pest[2];
            double widest = 0.0;
            for (int i = 0; i < 2000; i++) {
                Vec3 spot = PestAimTracker.spotOffset(random, width, height, eyeHeight, 0.95);
                double radius = Math.hypot(spot.x, spot.z);
                widest = Math.max(widest, radius);
                assertTrue(radius <= width / 2.0 * 0.95 + 1.0e-9, "radius " + radius);
                assertTrue(spot.y >= -eyeHeight && spot.y <= height - eyeHeight, "height " + spot.y);
            }
            assertTrue(widest > width / 2.0 * 0.8, "widest " + widest);
        }
    }

    @Test
    void driftStrengthSetsHowMuchOfTheBodyTheAimRoams() {
        assertEquals(0.0, PestAimTracker.reach(0.0), 1.0e-9);
        assertEquals(0.8, PestAimTracker.reach(1.0), 1.0e-9);
        assertEquals(0.95, PestAimTracker.reach(2.0), 1.0e-9);
        assertEquals(Vec3.ZERO, PestAimTracker.spotOffset(new SplittableRandom(1), 0.5, 0.9, 0.45, 0.0));
    }
}
