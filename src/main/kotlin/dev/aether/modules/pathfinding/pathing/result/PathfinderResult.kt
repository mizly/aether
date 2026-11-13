package dev.aether.modules.pathfinding.pathing.result

interface PathfinderResult {
    fun successful(): Boolean

    fun hasFailed(): Boolean

    fun hasFallenBack(): Boolean

    fun getPathState(): PathState

    fun getPath(): Path
}
