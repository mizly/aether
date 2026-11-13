package dev.aether.modules.pathfinding.pathing.processing

import dev.aether.modules.pathfinding.pathing.processing.context.SearchContext

interface Processor {
    fun initializeSearch(context: SearchContext) {}

    fun finalizeSearch(context: SearchContext) {}
}
