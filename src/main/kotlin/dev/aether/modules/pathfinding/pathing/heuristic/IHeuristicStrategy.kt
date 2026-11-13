package dev.aether.modules.pathfinding.pathing.heuristic

import dev.aether.modules.pathfinding.wrapper.PathPosition

interface IHeuristicStrategy {
    fun calculate(context: HeuristicContext): Double

    fun calculateTransitionCost(from: PathPosition, to: PathPosition): Double
}
