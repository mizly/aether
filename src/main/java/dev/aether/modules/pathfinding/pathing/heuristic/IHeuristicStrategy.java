package dev.aether.modules.pathfinding.pathing.heuristic;

import dev.aether.modules.pathfinding.wrapper.PathPosition;

public interface IHeuristicStrategy {
    double calculate(HeuristicContext context);
    double calculateTransitionCost(PathPosition from, PathPosition to);
}
