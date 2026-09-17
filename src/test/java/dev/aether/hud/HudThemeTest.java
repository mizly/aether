package dev.aether.hud;

import dev.aether.config.AetherConfig;
import dev.aether.macro.MacroState;
import dev.aether.ui.theme.Theme;
import net.fabricmc.loader.api.FabricLoader;
import org.junit.jupiter.api.*;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class HudThemeTest {
    private String savedTheme;

    @BeforeAll
    static void configureLoader() throws Exception {
        var loader = FabricLoader.getInstance();
        var configDir = loader.getClass().getDeclaredField("configDir");
        configDir.setAccessible(true);
        if (configDir.get(loader) == null) configDir.set(loader, Files.createTempDirectory("aether-hud-test"));
    }

    @BeforeEach
    void saveTheme() { savedTheme = Theme.exportJson(); }

    @AfterEach
    void restoreTheme() { Theme.importJson(savedTheme); }

    @Test
    void tintOpacityNeverOverridesConfiguredTransparency() {
        assertEquals(0x004080C0, HudStyle.alpha(0x004080C0, 0.8f));
        assertEquals(0x204080C0, HudStyle.alpha(0x804080C0, 0.25f));
        assertEquals(0x804080C0, HudStyle.alpha(0x804080C0, 2f));
        assertEquals(0x004080C0, HudStyle.alpha(0x804080C0, -1f));
    }

    @Test
    void cachedTaskRowsFollowThemeChangesImmediately() {
        var ready = TaskHudStatusProvider.TaskStatusRow.ready("Task", "Ready to run");
        var waiting = TaskHudStatusProvider.TaskStatusRow.waiting("Task", "Waiting");
        var triggered = TaskHudStatusProvider.TaskStatusRow.triggered("Task", "Triggered");
        Theme.importJson("{\"HUD Success\":\"80993311\",\"HUD Warning\":\"40445566\",\"HUD Error\":\"20332211\"}");
        assertEquals(0x80993311, ready.color());
        assertEquals(0x40445566, waiting.color());
        assertEquals(0x20332211, triggered.color());
        Theme.HUD_SUCCESS = 0xFF123456;
        assertEquals(0xFF123456, ready.color());
        assertEquals(Theme.HUD_SUCCESS, HudStyle.stateColor(MacroState.State.FARMING));
        assertEquals(Theme.HUD_SUCCESS, HudStyle.bpsColor(20));
    }

    @Test
    void themeRoundTripIncludesStatusColorsAndKeepsHudSeparateFromGui() {
        int guiAccent = Theme.ACCENT_PRIMARY;
        Theme.HUD_ACCENT = 0x40224466;
        Theme.HUD_SUCCESS = 0x80664422;
        String exported = Theme.exportJson();
        Theme.HUD_ACCENT = 0;
        Theme.HUD_SUCCESS = 0;
        Theme.importJson(exported);
        assertEquals(0x40224466, Theme.HUD_ACCENT);
        assertEquals(0x80664422, Theme.HUD_SUCCESS);
        assertEquals(guiAccent, Theme.ACCENT_PRIMARY);
    }

    @Test
    void mainAndWatermarkSelectionIsABitmask() {
        int saved = AetherConfig.HUD_THEME.get();
        float macroHeight = new MacroHudElement().getHeight();
        try {
            for (int selection = 0; selection < 4; selection++) {
                AetherConfig.HUD_THEME.set(selection);
                assertEquals((selection & 1) != 0, new MainStatusHudElement().isVisible());
                assertEquals((selection & 2) != 0, new WatermarkHudElement().isVisible());
                assertEquals(macroHeight, new MacroHudElement().getHeight());
            }
        } finally {
            AetherConfig.HUD_THEME.set(saved);
        }
    }

    @Test
    void scoreboardUsesLiveHudColorsAndRetainsServerValueColors() {
        Theme.HUD_TITLE = 0xFFDEC0FF;
        Theme.HUD_VALUE = 0xFFE1E2E3;
        Theme.HUD_LABEL = 0xFFA1A2A3;
        assertEquals(Theme.HUD_TITLE, ScoreboardText.textColor(net.minecraft.network.chat.Style.EMPTY.withColor(0xFFFF55), -1, true));
        assertEquals(Theme.HUD_VALUE, ScoreboardText.textColor(net.minecraft.network.chat.Style.EMPTY, -1, false));
        assertEquals(Theme.HUD_LABEL, ScoreboardText.textColor(net.minecraft.network.chat.Style.EMPTY.withColor(0xAAAAAA), -1, false));
        assertEquals(0xFFFFAA00, ScoreboardText.textColor(net.minecraft.network.chat.Style.EMPTY.withColor(0xFFAA00), -1, false));
        Theme.HUD_TITLE = 0x80ABCDEF;
        assertEquals(0x40ABCDEF, ScoreboardText.textColor(net.minecraft.network.chat.Style.EMPTY, 0x80FFFFFF, true));
    }
}
