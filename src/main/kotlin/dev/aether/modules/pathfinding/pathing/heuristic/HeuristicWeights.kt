package dev.aether.modules.pathfinding.pathing.heuristic

class HeuristicWeights(
    @JvmField val manhattanWeight: Double,
    @JvmField val octileWeight: Double,
    @JvmField val perpendicularWeight: Double,
    @JvmField val heightWeight: Double
) {
    companion object {
        @JvmField
        val DEFAULT_WEIGHTS: HeuristicWeights = HeuristicWeights(0.0, 1.0, 0.0, 0.0)
    }
}
