package dev.aether.modules.pathfinding.pathing.processing.context;

import dev.aether.modules.pathfinding.pathing.configuration.PathfinderConfiguration;
import dev.aether.modules.pathfinding.pathing.context.EnvironmentContext;
import dev.aether.modules.pathfinding.provider.NavigationPointProvider;
import dev.aether.modules.pathfinding.wrapper.PathPosition;
import java.util.Map;

public interface EvaluationContext {
    PathPosition getCurrentPathPosition();
    PathPosition getPreviousPathPosition();       // nullable
    int getCurrentNodeDepth();
    double getCurrentNodeHeuristicValue();
    double getPathCostToPreviousPosition();
    double getBaseTransitionCost();
    SearchContext getSearchContext();
    PathPosition getGrandparentPathPosition();    // nullable
    default PathPosition getGreatGrandparentPathPosition() { return null; }

    default PathfinderConfiguration getPathfinderConfiguration() {
        return getSearchContext().getPathfinderConfiguration();
    }

    default NavigationPointProvider getNavigationPointProvider() {
        return getSearchContext().getNavigationPointProvider();
    }

    default Map<String, Object> getSharedData() {
        return getSearchContext().getSharedData();
    }

    default PathPosition getStartPathPosition() {
        return getSearchContext().getStartPathPosition();
    }

    default PathPosition getTargetPathPosition() {
        return getSearchContext().getTargetPathPosition();
    }

    default EnvironmentContext getEnvironmentContext() {
        return getSearchContext().getEnvironmentContext();
    }
}
