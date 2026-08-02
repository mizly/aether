package dev.aether.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BazaarUtilsTest {
    @Test
    void detectsInstantSellCompletionFromChatOrReturnedMenu() {
        assertTrue(BazaarUtils.isInstantSellFinished(true, false, "Are you sure?"));
        assertTrue(BazaarUtils.isInstantSellFinished(false, true, "Are you sure?"));
        assertTrue(BazaarUtils.isInstantSellFinished(false, false, "Bazaar ➜ Woods & Fishes"));
        assertFalse(BazaarUtils.isInstantSellFinished(false, false, "Are you sure?"));
    }
}
