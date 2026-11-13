package dev.aether.hud

class PestHudLayout(private val rows: Int, private val columns: Int, private val scale: Float) {
    fun rows(): Int = rows
    fun columns(): Int = columns
    fun scale(): Float = scale

    fun width(): Float = columns * COLUMN_WIDTH

    fun height(): Float = HEADER_HEIGHT + rows * ROW_HEIGHT + 5f

    override fun equals(other: Any?): Boolean =
        this === other || other is PestHudLayout && rows == other.rows && columns == other.columns && scale.compareTo(other.scale) == 0

    override fun hashCode(): Int {
        var result = rows
        result = 31 * result + columns
        result = 31 * result + java.lang.Float.hashCode(scale)
        return result
    }

    override fun toString(): String = "PestHudLayout[rows=$rows, columns=$columns, scale=$scale]"

    companion object {
        const val COLUMN_WIDTH: Float = 190f
        const val ROW_HEIGHT: Float = 35f
        const val HEADER_HEIGHT: Float = 27f

        @JvmStatic
        fun fit(count: Int, availableWidth: Float, availableHeight: Float, requestedScale: Float): PestHudLayout {
            val itemCount = Math.max(1, count)
            var best = PestHudLayout(1, itemCount, 0f)
            for (rows in 1..itemCount) {
                val columns = (itemCount + rows - 1) / rows
                val scale = Math.min(requestedScale, Math.min(
                    Math.max(1f, availableWidth) / (columns * COLUMN_WIDTH),
                    Math.max(1f, availableHeight) / (HEADER_HEIGHT + rows * ROW_HEIGHT + 5f)
                ))
                // Prefer a vertical list when several arrangements fit at the requested scale.
                if (scale >= best.scale()) best = PestHudLayout(rows, columns, scale)
            }
            return best
        }
    }
}
