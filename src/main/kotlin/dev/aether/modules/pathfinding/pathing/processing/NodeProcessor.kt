package dev.aether.modules.pathfinding.pathing.processing

import dev.aether.modules.pathfinding.pathing.processing.context.EvaluationContext

interface NodeProcessor : Processor {
    fun isValid(context: EvaluationContext): Boolean = true

    fun calculateCostContribution(context: EvaluationContext): Cost = Cost.ZERO
}
