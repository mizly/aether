package dev.aether.modules.pathfinding.provider

import dev.aether.modules.pathfinding.pathing.context.EnvironmentContext
import dev.aether.modules.pathfinding.wrapper.PathPosition

interface NavigationPointProvider {
    fun getNavigationPoint(position: PathPosition): NavigationPoint = getNavigationPoint(position, null)

    fun getNavigationPoint(position: PathPosition, environmentContext: EnvironmentContext?): NavigationPoint
}
