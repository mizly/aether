package dev.aether.modules.failsafe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SelfTeleportWindowTest {
    @Test
    void doesNotSuppressUnrequestedTeleports() {
        assertFalse(new SelfTeleportWindow().onPositionPacket(1000, 1500));
    }

    @Test
    void allowsSlowRewarpResponseAndTimesSettlePeriodFromArrival() {
        SelfTeleportWindow window = new SelfTeleportWindow();
        window.request(1000);
        assertTrue(window.onPositionPacket(4000, 1500));
        assertTrue(window.onPositionPacket(5499, 1500));
        assertFalse(window.onPositionPacket(5500, 1500));
    }

    @Test
    void correctionsDoNotExtendSettlePeriodIndefinitely() {
        SelfTeleportWindow window = new SelfTeleportWindow();
        window.request(1000);
        assertTrue(window.onPositionPacket(1100, 1500));
        assertTrue(window.onPositionPacket(2599, 1500));
        assertFalse(window.onPositionPacket(2600, 1500));
    }

    @Test
    void zeroSettleStillAllowsRequestedTeleportButNoFollowupCorrections() {
        SelfTeleportWindow window = new SelfTeleportWindow();
        window.request(1000);
        assertTrue(window.onPositionPacket(1100, 0));
        assertFalse(window.onPositionPacket(1101, 0));
    }

    @Test
    void failedCommandAndResetDoNotLeaveProtectionDisabled() {
        SelfTeleportWindow window = new SelfTeleportWindow();
        window.request(1000);
        assertFalse(window.onPositionPacket(11000, 1500));
        window.request(12000);
        window.reset();
        assertFalse(window.onPositionPacket(12001, 1500));
    }
}
