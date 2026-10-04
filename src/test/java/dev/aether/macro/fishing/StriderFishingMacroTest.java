package dev.aether.macro.fishing;

import dev.aether.modules.routes.Route;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StriderFishingMacroTest {
    @Test
    void readsTheCatchMarkerThroughDecoration() {
        assertTrue(StriderFishingMacro.isCatchMarker(
                StriderFishingMacro.stripFormatting("§c§l!!")));
        assertTrue(StriderFishingMacro.isCatchMarker(
                StriderFishingMacro.stripFormatting("  §l!!  ")));
        assertFalse(StriderFishingMacro.isCatchMarker(
                StriderFishingMacro.stripFormatting("§e§l?")));
        assertFalse(StriderFishingMacro.isCatchMarker(""));
    }

    @Test
    void doesNotReadTheWaitingMarkerAsACatch() {
        assertTrue(StriderFishingMacro.isBiteMarker(
                StriderFishingMacro.stripFormatting("§e§l?")));
        assertFalse(StriderFishingMacro.isBiteMarker(
                StriderFishingMacro.stripFormatting("§c§l!!")));
    }

    @Test
    void doesNotConfuseAHealthPlateWithTheCatchMarker() {
        assertFalse(StriderFishingMacro.isCatchMarker(
                StriderFishingMacro.stripFormatting("§c1,000§4❤")));
    }

    @Test
    void approachesAndBacksOffWithoutStrafing() {
        assertEquals(1, StriderFishingMacro.followDirection(4.0, 1.5, 0));
        assertEquals(0, StriderFishingMacro.followDirection(1.5, 1.5, 0));
        assertEquals(-1, StriderFishingMacro.followDirection(0.5, 1.5, 0));
    }

    @Test
    void aMoveInProgressRunsToTheKillDistanceInsteadOfTheBandEdge() {
        assertEquals(1, StriderFishingMacro.followDirection(1.7, 1.5, 1));
        assertEquals(0, StriderFishingMacro.followDirection(1.4, 1.5, 1));
        assertEquals(-1, StriderFishingMacro.followDirection(1.3, 1.5, -1));
        assertEquals(0, StriderFishingMacro.followDirection(1.6, 1.5, -1));
    }

    @Test
    void anOvershootInsideTheBandDoesNotAnswerWithTheOppositeKey() {
        assertEquals(0, StriderFishingMacro.followDirection(1.3, 1.5, 0));
        assertEquals(0, StriderFishingMacro.followDirection(1.7, 1.5, 0));
    }

    @Test
    void theSwingCadenceStaysBetweenThreeAndSixClicksASecond() {
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 500; i++) {
            long delay = StriderFishingMacro.nextAttackDelayMs(random);
            assertTrue(StriderFishingMacro.attackDelayInRange(delay));
            double cps = 1000.0 / delay;
            assertTrue(cps >= 3.0 && cps <= 6.0);
        }
    }

    @Test
    void theWayHomeStartsJustAfterTheCatchDies() {
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 500; i++) {
            long delay = StriderFishingMacro.nextReturnDelayMs(random);
            assertTrue(StriderFishingMacro.returnDelayInRange(delay));
            assertTrue(delay >= 25L && delay <= 80L);
        }
    }

    @Test
    void lootInsteadOfAMobPutsTheLineStraightBackOut() {
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 500; i++) {
            long delay = StriderFishingMacro.nextEmptyCatchDelayMs(random);
            assertTrue(StriderFishingMacro.emptyCatchDelayInRange(delay));
            assertTrue(delay >= 150L && delay <= 400L);
        }
    }

    @Test
    void theCursorSitsOffCentreOnTheFloatModel() {
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        boolean sawOffCentre = false;
        for (int i = 0; i < 500; i++) {
            net.minecraft.world.phys.Vec3 offset = StriderFishingMacro.aimBoxOffset(random);
            assertTrue(StriderFishingMacro.aimBoxOffsetInRange(offset));
            sawOffCentre |= offset.length() > 0.02;
        }
        assertTrue(sawOffCentre);
    }

    @Test
    void standingOnTheStartBlockNeedsNoRouteBackToIt() {
        assertTrue(StriderFishingMacro.withinOriginBlock(0.0, 0.0, 0.0));
        // the corners and lip of the block itself
        assertTrue(StriderFishingMacro.withinOriginBlock(0.5, 0.0, 0.5));
        assertTrue(StriderFishingMacro.withinOriginBlock(-0.5, 0.0, 0.5));
        // a hair above it, mid hop out of the lava
        assertTrue(StriderFishingMacro.withinOriginBlock(0.0, 0.9, 0.0));
    }

    @Test
    void theNextBlockOverStillEarnsARouteHome() {
        assertFalse(StriderFishingMacro.withinOriginBlock(0.8, 0.0, 0.0));
        assertFalse(StriderFishingMacro.withinOriginBlock(0.0, 0.0, -0.8));
        assertFalse(StriderFishingMacro.withinOriginBlock(0.0, 1.5, 0.0));
        assertFalse(StriderFishingMacro.withinOriginBlock(0.0, -0.9, 0.0));
    }

    @Test
    void aMissedLavaAimBacksOffBeforeTryingSomewhereElse() {
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 500; i++) {
            long delay = StriderFishingMacro.nextAimRetryDelayMs(random);
            assertTrue(StriderFishingMacro.aimRetryDelayInRange(delay));
            assertTrue(delay >= 400L && delay <= 900L);
        }
    }

    @Test
    void aRefusedRouteBacksOffBeforeTryingAgain() {
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 500; i++) {
            long delay = StriderFishingMacro.nextReturnRetryDelayMs(random);
            assertTrue(StriderFishingMacro.returnRetryDelayInRange(delay));
            // long enough that the jump can lift us out of lava before the next plan
            assertTrue(delay >= 500L && delay <= 900L);
        }
    }

    @Test
    void etherwarpOnlyEarnsItsKeepFromFourBlocksOut() {
        assertFalse(StriderFishingMacro.shouldEtherwarp(3.9, false, true));
        assertTrue(StriderFishingMacro.shouldEtherwarp(4.0, false, true));
        assertFalse(StriderFishingMacro.shouldEtherwarp(40.0, false, false));
    }

    @Test
    void lavaIsWarpedOutOfAsSoonAsThereIsAnywhereToGo() {
        assertFalse(StriderFishingMacro.shouldEtherwarp(0.9, true, true));
        assertTrue(StriderFishingMacro.shouldEtherwarp(1.0, true, true));
        assertTrue(StriderFishingMacro.shouldEtherwarp(2.0, true, true));
    }

    @Test
    void onlyACatchThatSurfacedAfterTheReelIsTargeted() {
        java.util.Set<Integer> beforeReel = java.util.Set.of(11, 22, 33);
        assertFalse(StriderFishingMacro.shouldAcceptTarget(22, beforeReel));
        assertTrue(StriderFishingMacro.shouldAcceptTarget(44, beforeReel));
    }

    @Test
    void theCursorSettlesOntoTheFloatSoonAfterItLands() {
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 500; i++) {
            long delay = StriderFishingMacro.nextFirstIdleDelayMs(random);
            assertTrue(StriderFishingMacro.firstIdleDelayInRange(delay));
            assertTrue(delay < StriderFishingMacro.nextIdleDelayMs(random));
        }
    }

    @Test
    void theGlanceAtTheFloatIsAFlickRatherThanAGlide() {
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 500; i++) {
            long turn = StriderFishingMacro.nextIdleTurnMs(random);
            assertTrue(StriderFishingMacro.idleTurnInRange(turn));
            assertTrue(turn >= 100L && turn <= 220L);
        }
    }

    @Test
    void theIdleDriftStaysSmall() {
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 500; i++) {
            assertTrue(StriderFishingMacro.idleDelayInRange(StriderFishingMacro.nextIdleDelayMs(random)));
            assertTrue(Math.abs(StriderFishingMacro.driftDegrees(random, 2.5f)) <= 2.5f);
        }
    }

    @Test
    void theSwimOutOfLavaWaitsABeatBeforeHoldingJump() {
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 500; i++) {
            long delay = StriderFishingMacro.nextLiquidJumpDelayMs(random);
            assertTrue(StriderFishingMacro.liquidJumpDelayInRange(delay));
            assertTrue(delay >= 100L && delay <= 300L);
        }
    }

    @Test
    void jumpIsHeldOnlyOnceTheSinkingBeatHasPassed() {
        assertFalse(StriderFishingMacro.shouldHoldLiquidJump(true, 1_000L, 1_200L));
        assertTrue(StriderFishingMacro.shouldHoldLiquidJump(true, 1_200L, 1_200L));
        assertTrue(StriderFishingMacro.shouldHoldLiquidJump(true, 9_000L, 1_200L));
    }

    @Test
    void dryLandNeverHoldsTheJumpKey() {
        assertFalse(StriderFishingMacro.shouldHoldLiquidJump(false, 9_000L, 1_200L));
        assertFalse(StriderFishingMacro.shouldHoldLiquidJump(true, 9_000L, 0L));
    }

    @Test
    void sneakIsDroppedInLiquidUnlessItIsAskedToContinue() {
        assertTrue(StriderFishingMacro.sneakAllowedInLiquid(false, false));
        assertFalse(StriderFishingMacro.sneakAllowedInLiquid(true, false));
        assertTrue(StriderFishingMacro.sneakAllowedInLiquid(true, true));
        assertTrue(StriderFishingMacro.sneakAllowedInLiquid(false, true));
    }
    @Test
    void thePoolIsClearedOnlyOnceItHoldsTheChosenCount() {
        assertFalse(StriderFishingMacro.soulWhipGoalReached(4, 5));
        assertTrue(StriderFishingMacro.soulWhipGoalReached(5, 5));
        assertTrue(StriderFishingMacro.soulWhipGoalReached(21, 20));
    }

    @Test
    void theWeaponSwapLandsInsideTheConfiguredWindowPlusTheOddFumble() {
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        boolean sawVariety = false;
        long first = StriderFishingMacro.nextWhipSwapDelayMs(random, 40, 130);
        for (int i = 0; i < 2000; i++) {
            long delay = StriderFishingMacro.nextWhipSwapDelayMs(random, 40, 130);
            assertTrue(StriderFishingMacro.whipSwapDelayInRange(delay, 40, 130));
            assertTrue(delay >= 40L && delay <= 220L);
            sawVariety |= delay != first;
        }
        assertTrue(sawVariety);
    }

    @Test
    void swappedSwapBoundsStillProduceAValidDelay() {
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 500; i++) {
            long delay = StriderFishingMacro.nextWhipSwapDelayMs(random, 130, 40);
            assertTrue(StriderFishingMacro.whipSwapDelayInRange(delay, 40, 130));
        }
    }

    @Test
    void theWhipIsDrawnABeatBeforeTheClickAndSwungOnALooseRhythm() {
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 500; i++) {
            long draw = StriderFishingMacro.nextWhipDrawDelayMs(random);
            assertTrue(StriderFishingMacro.whipDrawDelayInRange(draw));
            assertTrue(draw >= 45L);
            assertTrue(StriderFishingMacro.whipIntervalInRange(StriderFishingMacro.nextWhipIntervalMs(random)));
        }
    }

    @Test
    void theWhipOnlyFiresOnceTheCrosshairIsNearTheStrider() {
        assertTrue(StriderFishingMacro.aimWithin(10.0f, 20.0f, 14.0f, 24.0f, 6.0f));
        assertFalse(StriderFishingMacro.aimWithin(10.0f, 20.0f, 30.0f, 20.0f, 6.0f));
        // yaw wraps, so 179 and -179 are two degrees apart
        assertTrue(StriderFishingMacro.aimWithin(179.0f, 0.0f, -179.0f, 0.0f, 6.0f));
    }
    @Test
    void onlyRememberedStridersThatAreStillThereCountTowardThePool() {
        java.util.Set<Integer> remembered = new java.util.LinkedHashSet<>(java.util.List.of(1, 2, 3, 4, 5));
        java.util.Set<Integer> kept = StriderFishingMacro.stillPooled(remembered, true, id -> id != 3);
        assertEquals(java.util.Set.of(1, 2, 4, 5), kept);
    }

    @Test
    void aNewLobbyForgetsThePool() {
        java.util.Set<Integer> remembered = java.util.Set.of(1, 2, 3);
        assertTrue(StriderFishingMacro.stillPooled(remembered, false, id -> true).isEmpty());
    }
    @Test
    void theWhipIsGivenUpOnAfterEnoughSwingsOrTime() {
        assertFalse(StriderFishingMacro.whipFailing(5, 7_999L));
        assertTrue(StriderFishingMacro.whipFailing(6, 1_000L));
        assertTrue(StriderFishingMacro.whipFailing(2, 8_000L));
    }

    @Test
    void aStriderStillInThePoolIsLeftToTheWhip() {
        net.minecraft.world.phys.Vec3 home = new net.minecraft.world.phys.Vec3(0.5, 64.0, 0.5);
        net.minecraft.world.phys.Vec3 last = new net.minecraft.world.phys.Vec3(3.0, 64.0, 2.0);
        net.minecraft.world.phys.Vec3 now = new net.minecraft.world.phys.Vec3(3.3, 64.0, 2.4);
        assertFalse(StriderFishingMacro.escapedCage(last, now, home));
        assertFalse(StriderFishingMacro.escapedCage(null, now, home));
    }

    @Test
    void aTeleportedOrStrayStriderIsKilledByHand() {
        net.minecraft.world.phys.Vec3 home = new net.minecraft.world.phys.Vec3(0.5, 64.0, 0.5);
        net.minecraft.world.phys.Vec3 inPool = new net.minecraft.world.phys.Vec3(3.0, 64.0, 2.0);
        // a jump inside the radius still counts, since striders cannot move that far in a tick
        assertTrue(StriderFishingMacro.escapedCage(inPool,
                new net.minecraft.world.phys.Vec3(-2.0, 64.0, -2.0), home));
        assertTrue(StriderFishingMacro.escapedCage(null,
                new net.minecraft.world.phys.Vec3(9.0, 64.0, 0.5), home));
    }

    @Test
    void theSawyerSpotRouteIsOneWalkOntoTheSpot() {
        Route route = StriderFishingMacro.fixedSpotRoute("");

        assertEquals(List.of(new Route.Waypoint(-694, 120, 78, Route.LegType.WALK)), route.waypoints());
        assertFalse(route.hasWarp());
        assertEquals("/warp galatea", StriderFishingMacro.fixedSpotRoute("galatea").warpCommand());
    }

    @Test
    void aRestartAlwaysWarpsToGalateaBeforeWalkingToTheSawyerSpot() {
        assertEquals("galatea", StriderFishingMacro.fixedSpotWarp(true, true, 0.0));
        assertEquals("galatea", StriderFishingMacro.fixedSpotWarp(true, false, 500.0));
    }

    @Test
    void aStartOnlyWalksStraightToTheSawyerSpotFromCloseByOnGalatea() {
        assertEquals("", StriderFishingMacro.fixedSpotWarp(false, true, 0.0));
        assertEquals("", StriderFishingMacro.fixedSpotWarp(false, true, 96.0));
        assertEquals("galatea", StriderFishingMacro.fixedSpotWarp(false, true, 96.5));
        assertEquals("galatea", StriderFishingMacro.fixedSpotWarp(false, false, 10.0));
    }
}
