package dev.aether.modules.pathfinding.pathing.processing.context

import dev.aether.modules.pathfinding.pathing.configuration.PathfinderConfiguration
import dev.aether.modules.pathfinding.pathing.context.EnvironmentContext
import dev.aether.modules.pathfinding.provider.NavigationPointProvider
import dev.aether.modules.pathfinding.wrapper.PathPosition

interface SearchContext {
    fun getStartPathPosition(): PathPosition

    fun getTargetPathPosition(): PathPosition

    fun getPathfinderConfiguration(): PathfinderConfiguration

    fun getNavigationPointProvider(): NavigationPointProvider

    fun getSharedData(): MutableMap<String, Any>

    fun getEnvironmentContext(): EnvironmentContext?
}
