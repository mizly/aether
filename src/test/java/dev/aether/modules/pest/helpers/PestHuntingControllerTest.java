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
    void followMovementHoldsWhileAnInteractionIsReady() {
        assertEquals(0, PestHuntingController.followDirection(20.0, 6.0, false, false));
    }

    @Test
    void followMovementApproachesAndBacksOffWithoutStrafing() {
        assertEquals(1, PestHuntingController.followDirection(8.0, 6.0, false, true));
        assertEquals(0, PestHuntingController.followDirection(6.0, 6.0, false, true));
        assertEquals(-1, PestHuntingController.followDirection(4.0, 6.0, false, true));
        assertEquals(0, PestHuntingController.followDirection(8.0, 6.0, true, true));
    }
}
