package dev.aether.modules.pathfinding.pathing.result

enum class PathState {
    ABORTED,
    FOUND,
    FAILED,
    FALLBACK,
    LENGTH_LIMITED,
    MAX_ITERATIONS_REACHED
}
