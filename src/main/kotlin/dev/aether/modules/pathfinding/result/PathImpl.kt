package dev.aether.modules.pathfinding.result

import dev.aether.modules.pathfinding.pathing.result.Path
import dev.aether.modules.pathfinding.wrapper.PathPosition
import java.util.Collections

class PathImpl(
    private val start: PathPosition,
    private val end: PathPosition,
    positions: Collection<PathPosition>
) : Path {
    private val positions: List<PathPosition> =
        if (positions is List<PathPosition>) positions else java.util.List.copyOf(positions)

    override fun length(): Int = positions.size

    override fun getStart(): PathPosition = start

    override fun getEnd(): PathPosition = end

    override fun collect(): Collection<PathPosition> = Collections.unmodifiableList(positions)

    override fun iterator(): MutableIterator<PathPosition> = positions.toMutableList().iterator()
}
