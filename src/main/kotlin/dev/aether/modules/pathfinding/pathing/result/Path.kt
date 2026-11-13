package dev.aether.modules.pathfinding.pathing.result

import dev.aether.modules.pathfinding.wrapper.PathPosition

interface Path : Iterable<PathPosition> {
    fun length(): Int

    fun getStart(): PathPosition

    fun getEnd(): PathPosition

    fun collect(): Collection<PathPosition>
}
