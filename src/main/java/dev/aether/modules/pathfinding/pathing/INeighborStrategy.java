package dev.aether.modules.pathfinding.pathing;

import dev.aether.modules.pathfinding.wrapper.PathPosition;
import dev.aether.modules.pathfinding.wrapper.PathVector;

@FunctionalInterface
public interface INeighborStrategy {
    Iterable<PathVector> getOffsets();

    default Iterable<PathVector> getOffsets(PathPosition currentPosition) {
        return getOffsets();
    }
}
