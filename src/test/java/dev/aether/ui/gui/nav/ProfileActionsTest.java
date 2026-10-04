package dev.aether.ui.gui.nav;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aether.config.AetherConfig;
import dev.aether.config.ConfigProfileManager;
import dev.aether.ui.gui.TestConfigDir;
import dev.aether.ui.gui.preview.PreviewGuiHost;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProfileActionsTest {
    private static final ProfileActions.Kind CONFIG = ProfileActions.Kind.CONFIG;

    @BeforeAll
    static void configDir() {
        TestConfigDir.ensure();
    }

    @Test
    void configExportBlanksEverySecret() {
        String webhook = AetherConfig.DISCORD_WEBHOOK_URL.get();
        String token = AetherConfig.REMOTE_CONTROL_BOT_TOKEN.get();
        List<String> coop = AetherConfig.COOP_NAMES.get();
        String username = AetherConfig.CUSTOM_USERNAME.get();
        String nick = AetherConfig.SERVER_NICK.get();
        try {
            AetherConfig.DISCORD_WEBHOOK_URL.set("https://discord.com/api/webhooks/1/secret");
            AetherConfig.REMOTE_CONTROL_BOT_TOKEN.set("bot-token");
            AetherConfig.COOP_NAMES.set(List.of("CoopFriend"));
            AetherConfig.CUSTOM_USERNAME.set("RealName");
            AetherConfig.SERVER_NICK.set("Nick");
            assertTrue(ProfileActions.save(CONFIG, "p0b-export"));
            assertTrue(ConfigProfileManager.exportJson("p0b-export").contains("bot-token"), "the saved file keeps it");

            PreviewGuiHost.MemoryClipboard clipboard = new PreviewGuiHost.MemoryClipboard();
            ProfileActions.export(CONFIG, "p0b-export", clipboard);
            JsonObject exported = JsonParser.parseString(clipboard.read()).getAsJsonObject();
            assertEquals("", exported.get("discordWebhookUrl").getAsString());
            assertEquals("", exported.get("remoteControlBotToken").getAsString());
            assertEquals(0, exported.getAsJsonArray("coopNames").size());
            assertEquals("", exported.get("customUsername").getAsString());
            assertEquals("", exported.get("serverNick").getAsString());
            assertEquals(clipboard.read(), ProfileActions.exportJson(CONFIG, "p0b-export"));
        } finally {
            AetherConfig.DISCORD_WEBHOOK_URL.set(webhook);
            AetherConfig.REMOTE_CONTROL_BOT_TOKEN.set(token);
            AetherConfig.COOP_NAMES.set(coop);
            AetherConfig.CUSTOM_USERNAME.set(username);
            AetherConfig.SERVER_NICK.set(nick);
            ProfileActions.delete(CONFIG, "p0b-export");
        }
    }

    @Test
    void importTakesTheTypedNameOrImportedAndAsksBeforeOverwriting() {
        PreviewGuiHost.MemoryClipboard clipboard = new PreviewGuiHost.MemoryClipboard();
        try {
            assertEquals(ProfileActions.ImportResult.EMPTY_CLIPBOARD,
                    ProfileActions.importFromClipboard(CONFIG, "", clipboard, false));
            clipboard.write("{\"farmType\": \"S_SHAPE\"}");
            assertFalse(ProfileActions.exists(CONFIG, "imported"));
            assertEquals(ProfileActions.ImportResult.IMPORTED,
                    ProfileActions.importFromClipboard(CONFIG, "  ", clipboard, false));
            assertTrue(ProfileActions.exists(CONFIG, "imported"));
            assertEquals(ProfileActions.ImportResult.NEEDS_CONFIRM,
                    ProfileActions.importFromClipboard(CONFIG, null, clipboard, false));
            clipboard.write("{\"farmType\": \"CUSTOM\"}");
            assertEquals(ProfileActions.ImportResult.IMPORTED,
                    ProfileActions.importFromClipboard(CONFIG, null, clipboard, true));
            assertTrue(ConfigProfileManager.exportJson("imported").contains("CUSTOM"));

            assertEquals(ProfileActions.ImportResult.IMPORTED,
                    ProfileActions.importFromClipboard(CONFIG, "p0b typed", clipboard, false));
            assertTrue(ProfileActions.list(CONFIG).contains("p0b typed"));
        } finally {
            ProfileActions.delete(CONFIG, "imported");
            ProfileActions.delete(CONFIG, "p0b typed");
        }
        assertFalse(ProfileActions.exists(CONFIG, "imported"));
    }

    @Test
    void renameCommitsTrimmedNamesAndBlankCancels() {
        try {
            assertTrue(ProfileActions.save(CONFIG, "p0b-old"));
            assertFalse(ProfileActions.save(CONFIG, " "));
            assertFalse(ProfileActions.rename(CONFIG, "p0b-old", "   "));
            assertTrue(ProfileActions.exists(CONFIG, "p0b-old"));
            assertTrue(ProfileActions.rename(CONFIG, "p0b-old", " p0b-new "));
            assertFalse(ProfileActions.exists(CONFIG, "p0b-old"));
            assertTrue(ProfileActions.exists(CONFIG, "p0b-new"));
            assertTrue(ProfileActions.load(CONFIG, "p0b-new"));
            assertFalse(ProfileActions.load(CONFIG, "p0b-missing"));
        } finally {
            ProfileActions.delete(CONFIG, "p0b-old");
            ProfileActions.delete(CONFIG, "p0b-new");
        }
    }
}
