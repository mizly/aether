package dev.aether.modules.pathfinding.pathfinder.processing

import dev.aether.modules.pathfinding.pathing.configuration.PathfinderConfiguration
import dev.aether.modules.pathfinding.pathing.context.EnvironmentContext
import dev.aether.modules.pathfinding.pathing.processing.context.SearchContext
import dev.aether.modules.pathfinding.provider.NavigationPointProvider
import dev.aether.modules.pathfinding.wrapper.PathPosition
import java.util.HashMap

class SearchContextImpl(
    private val startPathPosition: PathPosition,
    private val targetPathPosition: PathPosition,
    private val pathfinderConfiguration: PathfinderConfiguration,
    private val navigationPointProvider: NavigationPointProvider,
    private val environmentContext: EnvironmentContext?
) : SearchContext {

    private val sharedData: MutableMap<String, Any> = HashMap()

    override fun getStartPathPosition(): PathPosition = startPathPosition

    override fun getTargetPathPosition(): PathPosition = targetPathPosition

    override fun getPathfinderConfiguration(): PathfinderConfiguration = pathfinderConfiguration

    override fun getNavigationPointProvider(): NavigationPointProvider = navigationPointProvider

    override fun getSharedData(): MutableMap<String, Any> = sharedData

    override fun getEnvironmentContext(): EnvironmentContext? = environmentContext
}
