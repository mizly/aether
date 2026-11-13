package dev.aether.modules.pathfinding.pathfinder.processing

import dev.aether.modules.pathfinding.Node
import dev.aether.modules.pathfinding.pathing.heuristic.IHeuristicStrategy
import dev.aether.modules.pathfinding.pathing.processing.context.EvaluationContext
import dev.aether.modules.pathfinding.pathing.processing.context.SearchContext
import dev.aether.modules.pathfinding.wrapper.PathPosition

class EvaluationContextImpl(
    private var searchContext: SearchContext?,
    private var engineNode: Node?,
    private var parentEngineNode: Node?,
    private var heuristicStrategy: IHeuristicStrategy?
) : EvaluationContext {

    // reused rather than reallocated per node
    fun update(
        searchContext: SearchContext,
        engineNode: Node,
        parentEngineNode: Node?,
        heuristicStrategy: IHeuristicStrategy
    ) {
        this.searchContext = searchContext
        this.engineNode = engineNode
        this.parentEngineNode = parentEngineNode
        this.heuristicStrategy = heuristicStrategy
    }

    override fun getCurrentPathPosition(): PathPosition = engineNode!!.position

    override fun getPreviousPathPosition(): PathPosition? = parentEngineNode?.position

    override fun getCurrentNodeDepth(): Int = engineNode!!.depth

    override fun getCurrentNodeHeuristicValue(): Double = engineNode!!.heuristic

    override fun getPathCostToPreviousPosition(): Double = parentEngineNode?.gCost ?: 0.0

    override fun getSearchContext(): SearchContext = searchContext!!

    override fun getGrandparentPathPosition(): PathPosition? {
        val parent = parentEngineNode
        return if (parent != null && parent.parent != null) parent.parent!!.position else null
    }

    override fun getGreatGrandparentPathPosition(): PathPosition? {
        val parent = parentEngineNode ?: return null
        val gp = parent.parent
        return if (gp != null && gp.parent != null) gp.parent!!.position else null
    }

    override fun getBaseTransitionCost(): Double {
        val parent = parentEngineNode ?: return 0.0
        val baseCost = heuristicStrategy!!.calculateTransitionCost(parent.position, engineNode!!.position)
        if (baseCost.isNaN() || baseCost.isInfinite()) {
            throw IllegalStateException("Heuristic transition cost produced an invalid numeric value: $baseCost")
        }
        return Math.max(baseCost, 0.0)
    }
}
