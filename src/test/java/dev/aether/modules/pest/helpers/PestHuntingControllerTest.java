package dev.aether.modules.pest.helpers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PestHuntingControllerTest {
    @Test
    void readsReelPromptFromEitherNameRepresentation() {
        assertEquals("REEL", PestHuntingController.stripFormatting("§e§lREEL"));
        assertEquals("REEL", PestHuntingController.stripFormatting("REEL"));
        assertEquals("REEL", PestHuntingController.stripFormatting("§e§lREEL§r"));
        assertEquals("REEL", PestHuntingController.stripFormatting("  §lREEL  "));
    }

    @Test
    void doesNotConfuseTheStaminaBarWithTheReelPrompt() {
        assertNotEquals("REEL",
                PestHuntingController.stripFormatting("§a§l§m          "));
    }

    @Test
    void readsTheReelPromptThroughDecoration() {
        assertTrue(PestHuntingController.isReelPrompt(
                PestHuntingController.stripFormatting("§e§lREEL!")));
        assertTrue(PestHuntingController.isReelPrompt("REEL"));
        assertFalse(PestHuntingController.isReelPrompt(
                PestHuntingController.stripFormatting("§c1,000§4❤")));
        assertFalse(PestHuntingController.isReelPrompt(""));
    }

    @Test
    void aLandedLassoStopsBuyingTheTightThrowCorrection() {
        assertEquals(10.0f, PestHuntingController.huntAimTolerance(
                false, PestHuntingController.Stage.THROW));
        assertEquals(18.0f, PestHuntingController.huntAimTolerance(
                true, PestHuntingController.Stage.THROW));
        assertEquals(18.0f, PestHuntingController.huntAimTolerance(
                false, PestHuntingController.Stage.REEL));
    }

    @Test
    void theAimBlendStartsOnTheOldPointAndDecaysToNothing() {
        assertEquals(1.0, PestHuntingController.aimBlendRemaining(0L));
        assertTrue(PestHuntingController.aimBlendRemaining(210L) > 0.4);
        assertTrue(PestHuntingController.aimBlendRemaining(210L) < 0.6);
        assertEquals(0.0, PestHuntingController.aimBlendRemaining(420L));
        assertEquals(0.0, PestHuntingController.aimBlendRemaining(5_000L));
    }

    @Test
    void aFocusFlickerHasToPersistBeforeItMovesTheCamera() {
        assertFalse(PestHuntingController.adoptsPendingFocus(true, 1_000L, 1_150L));
        assertTrue(PestHuntingController.adoptsPendingFocus(true, 1_000L, 1_200L));
    }

    @Test
    void aLostFocusIsReplacedWithoutWaitingOutTheDebounce() {
        assertTrue(PestHuntingController.adoptsPendingFocus(false, 1_000L, 1_000L));
    }

    @Test
    void followMovementHoldsWhileAnInteractionIsReady() {
        assertEquals(0, PestHuntingController.followDirection(20.0, 6.0, false, false, 0));
    }

    @Test
    void followMovementApproachesAndBacksOffWithoutStrafing() {
        assertEquals(1, PestHuntingController.followDirection(8.0, 6.0, false, true, 0));
        assertEquals(0, PestHuntingController.followDirection(6.0, 6.0, false, true, 0));
        assertEquals(-1, PestHuntingController.followDirection(4.0, 6.0, false, true, 0));
        assertEquals(0, PestHuntingController.followDirection(8.0, 6.0, true, true, 0));
    }

    @Test
    void aMoveInProgressRunsToTheFollowDistanceInsteadOfTheBandEdge() {
        assertEquals(1, PestHuntingController.followDirection(6.5, 6.0, false, true, 1));
        assertEquals(0, PestHuntingController.followDirection(5.9, 6.0, false, true, 1));
        assertEquals(-1, PestHuntingController.followDirection(5.5, 6.0, false, true, -1));
        assertEquals(0, PestHuntingController.followDirection(6.1, 6.0, false, true, -1));
    }

    @Test
    void anOvershootPastTheFollowDistanceDoesNotAnswerWithTheOppositeKey() {
        assertEquals(0, PestHuntingController.followDirection(5.5, 6.0, false, true, 0));
    }
}
