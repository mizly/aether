package dev.aether.modules.pest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PestManagerCompletionTest {
    @Test
    void estimatedCompletionUsesEffectiveCount() {
        assertEquals(3, PestManager.selectCompletionAliveCount(true, 6, 3));
    }

    @Test
    void tabOnlyCompletionIgnoresEstimate() {
        assertEquals(6, PestManager.selectCompletionAliveCount(false, 6, 3));
        assertEquals(0, PestManager.selectCompletionAliveCount(false, -1, 3));
    }

    @Test
    void chatSpawnDoesNotDoubleCountPestsAlreadyShownInTab() {
        assertEquals(6, PestManager.reconcileChatSpawnCount(6, 6, 6));
    }

    @Test
    void chatSpawnRemainsFallbackWhenTabCountIsUnavailable() {
        assertEquals(12, PestManager.reconcileChatSpawnCount(6, 6, -1));
    }
}
