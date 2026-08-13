package dev.aether.util;

import net.minecraft.util.Mth;

/** Garden plot grid geometry: plot number to world bounds, and world position to plot cell. */
public final class GardenPlots {
    public static final int PLOT_SIZE = 96;
    public static final int PLOT_OFFSET = 48;

    /**
     * Garden plot numbers as seen on the map from above: top row is north (-Z),
     * left column is west (-X), 0 is the barn at grid (0, 0).
     */
    private static final int[][] PLOT_LAYOUT = {
            {21, 13,  9, 14, 22},
            {15,  5,  1,  6, 16},
            {10,  2,  0,  3, 11},
            {17,  7,  4,  8, 18},
            {23, 19, 12, 20, 24},
    };

    private GardenPlots() {
    }

    /** Grid cell of a plot number as {gridX, gridZ}, or null when the number is not on the map. */
    public static int[] gridForPlot(int plot) {
        for (int row = 0; row < PLOT_LAYOUT.length; row++) {
            for (int col = 0; col < PLOT_LAYOUT[row].length; col++) {
                if (PLOT_LAYOUT[row][col] == plot) {
                    return new int[] { col - 2, row - 2 };
                }
            }
        }
        return null;
    }

    public static Bounds boundsForPlot(int plot) {
        int[] grid = gridForPlot(plot);
        return grid == null ? null : boundsForGrid(grid[0], grid[1]);
    }

    /** Bounds for a plot label such as "14", "Plot 14" or "#14"; null when it is not a numbered plot. */
    public static Bounds boundsForPlot(String plot) {
        if (plot == null) {
            return null;
        }
        String digits = plot.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            return null;
        }
        try {
            return boundsForPlot(Integer.parseInt(digits));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static Bounds boundsContaining(double x, double z) {
        return boundsForGrid(gridIndex(x), gridIndex(z));
    }

    public static Bounds boundsForGrid(int gridX, int gridZ) {
        int minX = gridX * PLOT_SIZE - PLOT_OFFSET;
        int minZ = gridZ * PLOT_SIZE - PLOT_OFFSET;
        return new Bounds(minX, minZ, minX + PLOT_SIZE, minZ + PLOT_SIZE);
    }

    public static int gridIndex(double coord) {
        return Math.floorDiv(Mth.floor(coord) + PLOT_OFFSET, PLOT_SIZE);
    }

    /** Plot square with an exclusive max edge, matching the block columns the plot owns. */
    public record Bounds(int minX, int minZ, int maxX, int maxZ) {
        public boolean contains(double x, double z, double margin) {
            return x >= minX - margin && x < maxX + margin
                    && z >= minZ - margin && z < maxZ + margin;
        }

        public double centerX() {
            return (minX + maxX) / 2.0;
        }

        public double centerZ() {
            return (minZ + maxZ) / 2.0;
        }

        public double clampX(double x, double inset) {
            return Mth.clamp(x, minX + inset, maxX - inset);
        }

        public double clampZ(double z, double inset) {
            return Mth.clamp(z, minZ + inset, maxZ - inset);
        }
    }
}
