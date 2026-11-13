package dev.aether.modules.pathfinding.pathing

import dev.aether.modules.pathfinding.pathing.context.EnvironmentContext
import dev.aether.modules.pathfinding.pathing.result.PathfinderResult
import dev.aether.modules.pathfinding.wrapper.PathPosition
import java.util.concurrent.CompletionStage

interface Pathfinder {
    fun findPath(start: PathPosition, target: PathPosition): CompletionStage<PathfinderResult> =
        findPath(start, target, null)

    fun findPath(
        start: PathPosition,
        target: PathPosition,
        context: EnvironmentContext?
    ): CompletionStage<PathfinderResult>

    fun abort()
}
