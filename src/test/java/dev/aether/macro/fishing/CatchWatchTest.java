package dev.aether.macro.fishing;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatchWatchTest {
    @Test
    void readsTheCatchMarkerThroughDecoration() {
        assertTrue(CatchWatch.isCatchMarker(CatchWatch.stripFormatting("§c§l!!")));
        assertTrue(CatchWatch.isCatchMarker(CatchWatch.stripFormatting("  §l!!  ")));
        assertFalse(CatchWatch.isCatchMarker(CatchWatch.stripFormatting("§e§l?")));
        assertFalse(CatchWatch.isCatchMarker(""));
    }

    @Test
    void doesNotReadTheWaitingMarkerAsACatch() {
        assertTrue(CatchWatch.isBiteMarker(CatchWatch.stripFormatting("§e§l?")));
        assertFalse(CatchWatch.isBiteMarker(CatchWatch.stripFormatting("§c§l!!")));
    }

    @Test
    void doesNotConfuseAHealthPlateWithTheCatchMarker() {
        assertFalse(CatchWatch.isCatchMarker(CatchWatch.stripFormatting("§c1,000§4❤")));
    }

    @Test
    void onlyACatchThatSurfacedAfterTheReelIsTargeted() {
        Set<Integer> beforeReel = Set.of(11, 22, 33);
        assertFalse(CatchWatch.shouldAcceptTarget(22, beforeReel));
        assertTrue(CatchWatch.shouldAcceptTarget(44, beforeReel));
    }

    @Test
    void aPlateIsNeverTheHotspotTheHookTimerOrTheBiteMarker() {
        assertFalse(CatchWatch.isPlateName("HOTSPOT"));
        assertFalse(CatchWatch.isPlateName("1.5"));
        assertFalse(CatchWatch.isPlateName("12"));
        assertFalse(CatchWatch.isPlateName("!!!"));
        assertFalse(CatchWatch.isPlateName(" "));
        assertFalse(CatchWatch.isPlateName(null));
        assertTrue(CatchWatch.isPlateName("[Lv45] Stridersurfer 1,500/1,500❤"));
    }

    @Test
    void theHookTimerIsABareCountdown() {
        assertTrue(CatchWatch.isHookTimer("0.5"));
        assertTrue(CatchWatch.isHookTimer("12"));
        assertFalse(CatchWatch.isHookTimer("[Lv12] Squid"));
        assertFalse(CatchWatch.isHookTimer("!!!"));
        assertFalse(CatchWatch.isHookTimer(null));
    }

    @Test
    void aPlateFloatsJustOverItsMob() {
        Vec3 mob = new Vec3(0.5, 64.0, 0.5);
        assertTrue(CatchWatch.platesOver(mob, new Vec3(0.5, 64.0, 0.5)));
        assertTrue(CatchWatch.platesOver(mob, new Vec3(1.05, 67.0, 0.5)));
        assertFalse(CatchWatch.platesOver(mob, new Vec3(1.2, 65.0, 0.5)));
        assertFalse(CatchWatch.platesOver(mob, new Vec3(0.5, 67.1, 0.5)));
        assertFalse(CatchWatch.platesOver(mob, new Vec3(0.5, 63.9, 0.5)));
    }

    @Test
    void onlyAStandRightUnderTheHotspotIsItsBuffLine() {
        Vec3 hotspot = new Vec3(10.5, 66.0, 10.5);
        assertTrue(CatchWatch.sitsUnderHotspot(hotspot, new Vec3(10.5, 65.5, 10.5)));
        assertTrue(CatchWatch.sitsUnderHotspot(hotspot, new Vec3(10.5, 65.0, 10.5)));
        assertFalse(CatchWatch.sitsUnderHotspot(hotspot, new Vec3(10.5, 64.9, 10.5)));
        assertFalse(CatchWatch.sitsUnderHotspot(hotspot, new Vec3(10.8, 65.5, 10.5)));
        assertFalse(CatchWatch.sitsUnderHotspot(hotspot, new Vec3(10.5, 66.5, 10.5)));
    }

    @Test
    void theStandSpawnedRightAfterTheMobIsItsPlate() {
        Vec3 mob = new Vec3(0.5, 64.0, 0.5);
        List<CatchWatch.Stand> stands = List.of(
                new CatchWatch.Stand(107, new Vec3(0.5, 64.5, 0.5), "[Lv5] Flaming Worm"),
                new CatchWatch.Stand(102, new Vec3(0.5, 65.8, 0.5), "[Lv45] Stridersurfer"));
        assertEquals(102, CatchWatch.pickPlate(100, mob, stands, List.of()).id());
    }

    @Test
    void withNoStandNextInLineTheClosestOneOverTheMobIsItsPlate() {
        Vec3 mob = new Vec3(0.5, 64.0, 0.5);
        List<CatchWatch.Stand> stands = List.of(
                new CatchWatch.Stand(301, new Vec3(0.9, 65.8, 0.5), "[Lv5] Flaming Worm"),
                new CatchWatch.Stand(300, new Vec3(0.5, 65.8, 0.5), "[Lv45] Stridersurfer"),
                new CatchWatch.Stand(302, new Vec3(0.5, 67.5, 0.5), "[Lv9] Too High"));
        assertEquals(300, CatchWatch.pickPlate(100, mob, stands, List.of()).id());
    }

    @Test
    void eachMobReadsThePlateItFloatsRightUnder() {
        Vec3 left = new Vec3(0.0, 64.0, 0.0);
        Vec3 right = new Vec3(0.5, 64.0, 0.0);
        List<CatchWatch.Stand> stands = List.of(
                new CatchWatch.Stand(300, new Vec3(0.0, 65.8, 0.0), "Left"),
                new CatchWatch.Stand(301, new Vec3(0.5, 65.8, 0.0), "Right"));
        assertEquals("Left", CatchWatch.pickPlate(100, left, stands, List.of(right)).name());
        assertEquals("Right", CatchWatch.pickPlate(200, right, stands, List.of(left)).name());
    }

    @Test
    void aNeighboursPlateIsNeverBorrowed() {
        Vec3 bare = new Vec3(0.0, 64.0, 0.0);
        Vec3 named = new Vec3(0.5, 64.0, 0.0);
        List<CatchWatch.Stand> stands = List.of(new CatchWatch.Stand(301, new Vec3(0.5, 65.8, 0.0), "Right"));
        assertNull(CatchWatch.pickPlate(100, bare, stands, List.of(named)));
        // not even when that plate happens to be next in line after the bare mob
        assertNull(CatchWatch.pickPlate(300, bare, stands, List.of(named)));
    }

    @Test
    void aRiderLeavesItsMountThePlateNextInLine() {
        Vec3 mount = new Vec3(0.5, 64.0, 0.5);
        Vec3 rider = new Vec3(0.5, 65.0, 0.5);
        List<CatchWatch.Stand> stands = List.of(
                new CatchWatch.Stand(102, new Vec3(0.5, 66.4, 0.5), "[Lv45] Stridersurfer"));
        assertEquals(102, CatchWatch.pickPlate(100, mount, stands, List.of(rider)).id());
    }

    @Test
    void theHotspotItsBuffLineAndTheHookTimerAreNeverAPlate() {
        Vec3 mob = new Vec3(0.5, 64.0, 0.5);
        List<CatchWatch.Stand> stands = List.of(
                new CatchWatch.Stand(101, new Vec3(0.5, 66.0, 0.5), "HOTSPOT"),
                new CatchWatch.Stand(102, new Vec3(0.5, 65.6, 0.5), "+5 Sea Creature Chance"),
                new CatchWatch.Stand(103, new Vec3(0.6, 64.6, 0.5), "2.5"),
                new CatchWatch.Stand(104, new Vec3(0.6, 64.6, 0.5), "!!!"));
        assertNull(CatchWatch.pickPlate(100, mob, stands, List.of()));
    }

    @Test
    void theSettleDeadlineIsTheFlightPlusAGrace() {
        assertEquals(60 * 50L + 1_500L, CatchWatch.settleDeadlineMs(60));
        assertEquals(170 * 50L + 1_500L, CatchWatch.settleDeadlineMs(0));
        assertEquals(170 * 50L + 1_500L, CatchWatch.settleDeadlineMs(-3));
    }

    @Test
    void aFloatHasSettledOnceItRestsOrItsTimeIsUp() {
        long seen = 10_000L;
        long deadline = CatchWatch.settleDeadlineMs(60);
        assertFalse(CatchWatch.settled(false, false, false, seen + deadline - 1, seen, deadline));
        assertTrue(CatchWatch.settled(false, false, false, seen + deadline, seen, deadline));
        assertTrue(CatchWatch.settled(true, false, false, seen, seen, deadline));
        assertTrue(CatchWatch.settled(false, true, false, seen, seen, deadline));
        assertTrue(CatchWatch.settled(false, false, true, seen, seen, deadline));
    }

    @Test
    void aFloatBobbingJustOverTheSurfaceStillCountsAsOnIt() {
        assertTrue(CatchWatch.withinSurface(63.8, 63.89));
        assertTrue(CatchWatch.withinSurface(64.1, 63.89));
        assertFalse(CatchWatch.withinSurface(64.5, 63.89));
    }

    @Test
    void theLockedMarkerIsTheTimerNearestOurFloat() {
        Vec3 hook = new Vec3(0.5, 64.0, 0.5);
        List<CatchWatch.Stand> stands = List.of(
                new CatchWatch.Stand(10, new Vec3(1.6, 65.0, 0.5), "2.5"),
                new CatchWatch.Stand(11, new Vec3(0.6, 65.0, 0.4), "3.0"),
                new CatchWatch.Stand(12, new Vec3(0.5, 65.0, 0.5), "[Lv12] Squid"),
                new CatchWatch.Stand(13, new Vec3(0.5, 64.5, 0.5), "HOTSPOT"));
        assertEquals(11, CatchWatch.nearestMarker(hook, stands));
    }

    @Test
    void aBiteMarkerCountsWhileATimerFartherThanABlockAndAHalfDoesNot() {
        Vec3 hook = new Vec3(0.5, 64.0, 0.5);
        assertEquals(7, CatchWatch.nearestMarker(hook,
                List.of(new CatchWatch.Stand(7, new Vec3(1.5, 65.0, 1.0), "!!!"))));
        assertEquals(8, CatchWatch.nearestMarker(hook,
                List.of(new CatchWatch.Stand(8, new Vec3(0.5, 65.0, 0.5), "?"))));
        assertEquals(-1, CatchWatch.nearestMarker(hook,
                List.of(new CatchWatch.Stand(9, new Vec3(2.1, 65.0, 0.5), "1.5"))));
        assertEquals(-1, CatchWatch.nearestMarker(hook, List.of()));
    }

    @Test
    void onlyNewMobsSurfacingAtOurFloatAreCatches() {
        Vec3 hook = new Vec3(0.0, 64.0, 0.0);
        Set<Integer> beforeReel = Set.of(5);
        assertTrue(CatchWatch.isNewCatch(6, new Vec3(3.0, 64.0, 2.0), hook, 4.0, beforeReel));
        assertFalse(CatchWatch.isNewCatch(5, new Vec3(1.0, 64.0, 0.0), hook, 4.0, beforeReel));
        assertFalse(CatchWatch.isNewCatch(7, new Vec3(3.0, 64.0, 3.0), hook, 4.0, beforeReel));
        assertFalse(CatchWatch.isNewCatch(8, new Vec3(0.0, 68.5, 0.0), hook, 4.0, beforeReel));
    }
}
