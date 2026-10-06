package dev.aether.macro.fishing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StriderFishingMacroTest {
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
    void aMissedLavaAimBacksOffBeforeTryingSomewhereElse() {
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 500; i++) {
            long delay = StriderFishingMacro.nextAimRetryDelayMs(random);
            assertTrue(StriderFishingMacro.aimRetryDelayInRange(delay));
            assertTrue(delay >= 400L && delay <= 900L);
        }
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
    void stridersLeftAliveByAClearShrinkThePoolUnderTheCap() {
        assertEquals(8, StriderFishingMacro.effectiveGoal(8, 0));
        assertEquals(8, StriderFishingMacro.effectiveGoal(8, 2));
        assertEquals(7, StriderFishingMacro.effectiveGoal(8, 3));
        assertEquals(10, StriderFishingMacro.effectiveGoal(10, 0));
        assertEquals(1, StriderFishingMacro.effectiveGoal(10, 9));
        assertEquals(0, StriderFishingMacro.effectiveGoal(1, 10));
    }

    @Test
    void theCapLineIsReadWhateverItsCase() {
        assertTrue(StriderFishingMacro.isCapLine("There is not enough space for another Sea Creature!"));
        assertTrue(StriderFishingMacro.isCapLine("  THERE IS NOT ENOUGH SPACE FOR ANOTHER SEA CREATURE! "));
        assertFalse(StriderFishingMacro.isCapLine("There is not enough space in your inventory!"));
        assertFalse(StriderFishingMacro.isCapLine(null));
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
    void theYawRunCanWrapAroundTheBackOfTheCircle() {
        boolean[] lands = new boolean[180];
        for (int i = 175; i < 180; i++) {
            lands[i] = true;
        }
        for (int i = 0; i < 5; i++) {
            lands[i] = true;
        }
        lands[90] = true;
        assertEquals(0, StriderFishingMacro.bestYawIndex(lands, 1));
    }

    @Test
    void aYawRunNeedsALandingYawEitherSide() {
        boolean[] lands = new boolean[180];
        lands[40] = true;
        lands[41] = true;
        assertEquals(-1, StriderFishingMacro.bestYawIndex(lands, 1));
        lands[42] = true;
        assertEquals(41, StriderFishingMacro.bestYawIndex(lands, 1));
        assertEquals(2, StriderFishingMacro.bestYawIndex(new boolean[] {false, true, true, false}, 0));
    }

    @Test
    void theLongestYawRunWins() {
        boolean[] lands = new boolean[180];
        for (int i = 10; i < 15; i++) {
            lands[i] = true;
        }
        for (int i = 100; i < 120; i++) {
            lands[i] = true;
        }
        assertEquals(110, StriderFishingMacro.bestYawIndex(lands, 1));
    }

    @Test
    void everyYawOrNoYawLandingIsToldApart() {
        boolean[] all = new boolean[180];
        java.util.Arrays.fill(all, true);
        assertEquals(-2, StriderFishingMacro.bestYawIndex(all, 1));
        assertEquals(-1, StriderFishingMacro.bestYawIndex(new boolean[180], 1));
        assertEquals(-1, StriderFishingMacro.bestYawIndex(new boolean[0], 1));
    }

    @Test
    void aMissRulesOutTheYawsAroundItAcrossTheBackOfTheCircle() {
        boolean[] lands = new boolean[180];
        java.util.Arrays.fill(lands, true);
        StriderFishingMacro.rejectWindow(lands, 179.0f, 4.0f, 2.0f);
        for (int i = 0; i < lands.length; i++) {
            boolean ruledOut = i == 0 || i == 1 || i == 178 || i == 179;
            assertEquals(!ruledOut, lands[i], "sample " + i);
        }
    }

    @Test
    void aYawIsFiledUnderTheNearestSample() {
        assertEquals(0, StriderFishingMacro.nearestSample(179.5f, 180, 2.0f));
        assertEquals(0, StriderFishingMacro.nearestSample(-179.2f, 180, 2.0f));
        assertEquals(91, StriderFishingMacro.nearestSample(1.1f, 180, 2.0f));
        assertEquals(91, StriderFishingMacro.nearestSample(361.1f, 180, 2.0f));
        assertEquals(-178.0f, StriderFishingMacro.sampleYaw(1, 2.0f), 1e-6f);
    }

    @Test
    void aSnagMovesTheThrowToTheFarEndOfItsYawRun() {
        boolean[] lands = new boolean[180];
        for (int i = 10; i <= 20; i++) {
            lands[i] = true;
        }
        assertEquals(19, StriderFishingMacro.farthestInRun(lands, 12, 1));
        assertEquals(11, StriderFishingMacro.farthestInRun(lands, 18, 1));
        assertEquals(20, StriderFishingMacro.farthestInRun(lands, 12, 0));
    }

    @Test
    void aSnagFollowsItsYawRunAcrossTheBackOfTheCircle() {
        boolean[] lands = new boolean[180];
        for (int i = 175; i < 180; i++) {
            lands[i] = true;
        }
        for (int i = 0; i < 5; i++) {
            lands[i] = true;
        }
        assertEquals(176, StriderFishingMacro.farthestInRun(lands, 1, 1));
        assertEquals(3, StriderFishingMacro.farthestInRun(lands, 176, 1));
    }

    @Test
    void aSnagWithNowhereElseInItsRunStaysPut() {
        boolean[] lands = new boolean[180];
        lands[50] = true;
        lands[51] = true;
        lands[52] = true;
        assertEquals(-1, StriderFishingMacro.farthestInRun(lands, 51, 1));
        assertEquals(-1, StriderFishingMacro.farthestInRun(lands, 90, 1));
        java.util.Arrays.fill(lands, true);
        assertEquals(100, StriderFishingMacro.farthestInRun(lands, 10, 1));
    }

    @Test
    void theLandingTickIsTheFirstOneThatReachesTheLanding() {
        net.minecraft.world.phys.Vec3 eye = net.minecraft.world.phys.Vec3.ZERO;
        net.minecraft.world.phys.Vec3[] path = {
                new net.minecraft.world.phys.Vec3(0.0, 0.0, 0.3),
                new net.minecraft.world.phys.Vec3(0.0, 1.0, 1.0),
                new net.minecraft.world.phys.Vec3(0.0, 1.5, 1.6),
                new net.minecraft.world.phys.Vec3(0.0, 1.2, 2.0)};
        assertEquals(2, StriderFishingMacro.landingTick(path, eye, new net.minecraft.world.phys.Vec3(0.0, 1.4, 1.3)));
        assertEquals(1, StriderFishingMacro.landingTick(path, eye, new net.minecraft.world.phys.Vec3(0.0, 0.5, 0.6)));
        assertEquals(3, StriderFishingMacro.landingTick(path, eye, new net.minecraft.world.phys.Vec3(0.0, 0.0, 9.0)));
    }

    @Test
    void theLookUpTurnStaysSteepAndCloseToTheSolvedYaw() {
        java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < 500; i++) {
            float pitch = StriderFishingMacro.lookUpPitch(random);
            assertTrue(pitch >= -89.0f && pitch <= -84.0f);
            float jitter = StriderFishingMacro.jitter(random, 1.5f);
            assertTrue(Math.abs(jitter) <= 1.5f);
        }
        assertEquals(0.0f, StriderFishingMacro.jitter(random, 0.0f));
    }

    @Test
    void aWhipAimedDownLandsOnTheStairFromAnywhereTheCentringAccepts() {
        double offset = 0.12;
        for (double eye : new double[] {1.27, 1.62}) {
            for (int side = 0; side < 8; side++) {
                double angle = Math.toRadians(side * 45.0);
                double dx = Math.cos(angle) * offset;
                double dz = Math.sin(angle) * offset;
                for (float pitch = 84.0f; pitch <= 89.5f; pitch += 0.5f) {
                    for (float yaw = -180.0f; yaw < 180.0f; yaw += 15.0f) {
                        assertTrue(StriderFishingMacro.floorAimHits(dx, dz, eye, yaw, pitch),
                                "eye " + eye + " side " + side + " pitch " + pitch + " yaw " + yaw);
                    }
                }
            }
        }
    }

    @Test
    void aShallowOrUpwardLookMissesTheStair() {
        assertFalse(StriderFishingMacro.floorAimHits(0.0, 0.0, 1.62, 0.0f, 45.0f));
        assertFalse(StriderFishingMacro.floorAimHits(0.0, 0.0, 1.62, 0.0f, -10.0f));
        assertFalse(StriderFishingMacro.floorAimHits(0.45, 0.0, 1.62, -90.0f, 80.0f));
    }

    @Test
    void theStairIsTheSpotsOwnBlockOnlyWhenTheFeetStandInsideIt() {
        net.minecraft.core.BlockPos origin = new net.minecraft.core.BlockPos(-694, 120, 78);
        assertEquals(origin, StriderFishingMacro.whipFloor(origin, true));
        assertEquals(new net.minecraft.core.BlockPos(-694, 119, 78), StriderFishingMacro.whipFloor(origin, false));
    }

    @Test
    void theWhipLookStaysSteeplyDown() {
        java.util.Random random = new java.util.Random(11);
        for (int i = 0; i < 500; i++) {
            float pitch = StriderFishingMacro.whipPitch(random);
            assertTrue(pitch >= 84.0f && pitch <= 89.5f);
        }
    }
}
