package dev.aether.modules.pathfinding.pathing

import dev.aether.modules.pathfinding.wrapper.PathPosition
import dev.aether.modules.pathfinding.wrapper.PathVector

fun interface INeighborStrategy {
    fun getOffsets(): Iterable<PathVector>

    fun getOffsets(currentPosition: PathPosition): Iterable<PathVector> = getOffsets()
}
