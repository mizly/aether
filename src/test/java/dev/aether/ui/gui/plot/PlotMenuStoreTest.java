package dev.aether.ui.gui.plot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlotMenuStoreTest {
    @TempDir
    Path dir;

    @Test
    void menusAreKeptPerProfileAndSurviveARestart() throws Exception {
        Path file = dir.resolve("aether").resolve("garden_plots.json");
        PlotMenuStore store = new PlotMenuStore(file);
        PlotMenuSnapshot banana = PlotPickerModelTest.menu();
        PlotMenuSnapshot apple = new PlotMenuSnapshot(2L, Map.of(5,
                new PlotMenuItem("minecraft:carrot", "§aPlot §7- §b5", List.of("§4§l §cThis plot has §22 §2Pests§c!"))));
        assertTrue(store.put("banana-id", banana));
        assertFalse(store.put("banana-id", banana), "the same menu again is not a change");
        assertTrue(store.put("apple-id", apple));
        store.save();
        assertTrue(Files.readString(file).contains("§aPlot §7- §b5"), "colour codes stay readable in the file");

        PlotMenuStore restarted = new PlotMenuStore(file);
        assertTrue(banana.sameItems(restarted.current("banana-id")));
        assertEquals(2, restarted.current("apple-id").info(5).pests());
        assertTrue(apple.sameItems(restarted.current(null)), "before hypixel names the profile, the last one read answers");
        assertNull(restarted.current("cherry-id"), "a known profile never borrows another garden");
    }

    @Test
    void aBrokenFileIsIgnored() throws Exception {
        Path file = dir.resolve("garden_plots.json");
        Files.writeString(file, "{ not json");
        PlotMenuStore store = new PlotMenuStore(file);
        assertNull(store.current(null));
        store.put(null, PlotPickerModelTest.menu());
        assertNotNull(store.current(PlotMenuStore.UNKNOWN_PROFILE));
    }
}
