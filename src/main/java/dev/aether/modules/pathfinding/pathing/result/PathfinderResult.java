package dev.aether.modules.pathfinding.pathing.result;

public interface PathfinderResult {
    boolean successful();
    boolean hasFailed();
    boolean hasFallenBack();
    PathState getPathState();
    Path getPath();
}
