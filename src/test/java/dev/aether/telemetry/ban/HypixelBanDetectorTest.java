package dev.aether.telemetry.ban;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HypixelBanDetectorTest {
    private static final String TEMPORARY_BAN = """
            You are temporarily banned for 15m 38s from this server!

            Reason: Cheating through the use of unfair game advantages.
            Find out more: https://www.hypixel.net/appeal

            Ban ID: #a1b2c3d4
            Sharing your Ban ID may affect the processing of your appeal!""";

    private static final String PERMANENT_BAN = """
            You are permanently banned from this server!

            Reason: Cheating through the use of unfair game advantages.
            Find out more: https://www.hypixel.net/appeal

            Ban ID: #ffffffff""";

    private static final String SIMULATED_BAN = """
            You are temporarily banned for 4m 12s from this server!

            Reason: Cheating through the use of unfair game advantages.
            Find out more: https://www.hypixel.net/appeal

            Ban ID: #AETHERSB
            Sharing your Ban ID may affect the processing of your appeal!""";

    @Test
    void parsesTemporaryHypixelBan() {
        DetectedBan ban = HypixelBanDetector.detect(TEMPORARY_BAN);

        assertNotNull(ban);
        assertTrue(ban.temporary());
        assertEquals("15m 38s", ban.duration());
        assertEquals("Cheating through the use of unfair game advantages.", ban.reason());
        assertFalse(ban.simulated());
    }

    @Test
    void parsesPermanentHypixelBanWithoutDuration() {
        DetectedBan ban = HypixelBanDetector.detect(PERMANENT_BAN);

        assertNotNull(ban);
        assertFalse(ban.temporary());
        assertEquals("", ban.duration());
        assertEquals("Cheating through the use of unfair game advantages.", ban.reason());
        assertFalse(ban.simulated());
    }

    @Test
    void recognisesDynamicRestSimulatedBan() {
        DetectedBan ban = HypixelBanDetector.detect(SIMULATED_BAN);

        assertNotNull(ban);
        assertTrue(ban.temporary());
        assertTrue(ban.simulated());
        assertEquals("4m 12s", ban.duration());
    }

    @Test
    void ignoresGenericPlayDisconnect() {
        assertNull(HypixelBanDetector.detect("Disconnected"));
        assertNull(HypixelBanDetector.detect("Timed out"));
        assertNull(HypixelBanDetector.detect("Connection lost"));
        assertNull(HypixelBanDetector.detect("A disconnect occurred: Server restarting"));
        assertNull(HypixelBanDetector.detect("Failed to connect to the server"));
        assertNull(HypixelBanDetector.detect("Failed to verify username!"));
        assertNull(HypixelBanDetector.detect(""));
        assertNull(HypixelBanDetector.detect(null));
    }

    @Test
    void ignoresNormalKick() {
        assertNull(HypixelBanDetector.detect("You were kicked from the server: AFK"));
        assertNull(HypixelBanDetector.detect("Kicked for spamming!"));
    }

    @Test
    void ignoresBannedWordingThatIsNotAPunishmentScreen() {
        assertNull(HypixelBanDetector.detect("You are banned from using chat in this lobby."));
        assertNull(HypixelBanDetector.detect("Your guild has been banned from the event."));
        // Ban wording but no Hypixel punishment marker at all.
        assertNull(HypixelBanDetector.detect("You are permanently banned from this server!"));
    }

    @Test
    void toleratesFormattingCodesExtraSpacingAndWrappedLines() {
        String messy = "  §c§lYOU   ARE    TEMPORARILY   BANNED  FOR   1d 2h 3m 4s  FROM  THIS  SERVER! \n\n\n"
                + "§7reason:\n"
                + "§fUsing an unfair advantage.\n\n"
                + "   §7find out more:  §bhttps://www.hypixel.net/appeal  \n\n"
                + "§7BAN   ID :   #§fdeadbeef  \n";

        DetectedBan ban = HypixelBanDetector.detect(messy);

        assertNotNull(ban);
        assertTrue(ban.temporary());
        assertEquals("1d 2h 3m 4s", ban.duration());
        assertEquals("Using an unfair advantage.", ban.reason());
        assertFalse(ban.simulated());
    }

    @Test
    void detectorNeverExposesTheBanId() {
        DetectedBan ban = HypixelBanDetector.detect(TEMPORARY_BAN);

        assertNotNull(ban);
        assertFalse(ban.toString().contains("a1b2c3d4"));
        assertFalse(BanReport.of("hypixel", ban, new BanDisconnectContext(true, true, true, ""))
                .toJson().toString().contains("a1b2c3d4"));
    }
}
