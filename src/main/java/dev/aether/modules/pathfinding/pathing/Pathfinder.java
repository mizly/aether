package dev.aether.modules.pathfinding.pathing;

import dev.aether.modules.pathfinding.pathing.context.EnvironmentContext;
import dev.aether.modules.pathfinding.pathing.result.PathfinderResult;
import dev.aether.modules.pathfinding.wrapper.PathPosition;
import java.util.concurrent.CompletionStage;

public interface Pathfinder {
    default CompletionStage<PathfinderResult> findPath(PathPosition start, PathPosition target) {
        return findPath(start, target, null);
    }

    CompletionStage<PathfinderResult> findPath(PathPosition start, PathPosition target,
                                               EnvironmentContext context);

    void abort();
}
