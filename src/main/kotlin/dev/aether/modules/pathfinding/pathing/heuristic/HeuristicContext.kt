package dev.aether.modules.pathfinding.pathing.heuristic

import dev.aether.modules.pathfinding.pathing.PathfindingProgress
import dev.aether.modules.pathfinding.wrapper.PathPosition

class HeuristicContext(
    @JvmField val pathfindingProgress: PathfindingProgress,
    @JvmField val heuristicWeights: HeuristicWeights
) {
    constructor(
        position: PathPosition,
        startPosition: PathPosition,
        targetPosition: PathPosition,
        heuristicWeights: HeuristicWeights
    ) : this(PathfindingProgress(startPosition, position, targetPosition), heuristicWeights)

    fun position(): PathPosition = pathfindingProgress.current

    fun startPosition(): PathPosition = pathfindingProgress.start

    fun targetPosition(): PathPosition = pathfindingProgress.target
}
