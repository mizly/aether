package dev.aether.modules.pathfinding.pathing.processing

class Cost private constructor(@JvmField val value: Double) {
    companion object {
        @JvmField
        val ZERO: Cost = Cost(0.0)

        @JvmStatic
        fun of(value: Double): Cost {
            if (value.isNaN() || value < 0) {
                throw IllegalArgumentException("Cost must be a positive number or 0")
            }
            return Cost(value)
        }
    }
}
