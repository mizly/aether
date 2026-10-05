package dev.aether.macro.fishing;

import dev.aether.config.AetherConfig;
import dev.aether.modules.routes.RouteStore;
import net.fabricmc.loader.api.FabricLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FishingMacroConfigTest {
    @BeforeAll
    static void configureLoader() throws Exception {
        var loader = FabricLoader.getInstance();
        var configDir = loader.getClass().getDeclaredField("configDir");
        configDir.setAccessible(true);
        if (configDir.get(loader) == null) configDir.set(loader, Files.createTempDirectory("aether-fishing-test"));
    }

    @Test
    void theFishingMacroHasItsOwnRouteSelectionAndFolder() {
        assertSame(AetherConfig.FISHING_MACRO_ROUTE, FishingMacroKind.GENERAL.routeSelection());
        assertSame(RouteStore.FISHING, FishingMacroKind.GENERAL.folder());
        assertEquals("", FishingMacroKind.GENERAL.newRouteWarp());
        assertTrue(RouteStore.FOLDERS.contains(RouteStore.FISHING));
    }

    @Test
    void aFreshConfigFishesWaterWhereTheMacroStartsAndFightsEverything() {
        assertEquals("", AetherConfig.FISHING_MACRO_ROUTE.getDefault());
        assertEquals(FishingMacro.AimAt.WATER,
                FishingMacro.AimAt.fromConfig(AetherConfig.FISHING_MACRO_AIM_AT.getDefault()));
        assertTrue(AetherConfig.FISHING_MACRO_MOB_WHITELIST.getDefault().isEmpty());
        assertTrue(AetherConfig.FISHING_MACRO_MOB_BLACKLIST.getDefault().isEmpty());
    }

    @Test
    void theHyperionStaysInTheHotbarUntilTurnedOn() {
        assertFalse(AetherConfig.FISHING_MACRO_USE_HYPERION.getDefault());
    }
}
