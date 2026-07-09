package dev.aether.modules.pathfinding.pathfinder.processing;

import dev.aether.modules.pathfinding.pathing.configuration.PathfinderConfiguration;
import dev.aether.modules.pathfinding.pathing.context.EnvironmentContext;
import dev.aether.modules.pathfinding.pathing.processing.context.SearchContext;
import dev.aether.modules.pathfinding.provider.NavigationPointProvider;
import dev.aether.modules.pathfinding.wrapper.PathPosition;
import java.util.HashMap;
import java.util.Map;

public final class SearchContextImpl implements SearchContext {

    private final PathPosition              startPathPosition;
    private final PathPosition              targetPathPosition;
    private final PathfinderConfiguration   pathfinderConfiguration;
    private final NavigationPointProvider   navigationPointProvider;
    private final EnvironmentContext        environmentContext;
    private final Map<String, Object>       sharedData = new HashMap<>();

    public SearchContextImpl(PathPosition start, PathPosition target,
                              PathfinderConfiguration configuration,
                              NavigationPointProvider provider,
                              EnvironmentContext environmentContext) {
        this.startPathPosition      = start;
        this.targetPathPosition     = target;
        this.pathfinderConfiguration= configuration;
        this.navigationPointProvider= provider;
        this.environmentContext     = environmentContext;
    }

    @Override public PathPosition             getStartPathPosition()       { return startPathPosition; }
    @Override public PathPosition             getTargetPathPosition()      { return targetPathPosition; }
    @Override public PathfinderConfiguration  getPathfinderConfiguration() { return pathfinderConfiguration; }
    @Override public NavigationPointProvider  getNavigationPointProvider() { return navigationPointProvider; }
    @Override public Map<String, Object>      getSharedData()              { return sharedData; }
    @Override public EnvironmentContext       getEnvironmentContext()       { return environmentContext; }
}
