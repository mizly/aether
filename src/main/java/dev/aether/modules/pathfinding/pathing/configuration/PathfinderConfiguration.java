package dev.aether.modules.pathfinding.pathing.configuration;

import dev.aether.modules.pathfinding.pathing.INeighborStrategy;
import dev.aether.modules.pathfinding.pathing.NeighborStrategies;
import dev.aether.modules.pathfinding.pathing.context.EnvironmentContext;
import dev.aether.modules.pathfinding.pathing.heuristic.HeuristicWeights;
import dev.aether.modules.pathfinding.pathing.heuristic.IHeuristicStrategy;
import dev.aether.modules.pathfinding.pathing.heuristic.LinearHeuristicStrategy;
import dev.aether.modules.pathfinding.pathing.processing.NodeProcessor;
import dev.aether.modules.pathfinding.provider.NavigationPoint;
import dev.aether.modules.pathfinding.provider.NavigationPointProvider;
import dev.aether.modules.pathfinding.wrapper.PathPosition;
import java.util.Collections;
import java.util.List;

public final class PathfinderConfiguration {

    public final int                  maxIterations;
    public final int                  maxLength;
    public final boolean              async;
    public final boolean              fallback;
    public final NavigationPointProvider provider;
    public final HeuristicWeights     heuristicWeights;
    public final List<NodeProcessor>  processors;
    public final INeighborStrategy    neighborStrategy;
    public final IHeuristicStrategy   heuristicStrategy;

    private PathfinderConfiguration(Builder b) {
        this.maxIterations    = b.maxIterations;
        this.maxLength        = b.maxLength;
        this.async            = b.async;
        this.fallback         = b.fallback;
        this.provider         = b.provider;
        this.heuristicWeights = b.heuristicWeights;
        this.processors       = Collections.unmodifiableList(b.processors);
        this.neighborStrategy = b.neighborStrategy;
        this.heuristicStrategy= b.heuristicStrategy;
    }

    public static final PathfinderConfiguration DEFAULT = new Builder().build();

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private int                  maxIterations    = 50000;
        private int                  maxLength        = 5000;
        private boolean              async            = false;
        private boolean              fallback         = true;
        private NavigationPointProvider provider      = DefaultNavigationPointProvider.INSTANCE;
        private HeuristicWeights     heuristicWeights = HeuristicWeights.DEFAULT_WEIGHTS;
        private List<NodeProcessor>  processors       = List.of();
        private INeighborStrategy    neighborStrategy = NeighborStrategies.VERTICAL_AND_HORIZONTAL;
        private IHeuristicStrategy   heuristicStrategy= new LinearHeuristicStrategy();

        public Builder maxIterations(int v)            { this.maxIterations    = v; return this; }
        public Builder maxLength(int v)                { this.maxLength        = v; return this; }
        public Builder async(boolean v)                { this.async            = v; return this; }
        public Builder fallback(boolean v)             { this.fallback         = v; return this; }
        public Builder provider(NavigationPointProvider v) { this.provider     = v; return this; }
        public Builder heuristicWeights(HeuristicWeights v){ this.heuristicWeights = v; return this; }
        public Builder processors(List<NodeProcessor> v)   { this.processors   = v; return this; }
        public Builder neighborStrategy(INeighborStrategy v){ this.neighborStrategy = v; return this; }
        public Builder heuristicStrategy(IHeuristicStrategy v){ this.heuristicStrategy = v; return this; }

        public PathfinderConfiguration build() { return new PathfinderConfiguration(this); }
    }
}

/** Package-private default provider: everything is traversable. */
final class DefaultNavigationPointProvider implements NavigationPointProvider {
    static final DefaultNavigationPointProvider INSTANCE = new DefaultNavigationPointProvider();
    private DefaultNavigationPointProvider() {}

    @Override
    public NavigationPoint getNavigationPoint(PathPosition position, EnvironmentContext ctx) {
        return new NavigationPoint() {
            @Override public boolean isTraversable() { return true;  }
            @Override public boolean hasFloor()      { return true;  }
            @Override public double  getFloorLevel() { return 0.0;  }
            @Override public boolean isClimbable()   { return false; }
            @Override public boolean isLiquid()      { return false; }
        };
    }
}
