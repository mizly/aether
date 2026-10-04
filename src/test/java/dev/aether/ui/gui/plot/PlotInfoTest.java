package dev.aether.ui.gui.plot;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlotInfoTest {
    @Test
    void readsUnlockedPlotsWithCustomNamesPestsAndSprays() {
        PlotInfo info = PlotInfo.of(4, new PlotMenuItem("minecraft:wheat", "§aPlot §7- §bS 4", List.of(
                "§7Pasting in progress: 0%",
                "§4§l §cThis plot has §21 §2Pest§c!",
                "§7Sprayed with §aCompost §7- §a5m 30s",
                "",
                "§eLeft-click to modify!")));
        assertEquals("S 4", info.label());
        assertEquals(PlotStatus.PRESET, info.status());
        assertEquals(1, info.pests());
        assertEquals("Compost - 5m 30s", info.spray());
        assertEquals(-1, info.cleanup());
    }

    @Test
    void pestLinesFromOldAndNewClientsBothCount() {
        assertEquals(12, PlotInfo.of(3, item("lime_stained_glass_pane", "§aPlot §7- §b3",
                "§4§lൠ §cThis plot has §212 §2 Pests§c!")).pests());
        assertEquals(3, PlotInfo.of(3, item("lime_stained_glass_pane", "§aPlot §7- §b3",
                "§4§l §r§7This plot has §r§c3 Pests§r§7!")).pests());
        assertEquals(0, PlotInfo.of(3, item("lime_stained_glass_pane", "§aPlot §7- §b3", "§7Cleanup: §b100% Completed")).pests());
    }

    @Test
    void iconsAndLoreGiveTheState() {
        assertEquals(PlotStatus.CLEANED, status(5, "lime_stained_glass_pane"));
        assertEquals(PlotStatus.UNCLEANED, status(10, "orange_stained_glass_pane", "§7Cleanup: §b42% Completed"));
        assertEquals(42, PlotInfo.of(10, item("orange_stained_glass_pane", "§aPlot §7- §b10", "§7Cleanup: §b42% Completed")).cleanup());
        assertEquals(PlotStatus.UNCLEANED, status(10, "wheat", "§7Cleanup: §b7.5% Completed"));
        assertEquals(PlotStatus.GREENHOUSE, status(1, "white_stained_glass", "§7Greenhouse Plot"));
        assertEquals(PlotStatus.GREENHOUSE, status(9, "white_stained_glass"));
        assertEquals(PlotStatus.UNLOCKABLE, status(11, "oak_button", "§7Cost:", "§aCompost §8x2"));
        assertEquals(PlotStatus.LOCKED, status(21, "red_stained_glass_pane"));
        assertEquals(PlotStatus.LOCKED, status(21, "barrier", "§7Cost:"));
        assertEquals(PlotStatus.BARN, status(0, "spruce_planks"));
        assertFalse(PlotStatus.LOCKED.unlocked());
        assertFalse(PlotStatus.UNLOCKABLE.unlocked());
        assertTrue(PlotStatus.GREENHOUSE.unlocked());
    }

    @Test
    void lockedNamesAndTheBarnKeepTheirLabels() {
        assertEquals("21", PlotInfo.of(21, item("red_stained_glass_pane", "§ePlot §8- §b21")).label());
        assertEquals("22a", PlotInfo.of(22, item("lime_stained_glass_pane", "§aPlot §7- §b22a")).label());
        assertEquals("The Barn", PlotInfo.of(0, item("spruce_planks", "§aThe Barn")).label());
        assertEquals("7", PlotInfo.of(7, item("lime_stained_glass_pane", "")).label());
    }

    @Test
    void aSnapshotIsCompleteWithAllTwentyFiveSlots() {
        Map<Integer, PlotMenuItem> items = new java.util.HashMap<>();
        for (int plot = 0; plot <= 24; plot++) {
            items.put(plot, item(plot == 1 ? "white_stained_glass" : "lime_stained_glass_pane", "§aPlot §7- §b" + plot,
                    plot == 1 ? "§7Greenhouse Plot" : "§7"));
        }
        PlotMenuSnapshot snapshot = new PlotMenuSnapshot(1L, items);
        assertTrue(snapshot.complete());
        assertTrue(snapshot.isGreenhouse(1));
        assertFalse(snapshot.isGreenhouse(2));
        items.remove(24);
        assertFalse(new PlotMenuSnapshot(1L, items).complete());
        assertNull(new PlotMenuSnapshot(1L, items).info(24));
    }

    private static PlotStatus status(int plot, String id, String... lore) {
        return PlotInfo.of(plot, item(id, "§aPlot §7- §b" + plot, lore)).status();
    }

    private static PlotMenuItem item(String id, String name, String... lore) {
        return new PlotMenuItem(id, name, List.of(lore));
    }
}
