package dev.aether.ui.gui.plot;

import dev.aether.ui.settings.PlotToken;
import dev.aether.util.GardenPlots;

import java.util.Arrays;

// where each plot sits in hypixel's Configure Plots chest: the garden map, north up, fills columns 2-6 of the
// first five rows, so slot = 9 * row + column + 2 and the barn is slot 22
public final class PlotSlots {
    public static final int COLUMNS = 9;
    public static final int ROWS = 6;
    public static final int SIZE = COLUMNS * ROWS;
    public static final int MAP_SIZE = 5;
    public static final int BARN_SLOT = 22;
    public static final int GO_BACK_SLOT = 48;
    public static final int CLOSE_SLOT = 49;
    private static final int MAP_COLUMN = 2;
    private static final int NONE = -1;
    private static final int[] PLOT_BY_SLOT = new int[SIZE];
    private static final int[] SLOT_BY_PLOT = new int[PlotToken.MAX_PLOT + 1];

    static {
        Arrays.fill(PLOT_BY_SLOT, NONE);
        for (int plot = PlotToken.BARN; plot <= PlotToken.MAX_PLOT; plot++) {
            int[] grid = GardenPlots.gridForPlot(plot);
            int slot = (grid[1] + 2) * COLUMNS + MAP_COLUMN + grid[0] + 2;
            PLOT_BY_SLOT[slot] = plot;
            SLOT_BY_PLOT[plot] = slot;
        }
    }

    private PlotSlots() {
    }

    // 0 is the barn
    public static int slotOf(int plot) {
        if (plot < PlotToken.BARN || plot > PlotToken.MAX_PLOT) {
            throw new IllegalArgumentException("no garden plot " + plot);
        }
        return SLOT_BY_PLOT[plot];
    }

    // the plot shown in that slot, 0 for the barn, -1 for filler and buttons
    public static int plotAt(int slot) {
        return slot < 0 || slot >= SIZE ? NONE : PLOT_BY_SLOT[slot];
    }

    public static boolean isMapSlot(int slot) {
        return plotAt(slot) != NONE;
    }

    public static int row(int slot) {
        return slot / COLUMNS;
    }

    public static int column(int slot) {
        return slot % COLUMNS;
    }

    // the plot's cell on the 5x5 map, for thumbnails
    public static int mapColumn(int plot) {
        return column(slotOf(plot)) - MAP_COLUMN;
    }

    public static int mapRow(int plot) {
        return row(slotOf(plot));
    }

    public static int plotAtCell(int mapColumn, int mapRow) {
        if (mapColumn < 0 || mapColumn >= MAP_SIZE || mapRow < 0 || mapRow >= MAP_SIZE) {
            return NONE;
        }
        return PLOT_BY_SLOT[mapRow * COLUMNS + MAP_COLUMN + mapColumn];
    }
}
