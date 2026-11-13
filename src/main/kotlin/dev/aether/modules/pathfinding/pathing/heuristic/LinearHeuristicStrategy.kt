package dev.aether.modules.pathfinding.pathing.heuristic

import dev.aether.modules.pathfinding.wrapper.PathPosition
import kotlin.math.abs
import kotlin.math.sqrt

class LinearHeuristicStrategy : IHeuristicStrategy {
    override fun calculate(context: HeuristicContext): Double {
        val progress = context.pathfindingProgress
        val weights = context.heuristicWeights

        val position = progress.current
        val target = progress.target

        val ax = abs(position.flooredX() - target.flooredX())
        val ay = abs(position.flooredY() - target.flooredY())
        val az = abs(position.flooredZ() - target.flooredZ())

        val manhattan = (ax + ay + az).toDouble()

        val min = minOf(ax, ay, az)
        val max = maxOf(ax, ay, az)
        val mid = ax + ay + az - min - max
        val octile = (D3 - D2) * min + (D2 - D1) * mid + D1 * max

        val perpendicular = InternalHeuristicUtils.calculatePerpendicularDistance(progress)
        val height = abs(position.flooredY() - target.flooredY()).toDouble()

        return manhattan * weights.manhattanWeight +
            octile * weights.octileWeight +
            perpendicular * weights.perpendicularWeight +
            height * weights.heightWeight
    }

    override fun calculateTransitionCost(from: PathPosition, to: PathPosition): Double {
        val dx = to.centeredX() - from.centeredX()
        val dy = to.centeredY() - from.centeredY()
        val dz = to.centeredZ() - from.centeredZ()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private companion object {
        private const val D1: Double = 1.0
        private val D2: Double = Math.sqrt(2.0)
        private val D3: Double = Math.sqrt(3.0)
    }
}
