package dev.aether.modules.failsafe;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FishingFailsafeRulesTest {

    @Test
    void onlyVersionFourUuidsCountAsRealPlayers() {
        assertTrue(PlayerNearbyFailsafe.isRealPlayerUuid(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")));
        assertFalse(PlayerNearbyFailsafe.isRealPlayerUuid(UUID.fromString("0a6c5c1e-3f2d-2b1a-9c8d-7e6f5a4b3c2d")));
        assertFalse(PlayerNearbyFailsafe.isRealPlayerUuid(null));
    }

    @Test
    void playerHasToStayForTheWholeConfiguredTime() {
        assertFalse(PlayerNearbyFailsafe.hasStayedLongEnough(1_000L, 10_999L, 10_000L));
        assertTrue(PlayerNearbyFailsafe.hasStayedLongEnough(1_000L, 11_000L, 10_000L));
        assertFalse(PlayerNearbyFailsafe.hasStayedLongEnough(0L, 50_000L, 10_000L));
    }

    @Test
    void aJumpPastTheThresholdIsATeleport() {
        assertFalse(TeleportFailsafe.isTeleport(3.9, 5.0));
        assertTrue(TeleportFailsafe.isTeleport(5.0, 5.0));
        assertTrue(TeleportFailsafe.isTeleport(42.0, 5.0));
    }

    @Test
    void anOwnWarpWindowExcusesTheJump() {
        assertTrue(TeleportFailsafe.isOwnMovement(false, false, 1_000L, 2_000L));
        assertFalse(TeleportFailsafe.isOwnMovement(false, false, 2_000L, 2_000L));
        assertTrue(TeleportFailsafe.isOwnMovement(true, false, 5_000L, 0L));
        assertTrue(TeleportFailsafe.isOwnMovement(false, true, 5_000L, 0L));
    }

    @Test
    void restartActionParsesFromConfig() {
        assertEquals(FailsafeAction.RESTART, FailsafeAction.fromConfig("restart"));
        assertEquals(FailsafeAction.STOP, FailsafeAction.fromConfig("bogus"));
    }
}
