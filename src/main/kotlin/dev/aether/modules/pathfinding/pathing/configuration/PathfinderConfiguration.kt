package dev.aether.modules.pathfinding.pathing.configuration

import dev.aether.modules.pathfinding.pathing.INeighborStrategy
import dev.aether.modules.pathfinding.pathing.NeighborStrategies
import dev.aether.modules.pathfinding.pathing.context.EnvironmentContext
import dev.aether.modules.pathfinding.pathing.heuristic.HeuristicWeights
import dev.aether.modules.pathfinding.pathing.heuristic.IHeuristicStrategy
import dev.aether.modules.pathfinding.pathing.heuristic.LinearHeuristicStrategy
import dev.aether.modules.pathfinding.pathing.processing.NodeProcessor
import dev.aether.modules.pathfinding.provider.NavigationPoint
import dev.aether.modules.pathfinding.provider.NavigationPointProvider
import dev.aether.modules.pathfinding.wrapper.PathPosition
import java.util.Collections

class PathfinderConfiguration internal constructor(b: Builder) {
    @JvmField
    val maxIterations: Int = b.maxIterations

    @JvmField
    val maxLength: Int = b.maxLength

    @JvmField
    val async: Boolean = b.async

    @JvmField
    val fallback: Boolean = b.fallback

    @JvmField
    val provider: NavigationPointProvider = b.provider

    @JvmField
    val heuristicWeights: HeuristicWeights = b.heuristicWeights

    @JvmField
    val processors: List<NodeProcessor> = Collections.unmodifiableList(b.processors)

    @JvmField
    val neighborStrategy: INeighborStrategy = b.neighborStrategy

    @JvmField
    val heuristicStrategy: IHeuristicStrategy = b.heuristicStrategy


    companion object {
        @JvmField
        val DEFAULT: PathfinderConfiguration = Builder().build()

        @JvmStatic
        fun builder(): Builder = Builder()
    }
}

// default provider: everything is traversable
internal class DefaultNavigationPointProvider private constructor() : NavigationPointProvider {
    override fun getNavigationPoint(position: PathPosition, environmentContext: EnvironmentContext?): NavigationPoint =
        object : NavigationPoint {
            override fun isTraversable(): Boolean = true

            override fun hasFloor(): Boolean = true

            override fun getFloorLevel(): Double = 0.0

            override fun isClimbable(): Boolean = false

            override fun isLiquid(): Boolean = false
        }

    companion object {
        @JvmField
        val INSTANCE: DefaultNavigationPointProvider = DefaultNavigationPointProvider()
    }
}

class Builder internal constructor() {
    @JvmField internal var maxIterations: Int = 50000
    @JvmField internal var maxLength: Int = 5000
    @JvmField internal var async: Boolean = false
    @JvmField internal var fallback: Boolean = true
    @JvmField internal var provider: NavigationPointProvider = DefaultNavigationPointProvider.INSTANCE
    @JvmField internal var heuristicWeights: HeuristicWeights = HeuristicWeights.DEFAULT_WEIGHTS
    @JvmField internal var processors: List<NodeProcessor> = listOf()
    @JvmField internal var neighborStrategy: INeighborStrategy = NeighborStrategies.VERTICAL_AND_HORIZONTAL
    @JvmField internal var heuristicStrategy: IHeuristicStrategy = LinearHeuristicStrategy()

    fun maxIterations(v: Int): Builder {
        maxIterations = v
        return this
    }

    fun maxLength(v: Int): Builder {
        maxLength = v
        return this
    }

    fun async(v: Boolean): Builder {
        async = v
        return this
    }

    fun fallback(v: Boolean): Builder {
        fallback = v
        return this
    }

    fun provider(v: NavigationPointProvider): Builder {
        provider = v
        return this
    }

    fun heuristicWeights(v: HeuristicWeights): Builder {
        heuristicWeights = v
        return this
    }

    fun processors(v: List<NodeProcessor>): Builder {
        processors = v
        return this
    }

    fun neighborStrategy(v: INeighborStrategy): Builder {
        neighborStrategy = v
        return this
    }

    fun heuristicStrategy(v: IHeuristicStrategy): Builder {
        heuristicStrategy = v
        return this
    }

    fun build(): PathfinderConfiguration = PathfinderConfiguration(this)
}
