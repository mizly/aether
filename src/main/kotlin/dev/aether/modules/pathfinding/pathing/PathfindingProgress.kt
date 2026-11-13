package dev.aether.modules.pathfinding.pathing

import dev.aether.modules.pathfinding.wrapper.PathPosition

class PathfindingProgress(
    @JvmField val start: PathPosition,
    @JvmField val current: PathPosition,
    @JvmField val target: PathPosition
)
