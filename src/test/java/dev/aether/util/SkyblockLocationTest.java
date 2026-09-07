package dev.aether.util;

import dev.aether.macro.MacroState.Location;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkyblockLocationTest {
    @Test
    void requiresSkyblockScoreboardBeforeTrustingGardenTabEntry() {
        for (String title : new String[]{"HYPIXEL", "SKYWARS", "BED WARS", "", "NOT SKYBLOCK", "SKYBLOCKING"}) {
            assertEquals(Location.LOBBY, SkyblockLocation.resolve(title, false, "Area: Garden"), title);
        }
        assertEquals(Location.LIMBO, SkyblockLocation.resolve(null, false, "Area: Garden"));
    }

    @Test
    void recognizesFormattedSoloCoopAndGuestScoreboards() {
        for (String title : new String[]{"SKYBLOCK", "§e§lSKYBLOCK", "§6SKYBLOCK CO-OP", "SkyBlock Guest",
                "\u00A0§eSKYBLOCK\u00A0"}) {
            assertEquals(Location.GARDEN, SkyblockLocation.resolve(title, false, "§bArea: §aGarden"), title);
        }
    }

    @Test
    void lobbyItemsOverrideStaleSkyblockAndGardenData() {
        assertEquals(Location.LOBBY, SkyblockLocation.resolve("SKYBLOCK", true, "Area: Garden"));
    }

    @Test
    void doesNotTreatMissingOrDifferentAreaAsGarden() {
        assertEquals(Location.HUB, SkyblockLocation.resolve("SKYBLOCK", false, null));
        assertEquals(Location.HUB, SkyblockLocation.resolve("SKYBLOCK", false, "Area: Hub"));
        assertEquals(Location.HUB, SkyblockLocation.resolve("SKYBLOCK", false, "Area: Garden Lobby"));
        assertEquals(Location.CRYSTAL_HOLLOWS,
                SkyblockLocation.resolve("SKYBLOCK", false, "Area: Crystal Hollows"));
    }
}
