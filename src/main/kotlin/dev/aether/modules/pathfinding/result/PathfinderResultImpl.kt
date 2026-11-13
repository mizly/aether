package dev.aether.modules.pathfinding.result

import dev.aether.modules.pathfinding.pathing.result.Path
import dev.aether.modules.pathfinding.pathing.result.PathState
import dev.aether.modules.pathfinding.pathing.result.PathfinderResult

class PathfinderResultImpl(
    private val state: PathState,
    private val path: Path
) : PathfinderResult {
    override fun successful(): Boolean = state == PathState.FOUND

    override fun hasFailed(): Boolean = state == PathState.FAILED

    override fun hasFallenBack(): Boolean =
        state == PathState.FALLBACK ||
            state == PathState.MAX_ITERATIONS_REACHED ||
            state == PathState.LENGTH_LIMITED

    override fun getPathState(): PathState = state

    override fun getPath(): Path = path
}
