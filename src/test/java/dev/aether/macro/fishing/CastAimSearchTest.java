package dev.aether.macro.fishing;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.Mth;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CastAimSearchTest {
    private static final BlockPos FEET = new BlockPos(0, 64, 0);
    private static final Vec3 EYE = new Vec3(0.5, 64 + 1.62, 0.5);
    private static final CastAimSearch.Spec CLASSIC = CastAimSearch.Spec.STRIDER_CLASSIC;
    private static final CastAimSearch.Spec GENERAL = CastAimSearch.Spec.GENERAL;

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

    // the player on a stone block in a still lake whose surface sits just under their feet
    private static FakeCastLevel lake() {
        return new FakeCastLevel()
                .fill(-10, 62, -10, 10, 62, 10, Blocks.STONE.defaultBlockState())
                .fill(-10, 63, -10, 10, 63, 10, Blocks.WATER.defaultBlockState())
                .fill(0, 63, 0, 0, 63, 0, Blocks.STONE.defaultBlockState());
    }

    // a pillar three blocks over a lake, where a shallow throw would come down well past the cap
    private static FakeCastLevel pillarOverLake() {
        return new FakeCastLevel()
                .fill(-10, 59, -10, 10, 59, 10, Blocks.STONE.defaultBlockState())
                .fill(-10, 60, -10, 10, 60, 10, Blocks.WATER.defaultBlockState())
                .fill(0, 60, 0, 0, 63, 0, Blocks.STONE.defaultBlockState());
    }

    private static CastAimSearch search(FakeCastLevel level, Set<BlockPos> rejected, long seed) {
        return new CastAimSearch(level, FEET, EYE, 0.0f, CLASSIC, rejected, CastSim::isLava, new Random(seed));
    }

    private static CastAimSearch fishing(FakeCastLevel level, CastAimSearch.Spec spec, Set<BlockPos> rejected,
                                         long seed) {
        return new CastAimSearch(level, FEET, EYE, 30.0f, spec, rejected, CastSim::isWater, new Random(seed));
    }

    private static CastAimSearch.Step runUntilDone(CastAimSearch search) {
        CastAimSearch.Step step = search.step();
        for (int i = 0; i < 500 && step.status() == CastAimSearch.Status.WORKING; i++) {
            step = search.step();
        }
        return step;
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
    void noThrowIsPickedThatComesDownInACellAFloatAlreadyMissedIn() {
        FakeCastLevel level = lake();
        // every cell beside a candidate is one a float already missed in
        Set<BlockPos> rejected = new HashSet<>();
        for (BlockPos cell : CastAimSearch.surfaceCells(level, FEET, 6, CastSim::isWater)) {
            if (((cell.getX() + cell.getZ()) & 1) != 0) {
                rejected.add(cell);
            }
        }
        int found = 0;
        for (long seed = 0; seed < 4; seed++) {
            CastAimSearch search = fishing(level, GENERAL, rejected, seed);
            CastAimSearch.Step step;
            int steps = 0;
            while ((step = search.step()).status() != CastAimSearch.Status.EXHAUSTED && ++steps < 500) {
                if (step.status() != CastAimSearch.Status.FOUND) {
                    continue;
                }
                found++;
                CastSim.CastAim aim = step.aim();
                // the pick and the pitches either side that make up its margin
                for (float off : new float[]{-2.0f, -1.0f, 0.0f, 1.0f, 2.0f}) {
                    Vec3 landing = CastSim.predictCastLanding(level, EYE, aim.yaw(), aim.pitch() + off,
                            CastSim::isWater, CastSim.DEFAULT_TICKS, 5.0);
                    assertNotNull(landing);
                    assertTrue(CastSim.acceptsLanding(BlockPos.containing(landing), rejected));
                }
            }
        }
        assertTrue(found >= 1);
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

    @Test
    void everyGeneralAimLandsWithinFiveBlocksAtAModeratePitch() {
        for (long seed = 0; seed < 4; seed++) {
            FakeCastLevel level = seed % 2 == 0 ? lake() : pillarOverLake();
            Set<BlockPos> rejected = new HashSet<>();
            CastAimSearch search = fishing(level, GENERAL, rejected, seed);
            int found = 0;
            CastAimSearch.Step step;
            int steps = 0;
            while ((step = search.step()).status() != CastAimSearch.Status.EXHAUSTED && ++steps < 500) {
                if (step.status() != CastAimSearch.Status.FOUND) {
                    continue;
                }
                found++;
                CastSim.CastAim aim = step.aim();
                assertTrue(aim.pitch() >= 20.0f && aim.pitch() <= 70.0f);
                Vec3 landing = CastSim.predictCastLanding(level, EYE, aim.yaw(), aim.pitch(),
                        CastSim::isWater, CastSim.DEFAULT_TICKS);
                assertNotNull(landing);
                assertTrue(Math.hypot(landing.x - EYE.x, landing.z - EYE.z) <= 5.0);
                // two degrees of pitch either way still comes down in the water inside the cap
                for (float off : new float[]{-2.0f, -1.0f, 1.0f, 2.0f}) {
                    assertNotNull(CastSim.predictCastLanding(level, EYE, aim.yaw(), aim.pitch() + off,
                            CastSim::isWater, CastSim.DEFAULT_TICKS, 5.0));
                }
                rejected.add(aim.block());
            }
            assertTrue(found >= 10);
        }
    }

    @Test
    void aGeneralSearchNeverWidensAndGivesUpOnWaterPastTheCap() {
        // from a pillar three blocks up the only water starts five and a half blocks out: an easy throw, past the cap
        FakeCastLevel level = new FakeCastLevel()
                .fill(-10, 59, -10, 10, 60, 10, Blocks.STONE.defaultBlockState())
                .fill(6, 60, -10, 10, 60, 10, Blocks.WATER.defaultBlockState())
                .fill(0, 61, 0, 0, 63, 0, Blocks.STONE.defaultBlockState());

        assertEquals(CastAimSearch.Status.EXHAUSTED,
                runUntilDone(fishing(level, GENERAL, new HashSet<>(), 1L)).status());
        assertEquals(CastAimSearch.Status.FOUND,
                runUntilDone(fishing(level, CLASSIC, new HashSet<>(), 1L)).status());
        assertSame(GENERAL, GENERAL.widened(3));
        assertEquals(6, CLASSIC.widened(0).radius());
        assertEquals(8, CLASSIC.widened(1).radius());
        assertEquals(14, CLASSIC.widened(10).radius());
    }

    @Test
    void aTargetIsAimedAtAndOnlyALandingWithinABlockOfItCounts() {
        FakeCastLevel level = lake();
        Vec3 target = new Vec3(3.5, 63.889, 0.5);
        CastAimSearch.Spec aimed = GENERAL.withTarget(target);
        for (long seed = 0; seed < 5; seed++) {
            CastAimSearch.Step step = fishing(level, aimed, new HashSet<>(), seed).step();

            assertEquals(CastAimSearch.Status.FOUND, step.status());
            assertEquals(new BlockPos(3, 63, 0), step.aim().block());
            float straightAt = CastSim.yawTo(target.x - EYE.x, target.z - EYE.z);
            assertTrue(Math.abs(Mth.wrapDegrees(step.aim().yaw() - straightAt)) <= 6.0f);
            Vec3 landing = CastSim.predictCastLanding(level, EYE, step.aim().yaw(), step.aim().pitch(),
                    CastSim::isWater, CastSim.DEFAULT_TICKS);
            assertNotNull(landing);
            assertTrue(Math.hypot(landing.x - target.x, landing.z - target.z) <= 1.0);
        }
    }

    @Test
    void aTargetWithNoWaterWithinABlockOrPastTheCapIsGivenUpOn() {
        // a 3x3 island two to five blocks south leaves no water within a block of its centre
        FakeCastLevel level = lake().fill(-1, 63, 3, 1, 63, 5, Blocks.STONE.defaultBlockState());
        Vec3 island = new Vec3(0.5, 63.889, 4.5);
        Vec3 far = new Vec3(7.5, 63.889, 0.5);
        Vec3 near = new Vec3(3.5, 63.889, 0.5);

        assertEquals(CastAimSearch.Status.EXHAUSTED,
                fishing(level, GENERAL.withTarget(island), new HashSet<>(), 2L).step().status());
        assertEquals(CastAimSearch.Status.EXHAUSTED,
                fishing(level, GENERAL.withTarget(far), new HashSet<>(), 2L).step().status());
        // a float already missed there
        assertEquals(CastAimSearch.Status.EXHAUSTED,
                fishing(level, GENERAL.withTarget(near), new HashSet<>(Set.of(new BlockPos(3, 63, 0))), 2L)
                        .step().status());
    }

    @Test
    void generalCellsStartAboutThreeAndAHalfOutAndInFront() {
        // a little off the block centre, which must not reorder cells the same number of blocks out
        Vec3 eye = new Vec3(0.7, 65.62, 0.35);
        List<BlockPos> cells = List.of(
                new BlockPos(1, 63, 0),
                new BlockPos(0, 63, 5),
                new BlockPos(0, 63, 4),
                new BlockPos(-3, 63, 2),
                new BlockPos(4, 63, 0),
                new BlockPos(2, 63, -3),
                new BlockPos(3, 63, 2));

        // facing east, along +x
        List<BlockPos> ordered = CastAimSearch.ordered(cells, FEET, eye, -90.0f, CastAimSearch.Order.MID_RANGE);

        assertEquals(List.of(
                new BlockPos(3, 63, 2),
                new BlockPos(2, 63, -3),
                new BlockPos(-3, 63, 2),
                new BlockPos(4, 63, 0),
                new BlockPos(0, 63, 4),
                new BlockPos(0, 63, 5),
                new BlockPos(1, 63, 0)), ordered);
    }

    @Test
    void classicCellsGoNearestFirst() {
        List<BlockPos> cells = List.of(new BlockPos(4, 63, 0), new BlockPos(-2, 63, 1), new BlockPos(1, 63, 1));

        assertEquals(List.of(new BlockPos(1, 63, 1), new BlockPos(-2, 63, 1), new BlockPos(4, 63, 0)),
                CastAimSearch.ordered(cells, FEET, EYE, 0.0f, CastAimSearch.Order.NEAREST));
    }

    @Test
    void aSwappedOrSinglePitchRangeStillSearches() {
        FakeCastLevel level = lake();
        CastAimSearch.Spec swapped = new CastAimSearch.Spec(6, false, 70.0f, 20.0f, 1.0f, 5.0,
                CastAimSearch.Order.MID_RANGE, null, 1.0, 2, 2, 2);
        CastAimSearch.Spec single = new CastAimSearch.Spec(6, false, 40.0f, 40.0f, 1.0f, 5.0,
                CastAimSearch.Order.MID_RANGE, null, 1.0, 2, 0, 0);

        assertEquals(fishing(level, GENERAL, new HashSet<>(), 5L).step(),
                fishing(level, swapped, new HashSet<>(), 5L).step());
        CastAimSearch.Step step = runUntilDone(fishing(level, single, new HashSet<>(), 5L));
        assertEquals(CastAimSearch.Status.FOUND, step.status());
        assertEquals(40.0f, step.aim().pitch());
    }
}
