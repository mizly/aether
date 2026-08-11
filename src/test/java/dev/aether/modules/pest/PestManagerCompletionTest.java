package dev.aether.modules.pest;

import dev.aether.macro.MacroState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void cleaningCanOnlyStartWhileFarming() {
        assertTrue(PestManager.canStartCleaningInState(MacroState.State.FARMING));
        assertFalse(PestManager.canStartCleaningInState(MacroState.State.REWARPING));
        assertFalse(PestManager.canStartCleaningInState(MacroState.State.CLEANING));
        assertFalse(PestManager.canStartCleaningInState(MacroState.State.RECOVERING));
        assertFalse(PestManager.canStartCleaningInState(MacroState.State.OFF));
    }
}
