package dev.aether.telemetry.ban;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BanDetectionPipelineTest {
    private static final String PLAY_BAN = """
            You are temporarily banned for 15m 38s from this server!

            Reason: Cheating through the use of unfair game advantages.
            Find out more: https://www.hypixel.net/appeal

            Ban ID: #a1b2c3d4""";

    private static BanReport reportFor(boolean macroActive, boolean failsafeRecent) {
        DetectedBan ban = HypixelBanDetector.detect(PLAY_BAN);
        assertNotNull(ban);
        return BanReport.of(
                BanDetectionGate.SERVER_ID,
                ban,
                new BanDisconnectContext(macroActive, failsafeRecent, failsafeRecent, ""));
    }

    @Test
    void loginStageBanNeverPassesTheGate() {
        assertFalse(BanDetectionGate.allowsDetection(false, false, false, "mc.hypixel.net"));
        assertFalse(BanDetectionGate.allowsDetection(false, true, false, "mc.hypixel.net"));
    }

    @Test
    void establishedHypixelPlayDisconnectPassesTheGate() {
        assertTrue(BanDetectionGate.allowsDetection(true, true, false, "mc.hypixel.net"));
        assertTrue(BanDetectionGate.allowsDetection(true, true, false, "HYPIXEL.NET:25565"));
        assertFalse(BanDetectionGate.allowsDetection(true, false, false, "mc.hypixel.net"));
        assertFalse(BanDetectionGate.allowsDetection(true, true, true, "mc.hypixel.net"));
        assertFalse(BanDetectionGate.allowsDetection(true, true, false, "play.example.net"));
        assertFalse(BanDetectionGate.allowsDetection(true, true, false, "nothypixel.net"));
        assertFalse(BanDetectionGate.allowsDetection(true, true, false, ""));
    }

    @Test
    void realBanWhileMacroRunningReportsMacroActive() {
        BanReport report = reportFor(true, true);

        assertTrue(report.macroActiveAtDisconnect());
        assertTrue(report.failsafeTriggeredWithinLast5Minutes());
        assertEquals("hypixel", report.server());
        assertEquals("15m 38s", report.duration());
        assertTrue(report.toJson().get("macro_active").getAsBoolean());
        assertTrue(report.toJson().get("failsafe_recent").getAsBoolean());
    }

    @Test
    void realBanWhileMacroOffReportsMacroInactive() {
        BanReport report = reportFor(false, false);

        assertFalse(report.macroActiveAtDisconnect());
        assertFalse(report.toJson().get("macro_active").getAsBoolean());
        assertFalse(report.toJson().get("failsafe_recent").getAsBoolean());
    }

    @Test
    void reportKeepsPreDisconnectMacroStateAfterShutdownTurnsTheMacroOff() {
        boolean[] macroRunning = {true};
        BanDisconnectContext context = BanDisconnectContext.capture(macroRunning[0], 1_000_000_000L);

        macroRunning[0] = false; // stopMacro() runs after the snapshot

        DetectedBan ban = HypixelBanDetector.detect(PLAY_BAN);
        assertNotNull(ban);
        BanReport report = BanReport.of(BanDetectionGate.SERVER_ID, ban, context);
        assertTrue(report.macroActiveAtDisconnect());
        assertFalse(macroRunning[0]);
    }

    @Test
    void sameDisconnectObservedTwiceReportsOnce() {
        BanReportDeduplicator dedup = new BanReportDeduplicator();
        BanReport report = reportFor(true, false);

        assertTrue(dedup.accept(report, 1_000_000_000L));
        assertFalse(dedup.accept(report, 1_000_100_000L));
    }

    @Test
    void aLaterUnrelatedBanIsStillReported() {
        BanReportDeduplicator dedup = new BanReportDeduplicator();
        BanReport first = reportFor(true, false);
        BanReport second = new BanReport("hypixel", false, "Cheating.", "", true, false);

        assertTrue(dedup.accept(first, 1_000_000_000L));
        assertTrue(dedup.accept(second, 1_000_100_000L));
    }
}
