package dev.aether.macro.fishing;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HotspotSpotFinderTest {
    // a hotspot in the middle of a lake whose surface sits just under a flat bank at y 64
    private static final Vec3 CENTRE = new Vec3(0.5, 63.0 + 8.0 / 9.0, 0.5);
    private static final HotspotSpotFinder.Ground FLAT_BANK = feet -> feet.getY() == 64 ? 64.0 : Double.NaN;

    private static List<BlockPos> everyCandidate(HotspotSpotFinder.Ground ground, List<Vec3> players,
                                                 Set<BlockPos> bad, Vec3 from) {
        List<BlockPos> asked = new ArrayList<>();
        HotspotSpotFinder finder = HotspotSpotFinder.of(CENTRE, ground, players, bad, from, (feet, eye) -> {
            asked.add(feet);
            return null;
        });
        assertEquals(HotspotSpotFinder.Status.EXHAUSTED, finder.step(10_000).status());
        return asked;
    }

    @Test
    void theRingRunsFromTwoToSixBlocksOut() {
        assertFalse(HotspotSpotFinder.inRingBand(1.9, 0.0));
        assertTrue(HotspotSpotFinder.inRingBand(2.0, 0.0));
        assertTrue(HotspotSpotFinder.inRingBand(3.0, 4.0));
        assertTrue(HotspotSpotFinder.inRingBand(0.0, -6.0));
        assertFalse(HotspotSpotFinder.inRingBand(4.3, 4.3));
    }

    @Test
    void theFeetStandAtMostFourBlocksOverTheSurface() {
        assertTrue(HotspotSpotFinder.standsOverSurface(64.0, CENTRE.y));
        assertTrue(HotspotSpotFinder.standsOverSurface(CENTRE.y + 4.0, CENTRE.y));
        assertFalse(HotspotSpotFinder.standsOverSurface(CENTRE.y + 4.01, CENTRE.y));
        assertFalse(HotspotSpotFinder.standsOverSurface(63.0, CENTRE.y));
    }

    @Test
    void everyCandidateLiesInTheRing() {
        List<BlockPos> asked = everyCandidate(FLAT_BANK, List.of(), Set.of(), CENTRE);

        int expected = 0;
        for (int x = -7; x <= 7; x++) {
            for (int z = -7; z <= 7; z++) {
                double distance = Math.hypot(x, z);
                if (distance >= 2.0 && distance <= 6.0) {
                    expected++;
                }
            }
        }
        assertEquals(expected, asked.size());
        for (BlockPos feet : asked) {
            assertEquals(64, feet.getY());
            double distance = Math.hypot(feet.getX() + 0.5 - CENTRE.x, feet.getZ() + 0.5 - CENTRE.z);
            assertTrue(distance >= 2.0 && distance <= 6.0, feet.toShortString());
        }
    }

    @Test
    void aBankTooHighOverTheSurfaceGivesNoSpot() {
        HotspotSpotFinder.Ground cliff = feet -> feet.getY() == 68 ? 68.0 : Double.NaN;
        assertTrue(everyCandidate(cliff, List.of(), Set.of(), CENTRE).isEmpty());

        HotspotSpotFinder.Ground ledge = feet -> feet.getY() == 67 ? 67.0 : Double.NaN;
        assertFalse(everyCandidate(ledge, List.of(), Set.of(), CENTRE).isEmpty());
    }

    @Test
    void aBlockAnotherPlayerStandsOnIsSkipped() {
        Vec3 someone = new Vec3(3.5, 64.0, 0.5);
        List<BlockPos> asked = everyCandidate(FLAT_BANK, List.of(someone), Set.of(), CENTRE);

        assertFalse(asked.contains(new BlockPos(3, 64, 0)));
        assertFalse(asked.contains(new BlockPos(4, 64, 0)));
        assertTrue(asked.contains(new BlockPos(5, 64, 0)));
        assertTrue(asked.contains(new BlockPos(4, 64, 1)));
        assertTrue(HotspotSpotFinder.crowded(new Vec3(4.5, 64.0, 0.5), List.of(someone)));
        assertFalse(HotspotSpotFinder.crowded(new Vec3(4.6, 64.0, 0.5), List.of(someone)));
    }

    @Test
    void aSpotThatFailedBeforeIsNotTriedAgain() {
        Set<BlockPos> bad = new HashSet<>(Set.of(new BlockPos(3, 64, 0)));
        assertFalse(everyCandidate(FLAT_BANK, List.of(), bad, CENTRE).contains(new BlockPos(3, 64, 0)));
    }

    @Test
    void theSpotNearestThePlayerIsTriedFirstAndTwoATick() {
        Vec3 player = new Vec3(10.5, 64.0, 0.5);
        List<BlockPos> asked = new ArrayList<>();
        BlockPos good = new BlockPos(-3, 64, 0);
        HotspotSpotFinder finder = HotspotSpotFinder.of(CENTRE, FLAT_BANK, List.of(), Set.of(), player,
                (feet, eye) -> {
                    asked.add(feet);
                    assertEquals(64.0 + 1.62, eye.y, 1.0e-9);
                    return feet.equals(good) ? new CastSim.CastAim(feet, 0.0f, 45.0f) : null;
                });

        HotspotSpotFinder.Step first = finder.step();
        assertEquals(HotspotSpotFinder.Status.WORKING, first.status());
        assertNull(first.spot());
        assertEquals(List.of(new BlockPos(6, 64, 0)), asked.subList(0, 1));
        assertEquals(2, asked.size());

        HotspotSpotFinder.Step step = first;
        while (step.status() == HotspotSpotFinder.Status.WORKING) {
            step = finder.step();
        }
        assertEquals(HotspotSpotFinder.Status.FOUND, step.status());
        assertEquals(good, step.spot());
        for (int i = 1; i < asked.size(); i++) {
            assertTrue(distance(asked.get(i - 1), player) <= distance(asked.get(i), player));
        }
    }

    @Test
    void theFloatCountsInsideTheRingOnlyCloseToTheMiddle() {
        assertTrue(HotspotSpotFinder.inRing(new Vec3(2.5, 63.9, 0.5), CENTRE, 2.0));
        assertTrue(HotspotSpotFinder.inRing(new Vec3(1.9, 70.0, 1.9), CENTRE, 2.0));
        assertFalse(HotspotSpotFinder.inRing(new Vec3(2.0, 63.9, 2.0), CENTRE, 2.0));
        assertFalse(HotspotSpotFinder.inRing(new Vec3(-1.6, 63.9, 0.5), CENTRE, 2.0));
    }

    private static double distance(BlockPos feet, Vec3 player) {
        return new Vec3(feet.getX() + 0.5, 64.0, feet.getZ() + 0.5).distanceTo(player);
    }
}
