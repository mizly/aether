package dev.aether.ui.gui.plot;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PlotSlotsTest {
    @Test
    void plotsSitWhereHypixelPutsThem() {
        int[][] expected = {
                {21, 13, 9, 14, 22},
                {15, 5, 1, 6, 16},
                {10, 2, 0, 3, 11},
                {17, 7, 4, 8, 18},
                {23, 19, 12, 20, 24},
        };
        for (int row = 0; row < 5; row++) {
            for (int col = 0; col < 5; col++) {
                int slot = 9 * row + col + 2;
                assertEquals(expected[row][col], PlotSlots.plotAt(slot), "slot " + slot);
                assertEquals(slot, PlotSlots.slotOf(expected[row][col]));
                assertEquals(expected[row][col], PlotSlots.plotAtCell(col, row));
                assertEquals(col, PlotSlots.mapColumn(expected[row][col]));
                assertEquals(row, PlotSlots.mapRow(expected[row][col]));
            }
        }
        assertEquals(PlotSlots.BARN_SLOT, PlotSlots.slotOf(0));
        assertEquals(13, PlotSlots.slotOf(1));
        assertEquals(42, PlotSlots.slotOf(24));
    }

    @Test
    void everyOtherSlotIsFillerOrAButton() {
        Set<Integer> mapSlots = new HashSet<>();
        for (int slot = 0; slot < PlotSlots.SIZE; slot++) {
            if (PlotSlots.isMapSlot(slot)) {
                mapSlots.add(slot);
            } else {
                assertEquals(-1, PlotSlots.plotAt(slot));
            }
        }
        assertEquals(25, mapSlots.size());
        assertFalse(PlotSlots.isMapSlot(PlotSlots.GO_BACK_SLOT));
        assertFalse(PlotSlots.isMapSlot(PlotSlots.CLOSE_SLOT));
        assertEquals(-1, PlotSlots.plotAt(-1));
        assertEquals(-1, PlotSlots.plotAt(54));
        assertEquals(-1, PlotSlots.plotAtCell(5, 0));
        assertThrows(IllegalArgumentException.class, () -> PlotSlots.slotOf(25));
    }
}
