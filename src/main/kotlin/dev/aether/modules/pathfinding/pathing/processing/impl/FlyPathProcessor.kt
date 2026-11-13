package dev.aether.modules.pathfinding.pathing.processing.impl

import dev.aether.modules.pathfinding.movement.FlightCollisionChecker
import dev.aether.modules.pathfinding.movement.WalkabilityChecker
import dev.aether.modules.pathfinding.pathing.processing.Cost
import dev.aether.modules.pathfinding.pathing.processing.NodeProcessor
import dev.aether.modules.pathfinding.pathing.processing.context.EvaluationContext
import dev.aether.modules.pathfinding.wrapper.PathPosition

class FlyPathProcessor private constructor(private val checker: FlightCollisionChecker?, @Suppress("UNUSED_PARAMETER") marker: Unit) : NodeProcessor {
    constructor(checker: WalkabilityChecker?) : this(if (checker == null) null else FlightCollisionChecker(checker), Unit)
    constructor(checker: FlightCollisionChecker?) : this(checker, Unit)

    override fun isValid(context: EvaluationContext): Boolean {
        if (checker == null) return false
        val pos = context.getCurrentPathPosition()
        if (!hasFlightClearance(pos)) return false
        val prev = context.getPreviousPathPosition() ?: return true
        return checker.isClear(prev, pos)
    }

    override fun calculateCostContribution(context: EvaluationContext): Cost {
        val pos = context.getCurrentPathPosition()
        val prev = context.getPreviousPathPosition()
        if (checker == null || prev == null) return Cost.ZERO
        return Cost.of(Math.abs(pos.flooredY() - prev.flooredY()) * VERTICAL_COST + checker.clearanceCost(pos))
    }

    fun hasFlightClearance(pos: PathPosition): Boolean = checker != null && checker.hasClearance(pos)
    fun hasFlightClearance(x: Int, y: Int, z: Int): Boolean = hasFlightClearance(PathPosition(x.toDouble(), y.toDouble(), z.toDouble()))

    companion object { private const val VERTICAL_COST = 0.08 }
}
