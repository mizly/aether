package dev.aether.modules.pathfinding.provider;

import dev.aether.modules.pathfinding.pathing.context.EnvironmentContext;
import dev.aether.modules.pathfinding.wrapper.PathPosition;

public interface NavigationPointProvider {
    default NavigationPoint getNavigationPoint(PathPosition position) {
        return getNavigationPoint(position, null);
    }

    NavigationPoint getNavigationPoint(PathPosition position, EnvironmentContext environmentContext);
}
