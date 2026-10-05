package dev.aether.macro.fishing;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StriderLookUpCastTest {
    private static final int HORIZON = 170;

    @BeforeAll
    static void bootstrap() {
        // bootstrap reroutes stdout into the game log under logs/, where every later test's prints
        // would pile up as rotated archives in the repo, so the plain streams go back afterwards
        PrintStream out = System.out;
        PrintStream err = System.err;
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        System.setOut(out);
        System.setErr(err);
    }

    @Test
    void aLookUpThrowComesDownAboutEightBlocksOutWhateverTheYaw() {
        // lava one block under the feet, all the way round
        FakeCastLevel level = new FakeCastLevel()
                .fill(-12, -2, -12, 12, -2, 12, Blocks.STONE.defaultBlockState())
                .fill(-12, -1, -12, 12, -1, 12, Blocks.LAVA.defaultBlockState());
        Vec3 eye = new Vec3(0.5, 1.62, 0.5);
        for (float yaw = -180.0f; yaw < 180.0f; yaw += 30.0f) {
            Vec3 landing = CastSim.predictCastLanding(level, eye, yaw, -86.0f, CastSim::isLava, HORIZON);
            assertNotNull(landing);
            double out = Math.hypot(landing.x - eye.x, landing.z - eye.z);
            assertTrue(out >= 7.9 && out <= 8.1, "landed " + out + " out at yaw " + yaw);
            int tick = StriderFishingMacro.landingTick(CastSim.castPath(eye, yaw, -86.0f, HORIZON), eye, landing);
            assertTrue(tick >= 110 && tick <= 130, "landed on tick " + tick);
        }
    }

    @Test
    void everyPitchPastTheClampThrowsTheSameLob() {
        Vec3 eye = new Vec3(0.5, 1.27, 0.5);
        Vec3[] shallow = CastSim.castPath(eye, 37.0f, -80.0f, HORIZON);
        Vec3[] steep = CastSim.castPath(eye, 37.0f, -89.0f, HORIZON);
        for (int i = 0; i < shallow.length; i++) {
            assertEquals(0.0, shallow[i].distanceTo(steep[i]), 1e-9);
        }
    }

    @Test
    void theLobStaysInsideTheLeashUpToItsApex() {
        // vanilla drops a hook more than 32 blocks from its owner's feet
        Vec3 feet = new Vec3(0.5, 0.0, 0.5);
        Vec3[] path = CastSim.castPath(feet.add(0.0, 1.62, 0.0), 0.0f, -86.0f, HORIZON);
        int apex = 0;
        for (int i = 1; i < path.length; i++) {
            if (path[i].y > path[apex].y) {
                apex = i;
            }
        }
        assertTrue(apex > 0);
        for (int i = 0; i <= apex; i++) {
            assertTrue(path[i].distanceTo(feet) < 32.0);
        }
    }
}
