package dev.aether.macro.fishing;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CastSimTest {
    @Test
    void theFloatLeavesJustAheadOfTheEye() {
        Vec3[] path = CastSim.castPath(new Vec3(0.0, 1.27, 0.0), 0.0f, 10.0f, CastSim.DEFAULT_TICKS);
        assertEquals(0.0, path[0].x, 1e-9);
        assertEquals(1.27, path[0].y, 1e-9);
        assertEquals(0.3, path[0].z, 1e-9);
    }

    @Test
    void theFloatDropsUnderTheCrosshairLine() {
        // why a crouched look over a rim that is barely below the eye still clips it
        float pitch = 6.0f;
        Vec3[] path = CastSim.castPath(Vec3.ZERO, 0.0f, pitch, CastSim.DEFAULT_TICKS);
        double sightY = -Math.tan(Math.toRadians(pitch)) * path[2].z;
        assertTrue(path[2].y < sightY);
    }

    @Test
    void theThrowOnlyCountsAsSafeWithLandingPitchesEitherSide() {
        boolean[] lands = {false, true, true, true, true, true, false};
        assertEquals(0, CastSim.castMargin(lands, 1, 6));
        assertEquals(2, CastSim.castMargin(lands, 3, 6));
        assertEquals(0, CastSim.castMargin(lands, 5, 6));
        // past the cap more room stops counting, so a wide pool cannot outrank a closer throw
        assertEquals(1, CastSim.castMargin(lands, 3, 1));
    }

    @Test
    void aCellWhereAFloatAlreadyMissedIsNeverCountedOnAgain() {
        BlockPos landing = new BlockPos(3, 63, -2);
        assertTrue(CastSim.acceptsLanding(landing, Set.of()));
        assertTrue(CastSim.acceptsLanding(landing, Set.of(new BlockPos(3, 63, -1))));
        assertFalse(CastSim.acceptsLanding(landing, Set.of(new BlockPos(3, 63, -2))));
        // no landing at all is never a cast worth making
        assertFalse(CastSim.acceptsLanding(null, Set.of()));
    }

    @Test
    void theCursorIsAimedAtTheFloatItself() {
        assertEquals(0.0f, CastSim.yawTo(0.0, 4.0), 0.001f);
        assertEquals(90.0f, CastSim.yawTo(-4.0, 0.0), 0.001f);
        assertEquals(-90.0f, CastSim.yawTo(4.0, 0.0), 0.001f);
        // the float sits below eye level, so looking at it is a downward pitch
        assertEquals(45.0f, CastSim.pitchTo(0.0, -4.0, 4.0), 0.001f);
        assertEquals(0.0f, CastSim.pitchTo(0.0, 0.0, 4.0), 0.001f);
    }
}
