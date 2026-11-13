package dev.aether.util

import net.minecraft.util.Mth

class GardenPlots private constructor() {
    companion object {
        const val PLOT_SIZE = 96
        const val PLOT_OFFSET = 48
        private val PLOT_LAYOUT = arrayOf(
            intArrayOf(21, 13, 9, 14, 22), intArrayOf(15, 5, 1, 6, 16), intArrayOf(10, 2, 0, 3, 11), intArrayOf(17, 7, 4, 8, 18), intArrayOf(23, 19, 12, 20, 24)
        )

        @JvmStatic fun gridForPlot(plot: Int): IntArray? {
            for (row in PLOT_LAYOUT.indices) for (col in PLOT_LAYOUT[row].indices) if (PLOT_LAYOUT[row][col] == plot) return intArrayOf(col - 2, row - 2)
            return null
        }
        @JvmStatic fun boundsForPlot(plot: Int): Bounds? {
            val grid = gridForPlot(plot) ?: return null
            return boundsForGrid(grid[0], grid[1])
        }
        @JvmStatic fun boundsForPlot(plot: String?): Bounds? {
            if (plot == null) return null
            val digits = plot.replace(Regex("\\D"), "")
            if (digits.isEmpty()) return null
            return try { boundsForPlot(Integer.parseInt(digits)) } catch (_: NumberFormatException) { null }
        }
        @JvmStatic fun boundsContaining(x: Double, z: Double) = boundsForGrid(gridIndex(x), gridIndex(z))
        @JvmStatic fun boundsForGrid(gridX: Int, gridZ: Int): Bounds {
            val minX = gridX * PLOT_SIZE - PLOT_OFFSET
            val minZ = gridZ * PLOT_SIZE - PLOT_OFFSET
            return Bounds(minX, minZ, minX + PLOT_SIZE, minZ + PLOT_SIZE)
        }
        @JvmStatic fun gridIndex(coord: Double) = Math.floorDiv(Mth.floor(coord) + PLOT_OFFSET, PLOT_SIZE)
    }

    class Bounds(private val minX: Int, private val minZ: Int, private val maxX: Int, private val maxZ: Int) {
        fun minX(): Int = minX
        fun minZ(): Int = minZ
        fun maxX(): Int = maxX
        fun maxZ(): Int = maxZ
        operator fun component1(): Int = minX
        operator fun component2(): Int = minZ
        operator fun component3(): Int = maxX
        operator fun component4(): Int = maxZ
        fun contains(x: Double, z: Double, margin: Double) = x >= minX - margin && x < maxX + margin && z >= minZ - margin && z < maxZ + margin
        fun centerX(): Double = (minX + maxX) / 2.0
        fun centerZ(): Double = (minZ + maxZ) / 2.0
        fun clampX(x: Double, inset: Double) = Mth.clamp(x, minX + inset, maxX - inset)
        fun clampZ(z: Double, inset: Double) = Mth.clamp(z, minZ + inset, maxZ - inset)

        override fun equals(other: Any?): Boolean = other is Bounds && minX == other.minX && minZ == other.minZ && maxX == other.maxX && maxZ == other.maxZ

        override fun hashCode(): Int {
            var result = Integer.hashCode(minX)
            result = 31 * result + Integer.hashCode(minZ)
            result = 31 * result + Integer.hashCode(maxX)
            result = 31 * result + Integer.hashCode(maxZ)
            return result
        }

        override fun toString(): String = "Bounds[minX=$minX, minZ=$minZ, maxX=$maxX, maxZ=$maxZ]"
    }
}
