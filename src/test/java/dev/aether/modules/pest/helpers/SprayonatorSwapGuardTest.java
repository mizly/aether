package dev.aether.modules.pest.helpers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SprayonatorSwapGuardTest {
    @Test
    void keepsTheHoeSelectedForThreeTicksAfterReleasingAttack() {
        SprayonatorSwapGuard guard = new SprayonatorSwapGuard();
        assertFalse(guard.readyToSwap(100, true));
        assertFalse(guard.readyToSwap(101, false));
        assertFalse(guard.readyToSwap(102, false));
        assertTrue(guard.readyToSwap(103, false));
    }

    @Test
    void repeatedWorkerPollsCannotReplaceClientTicks() {
        SprayonatorSwapGuard guard = new SprayonatorSwapGuard();
        for (int poll = 0; poll < 100; poll++) {
            assertFalse(guard.readyToSwap(100, false));
        }
        assertFalse(guard.readyToSwap(102, false));
        assertTrue(guard.readyToSwap(103, false));
    }

    @Test
    void renewedHeldOrQueuedAttackRestartsTheReleaseWindow() {
        SprayonatorSwapGuard guard = new SprayonatorSwapGuard();
        assertFalse(guard.readyToSwap(100, true));
        assertFalse(guard.readyToSwap(102, false));
        assertFalse(guard.readyToSwap(103, true));
        assertFalse(guard.readyToSwap(105, false));
        assertTrue(guard.readyToSwap(106, false));
    }

    @Test
    void continuousAttackNeverAllowsTheSwap() {
        SprayonatorSwapGuard guard = new SprayonatorSwapGuard();
        for (int tick = 100; tick < 200; tick++) {
            assertFalse(guard.readyToSwap(tick, true));
        }
    }

    @Test
    void playerTickResetStartsANewReleaseWindow() {
        SprayonatorSwapGuard guard = new SprayonatorSwapGuard();
        assertFalse(guard.readyToSwap(100, true));
        assertFalse(guard.readyToSwap(0, false));
        assertFalse(guard.readyToSwap(2, false));
        assertTrue(guard.readyToSwap(3, false));
    }
}
