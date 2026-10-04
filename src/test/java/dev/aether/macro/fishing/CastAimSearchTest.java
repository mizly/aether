package dev.aether.macro.fishing;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CastAimSearchTest {
    private static final BlockPos FEET = new BlockPos(0, 64, 0);
    private static final Vec3 EYE = new Vec3(0.5, 64 + 1.62, 0.5);

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

    // a stone floor at y 63 with a 3x3 lava pool two blocks east of the player
    private static FakeCastLevel lavaPool() {
        return new FakeCastLevel()
                .fill(-8, 63, -8, 8, 63, 8, Blocks.STONE.defaultBlockState())
                .fill(2, 63, -1, 4, 63, 1, Blocks.LAVA.defaultBlockState());
    }

    private static CastAimSearch search(FakeCastLevel level, Set<BlockPos> rejected, long seed) {
        return new CastAimSearch(level, FEET, EYE, 6, rejected, CastSim::isLava, new Random(seed));
    }

    private static double horizontal(BlockPos cell) {
        return Math.hypot(cell.getX() + 0.5 - EYE.x, cell.getZ() + 0.5 - EYE.z);
    }

    @Test
    void theNearestLavaIsTriedFirstAndItsAimLandsInIt() {
        FakeCastLevel level = lavaPool();
        for (long seed = 0; seed < 5; seed++) {
            CastAimSearch.Step step = search(level, new HashSet<>(), seed).step(1);

            assertEquals(CastAimSearch.Status.FOUND, step.status());
            assertEquals(new BlockPos(2, 63, 0), step.aim().block());
            Vec3 landing = CastSim.predictCastLanding(level, EYE, step.aim().yaw(), step.aim().pitch(),
                    CastSim::isLava, CastSim.DEFAULT_TICKS);
            assertNotNull(landing);
            assertTrue(CastSim.isLava(level.getBlockState(BlockPos.containing(landing))));
        }
    }

    @Test
    void aMissCarriesOnPastTheCellsAlreadyTriedUntilEveryCellIsSpent() {
        Set<BlockPos> rejected = new HashSet<>();
        CastAimSearch search = search(lavaPool(), rejected, 7L);
        List<BlockPos> found = new ArrayList<>();
        CastAimSearch.Step step;
        int steps = 0;
        do {
            step = search.step(1);
            if (step.status() == CastAimSearch.Status.FOUND) {
                found.add(step.aim().block());
                // what a float landing off the lava does to the aim it was cast at
                rejected.add(step.aim().block());
            }
        } while (step.status() != CastAimSearch.Status.EXHAUSTED && ++steps < 100);

        assertEquals(CastAimSearch.Status.EXHAUSTED, step.status());
        assertTrue(found.size() >= 2);
        assertEquals(found.size(), new HashSet<>(found).size());
        for (int i = 1; i < found.size(); i++) {
            assertTrue(horizontal(found.get(i - 1)) <= horizontal(found.get(i)));
        }
    }

    @Test
    void aCellNoThrowCanReachOnlyUsesUpThatTicksBudget() {
        // the nearest lava sits at the bottom of a shaft that rises well above the eye
        FakeCastLevel level = lavaPool()
                .fill(-1, 63, -1, -1, 63, -1, Blocks.LAVA.defaultBlockState())
                .fill(-2, 64, -1, -2, 72, -1, Blocks.STONE.defaultBlockState())
                .fill(0, 64, -1, 0, 72, -1, Blocks.STONE.defaultBlockState())
                .fill(-1, 64, -2, -1, 72, -2, Blocks.STONE.defaultBlockState())
                .fill(-1, 64, 0, -1, 72, 0, Blocks.STONE.defaultBlockState());
        CastAimSearch search = search(level, new HashSet<>(), 3L);

        assertEquals(CastAimSearch.Status.WORKING, search.step(1).status());
        CastAimSearch.Step step = search.step(1);
        assertEquals(CastAimSearch.Status.FOUND, step.status());
        assertEquals(new BlockPos(2, 63, 0), step.aim().block());
    }

    @Test
    void rejectedCellsAreSkippedWithoutSpendingTheBudget() {
        Set<BlockPos> rejected = new HashSet<>(CastAimSearch.surfaceCells(lavaPool(), FEET, 6, CastSim::isLava));
        rejected.remove(new BlockPos(4, 63, 1));

        CastAimSearch.Step step = search(lavaPool(), rejected, 11L).step(1);

        assertEquals(CastAimSearch.Status.FOUND, step.status());
        assertEquals(new BlockPos(4, 63, 1), step.aim().block());
    }

    @Test
    void onlyTheTopOfTheLiquidIsACandidate() {
        FakeCastLevel level = new FakeCastLevel()
                .fill(-8, 60, -8, 8, 63, 8, Blocks.STONE.defaultBlockState())
                .fill(3, 61, 0, 3, 63, 0, Blocks.LAVA.defaultBlockState())
                .fill(3, 63, 2, 3, 63, 2, Blocks.LAVA.defaultBlockState())
                .fill(3, 64, 2, 3, 64, 2, Blocks.STONE.defaultBlockState())
                .fill(-3, 63, 0, -3, 63, 0, Blocks.LAVA.defaultBlockState())
                .fill(-3, 64, 0, -3, 64, 0, Blocks.WATER.defaultBlockState());

        assertEquals(List.of(new BlockPos(3, 63, 0)), CastAimSearch.surfaceCells(level, FEET, 6, CastSim::isLava));
    }
}
