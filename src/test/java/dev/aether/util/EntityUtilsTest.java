package dev.aether.util;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityUtilsTest {
    private static final UUID REAL = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    @Test
    void onlyVersionFourUuidsCountAsRealPlayers() {
        assertTrue(EntityUtils.isRealPlayer(REAL, true));
        assertFalse(EntityUtils.isRealPlayer(UUID.fromString("0a6c5c1e-3f2d-2b1a-9c8d-7e6f5a4b3c2d"), true));
        assertFalse(EntityUtils.isRealPlayer(null, true));
    }

    @Test
    void aRandomUuidMissingFromTheTabListIsNoRealPlayer() {
        assertFalse(EntityUtils.isRealPlayer(REAL, false));
    }
}
