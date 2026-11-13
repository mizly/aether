package dev.aether.modules.pathfinding.pathing.processing.context

import dev.aether.modules.pathfinding.pathing.configuration.PathfinderConfiguration
import dev.aether.modules.pathfinding.pathing.context.EnvironmentContext
import dev.aether.modules.pathfinding.provider.NavigationPointProvider
import dev.aether.modules.pathfinding.wrapper.PathPosition

interface EvaluationContext {
    fun getCurrentPathPosition(): PathPosition

    fun getPreviousPathPosition(): PathPosition?

    fun getCurrentNodeDepth(): Int

    fun getCurrentNodeHeuristicValue(): Double

    fun getPathCostToPreviousPosition(): Double

    fun getBaseTransitionCost(): Double

    fun getSearchContext(): SearchContext

    fun getGrandparentPathPosition(): PathPosition?

    fun getGreatGrandparentPathPosition(): PathPosition? = null

    fun getPathfinderConfiguration(): PathfinderConfiguration = getSearchContext().getPathfinderConfiguration()

    fun getNavigationPointProvider(): NavigationPointProvider = getSearchContext().getNavigationPointProvider()

    fun getSharedData(): MutableMap<String, Any> = getSearchContext().getSharedData()

    fun getStartPathPosition(): PathPosition = getSearchContext().getStartPathPosition()

    fun getTargetPathPosition(): PathPosition = getSearchContext().getTargetPathPosition()

    fun getEnvironmentContext(): EnvironmentContext? = getSearchContext().getEnvironmentContext()
}
