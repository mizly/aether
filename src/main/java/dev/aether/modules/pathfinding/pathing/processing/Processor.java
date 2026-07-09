package dev.aether.modules.pathfinding.pathing.processing;

import dev.aether.modules.pathfinding.pathing.processing.context.SearchContext;

public interface Processor {
    default void initializeSearch(SearchContext context) {}
    default void finalizeSearch(SearchContext context) {}
}
