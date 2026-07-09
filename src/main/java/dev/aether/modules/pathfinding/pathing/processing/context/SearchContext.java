package dev.aether.modules.pathfinding.pathing.processing.context;

import dev.aether.modules.pathfinding.pathing.configuration.PathfinderConfiguration;
import dev.aether.modules.pathfinding.pathing.context.EnvironmentContext;
import dev.aether.modules.pathfinding.provider.NavigationPointProvider;
import dev.aether.modules.pathfinding.wrapper.PathPosition;
import java.util.Map;

public interface SearchContext {
    PathPosition getStartPathPosition();
    PathPosition getTargetPathPosition();
    PathfinderConfiguration getPathfinderConfiguration();
    NavigationPointProvider getNavigationPointProvider();
    Map<String, Object> getSharedData();
    EnvironmentContext getEnvironmentContext();
}
