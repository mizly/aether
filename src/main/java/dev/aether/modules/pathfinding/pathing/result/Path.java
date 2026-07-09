package dev.aether.modules.pathfinding.pathing.result;

import dev.aether.modules.pathfinding.wrapper.PathPosition;
import java.util.Collection;

public interface Path extends Iterable<PathPosition> {
    int length();
    PathPosition getStart();
    PathPosition getEnd();
    Collection<PathPosition> collect();
}
