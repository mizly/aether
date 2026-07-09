package dev.aether.modules.pathfinding.pathing.result;

public enum PathState {
    ABORTED,
    FOUND,
    FAILED,
    FALLBACK,
    LENGTH_LIMITED,
    MAX_ITERATIONS_REACHED
}
