package dev.aether.modules.session;

import dev.aether.macro.MacroState.Location;
import dev.aether.util.SkyblockLocation;
import org.junit.jupiter.api.Test;

import static dev.aether.modules.session.RecoverySequence.Action.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RecoverySequenceTest {
    private final RecoverySequence sequence = new RecoverySequence();
    private final Object world = new Object();

    @Test
    void slowSkyblockJoinRetriesInsteadOfAdvancingOnTimers() {
        sequence.reset(0);
        assertEquals(WAIT, sequence.update(0, world, Location.LOBBY, true));
        assertEquals(SKYBLOCK, sequence.update(3_000, world, Location.LOBBY, true));
        assertEquals(WAIT, sequence.update(6_000, world, Location.LOBBY, true));
        assertEquals(WAIT, sequence.update(12_999, world, Location.LOBBY, true));
        assertEquals(SKYBLOCK, sequence.update(13_000, world, Location.LOBBY, true));
        assertEquals(GARDEN, sequence.update(16_000, world, Location.HUB, true));
    }

    @Test
    void failedGardenWarpNeverResumesRegardlessOfElapsedTime() {
        sequence.reset(0);
        assertEquals(WAIT, sequence.update(0, world, Location.HUB, true));
        assertEquals(GARDEN, sequence.update(3_000, world, Location.HUB, true));
        assertEquals(WAIT, sequence.update(6_000, world, Location.HUB, true));
        for (long now = 13_000; now <= 113_000; now += 10_000) {
            assertEquals(GARDEN, sequence.update(now, world, Location.HUB, true));
        }
    }

    @Test
    void leavesLimboAndOnlyWarpsGardenAfterSkyblockIsVisible() {
        sequence.reset(0);
        assertEquals(WAIT, sequence.update(0, world, Location.LIMBO, true));
        assertEquals(LOBBY, sequence.update(3_000, world, Location.LIMBO, true));
        assertEquals(WAIT, sequence.update(6_000, world, Location.LIMBO, true));
        assertEquals(SKYBLOCK, sequence.update(7_000, world, Location.LOBBY, true));
        assertEquals(WAIT, sequence.update(9_999, world, Location.HUB, true));
        assertEquals(GARDEN, sequence.update(10_000, world, Location.HUB, true));
    }

    @Test
    void arrivingDirectlyInGardenStillWaitsForWorldAndLocationToSettle() {
        sequence.reset(0);
        assertEquals(WAIT, sequence.update(0, world, Location.GARDEN, true));
        assertEquals(WAIT, sequence.update(2_999, world, Location.GARDEN, true));
        assertEquals(RESUME, sequence.update(3_000, world, Location.GARDEN, true));
    }

    @Test
    void newWorldCannotReusePreviousGardenConfirmation() {
        sequence.reset(0);
        assertEquals(WAIT, sequence.update(0, world, Location.GARDEN, true));
        Object newWorld = new Object();
        assertEquals(WAIT, sequence.update(3_000, newWorld, Location.GARDEN, true));
        assertEquals(WAIT, sequence.update(5_999, newWorld, Location.GARDEN, true));
        assertEquals(RESUME, sequence.update(6_000, newWorld, Location.GARDEN, true));
    }

    @Test
    void missingScoreboardResetsGardenConfirmation() {
        sequence.reset(0);
        sequence.update(0, world, Location.HUB, true);
        assertEquals(GARDEN, sequence.update(3_000, world, Location.HUB, true));
        assertEquals(WAIT, sequence.update(6_000, world,
                SkyblockLocation.resolve("SKYBLOCK", false, "Area: Garden"), true));
        assertEquals(WAIT, sequence.update(6_500, world,
                SkyblockLocation.resolve(null, false, "Area: Garden"), true));
        assertEquals(WAIT, sequence.update(7_000, world, Location.GARDEN, true));
        assertEquals(WAIT, sequence.update(7_999, world, Location.GARDEN, true));
        assertEquals(RESUME, sequence.update(8_000, world, Location.GARDEN, true));
    }

    @Test
    void lobbyScoreboardWithStaleGardenTabNeverResumes() {
        sequence.reset(0);
        Location location = SkyblockLocation.resolve("HYPIXEL", false, "Area: Garden");
        assertEquals(WAIT, sequence.update(0, world, location, true));
        assertEquals(SKYBLOCK, sequence.update(3_000, world, location, true));
        assertEquals(SKYBLOCK, sequence.update(30_000, world, location, true));
    }

    @Test
    void transferBackToLobbyRestartsSkyblockJoin() {
        sequence.reset(0);
        sequence.update(0, world, Location.HUB, true);
        assertEquals(GARDEN, sequence.update(3_000, world, Location.HUB, true));
        Object lobby = new Object();
        assertEquals(WAIT, sequence.update(6_000, lobby, Location.LOBBY, true));
        assertEquals(SKYBLOCK, sequence.update(9_000, lobby, Location.LOBBY, true));
    }

    @Test
    void loadingScreensAndUnavailablePlayerOrChunksCannotResumeOrSendCommands() {
        sequence.reset(0);
        sequence.update(0, world, Location.HUB, false);
        assertEquals(WAIT, sequence.update(10_000, world, Location.HUB, false));
        assertEquals(WAIT, sequence.update(20_000, world, Location.GARDEN, false));
        assertEquals(WAIT, sequence.update(30_000, null, Location.GARDEN, true));
        assertEquals(WAIT, sequence.update(40_000, world, Location.GARDEN, true));
        assertEquals(RESUME, sequence.update(43_000, world, Location.GARDEN, true));
    }

    @Test
    void briefLoadingInterruptionRestartsGardenStabilization() {
        sequence.reset(0);
        sequence.update(0, world, Location.GARDEN, true);
        assertEquals(WAIT, sequence.update(3_000, world, Location.GARDEN, false));
        assertEquals(WAIT, sequence.update(4_000, world, Location.GARDEN, true));
        assertEquals(WAIT, sequence.update(4_999, world, Location.GARDEN, true));
        assertEquals(RESUME, sequence.update(5_000, world, Location.GARDEN, true));
    }

    @Test
    void resettingRecoveryDiscardsPreviousArrivalAndCommandHistory() {
        sequence.reset(0);
        sequence.update(0, world, Location.GARDEN, true);
        assertEquals(RESUME, sequence.update(3_000, world, Location.GARDEN, true));
        sequence.reset(30_000);
        assertEquals(WAIT, sequence.update(30_000, world, Location.GARDEN, true));
        assertEquals(RESUME, sequence.update(33_000, world, Location.GARDEN, true));
    }

    @Test
    void unknownLocationWaitsAndOtherSkyblockAreasReturnToGarden() {
        sequence.reset(0);
        assertEquals(WAIT, sequence.update(0, world, Location.UNKNOWN, true));
        assertEquals(WAIT, sequence.update(10_000, world, Location.UNKNOWN, true));
        assertEquals(GARDEN, sequence.update(11_000, world, Location.CRYSTAL_HOLLOWS, true));
    }
}
