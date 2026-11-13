package dev.aether.modules.pathfinding.pathfinder

import dev.aether.modules.pathfinding.Node
import dev.aether.modules.pathfinding.pathfinder.heap.PrimitiveMinHeap
import dev.aether.modules.pathfinding.pathfinder.processing.EvaluationContextImpl
import dev.aether.modules.pathfinding.pathfinder.processing.SearchContextImpl
import dev.aether.modules.pathfinding.pathing.INeighborStrategy
import dev.aether.modules.pathfinding.pathing.Pathfinder
import dev.aether.modules.pathfinding.pathing.configuration.PathfinderConfiguration
import dev.aether.modules.pathfinding.pathing.context.EnvironmentContext
import dev.aether.modules.pathfinding.pathing.processing.NodeProcessor
import dev.aether.modules.pathfinding.pathing.processing.context.SearchContext
import dev.aether.modules.pathfinding.pathing.result.Path
import dev.aether.modules.pathfinding.pathing.result.PathState
import dev.aether.modules.pathfinding.pathing.result.PathfinderResult
import dev.aether.modules.pathfinding.provider.NavigationPointProvider
import dev.aether.modules.pathfinding.result.PathImpl
import dev.aether.modules.pathfinding.result.PathfinderResultImpl
import dev.aether.modules.pathfinding.wrapper.PathPosition
import java.util.Collections
import java.util.LinkedHashSet
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

abstract class AbstractPathfinder(protected val pathfinderConfiguration: PathfinderConfiguration) : Pathfinder {

    protected val navigationPointProvider: NavigationPointProvider = pathfinderConfiguration.provider
    protected val processors: List<NodeProcessor> = pathfinderConfiguration.processors
    protected val neighborStrategy: INeighborStrategy = pathfinderConfiguration.neighborStrategy

    private val abortRequested = AtomicBoolean(false)

    override fun findPath(start: PathPosition, target: PathPosition, context: EnvironmentContext?): CompletionStage<PathfinderResult> {
        abortRequested.set(false)
        return initiatePathing(start, target, context)
    }

    override fun abort() {
        abortRequested.set(true)
    }

    private fun initiatePathing(
        start: PathPosition,
        target: PathPosition,
        environmentContext: EnvironmentContext?
    ): CompletionStage<PathfinderResult> {
        val effectiveStart = start.floor()
        val effectiveTarget = target.floor()

        if (pathfinderConfiguration.async) {
            return CompletableFuture
                .supplyAsync({ executePathingAlgorithm(effectiveStart, effectiveTarget, environmentContext) }, PATHING_EXECUTOR_SERVICE)
                .exceptionally { handlePathingException(start, target, it) }
        } else {
            return try {
                CompletableFuture.completedFuture(
                    executePathingAlgorithm(effectiveStart, effectiveTarget, environmentContext)
                )
            } catch (e: Exception) {
                CompletableFuture.completedFuture(handlePathingException(start, target, e))
            }
        }
    }

    private fun executePathingAlgorithm(
        start: PathPosition,
        target: PathPosition,
        environmentContext: EnvironmentContext?
    ): PathfinderResult {
        initializeSearch()

        val searchContext: SearchContext = SearchContextImpl(
            start, target, pathfinderConfiguration, navigationPointProvider, environmentContext
        )

        try {
            for (p in processors) p.initializeSearch(searchContext)

            val startNode = createStartNode(start, target)
            val startCtx = EvaluationContextImpl(searchContext, startNode, null, pathfinderConfiguration.heuristicStrategy)

            if (processors.stream().anyMatch { !it.isValid(startCtx) }) {
                return PathfinderResultImpl(PathState.FAILED, PathImpl(start, target, EMPTY_PATH_POSITIONS))
            }

            val openSet = PrimitiveMinHeap(1024)
            val startKey = calculateHeapKey(startNode, startNode.fCost())
            insertStartNode(startNode, startKey, openSet)

            var currentDepth = 0
            var bestFallbackNode = startNode

            while (!openSet.isEmpty() && currentDepth < pathfinderConfiguration.maxIterations) {
                currentDepth++

                if (abortRequested.get()) {
                    return createAbortedResult(start, target, bestFallbackNode)
                }

                val currentNode = extractBestNode(openSet)
                markNodeAsExpanded(currentNode)

                if (currentNode.heuristic < bestFallbackNode.heuristic) {
                    bestFallbackNode = currentNode
                }

                if (hasReachedPathLengthLimit(currentNode)) {
                    return PathfinderResultImpl(
                        PathState.LENGTH_LIMITED,
                        reconstructPath(start, target, currentNode)
                    )
                }

                if (currentNode.isTarget(target)) {
                    return PathfinderResultImpl(PathState.FOUND, reconstructPath(start, target, currentNode))
                }

                processSuccessors(start, target, currentNode, openSet, searchContext)
            }

            return determinePostLoopResult(currentDepth, start, target, bestFallbackNode)
        } catch (e: Exception) {
            return PathfinderResultImpl(PathState.FAILED, PathImpl(start, target, EMPTY_PATH_POSITIONS))
        } finally {
            for (p in processors) {
                try {
                    p.finalizeSearch(searchContext)
                } catch (ignored: Exception) {
                }
            }
            performAlgorithmCleanup()
        }
    }

    protected fun calculateHeapKey(neighbor: Node, fCost: Double): Double {
        val heuristic = neighbor.heuristic
        val tieBreaker = TIE_BREAKER_WEIGHT * (heuristic / (Math.abs(fCost) + 1))
        var heapKey = fCost - tieBreaker
        if (heapKey.isNaN() || heapKey.isInfinite()) heapKey = fCost
        return heapKey
    }

    private fun createAbortedResult(start: PathPosition, target: PathPosition, fallbackNode: Node): PathfinderResult {
        abortRequested.set(false)
        return resultWithReconstructedPath(PathState.ABORTED, start, target, fallbackNode)
    }

    private fun handlePathingException(start: PathPosition, target: PathPosition, throwable: Throwable): PathfinderResult =
        failedEmptyResult(start, target)

    protected fun createStartNode(startPos: PathPosition, targetPos: PathPosition): Node =
        Node(
            startPos, startPos, targetPos,
            pathfinderConfiguration.heuristicWeights,
            pathfinderConfiguration.heuristicStrategy, 0
        )

    private fun hasReachedPathLengthLimit(node: Node): Boolean =
        pathfinderConfiguration.maxLength > 0 && node.depth >= pathfinderConfiguration.maxLength

    private fun determinePostLoopResult(
        depthReached: Int,
        start: PathPosition,
        target: PathPosition,
        fallbackNode: Node
    ): PathfinderResult {
        if (depthReached >= pathfinderConfiguration.maxIterations) {
            return resultWithReconstructedPath(PathState.MAX_ITERATIONS_REACHED, start, target, fallbackNode)
        }
        if (pathfinderConfiguration.fallback) {
            return resultWithReconstructedPath(PathState.FALLBACK, start, target, fallbackNode)
        }
        return failedEmptyResult(start, target)
    }

    private fun resultWithReconstructedPath(
        state: PathState,
        start: PathPosition,
        target: PathPosition,
        fallbackNode: Node
    ): PathfinderResult {
        val path = reconstructPath(start, target, fallbackNode)
        return PathfinderResultImpl(state, path)
    }

    private fun failedEmptyResult(start: PathPosition, target: PathPosition): PathfinderResult {
        val path = PathImpl(start, target, EMPTY_PATH_POSITIONS)
        return PathfinderResultImpl(PathState.FAILED, path)
    }

    protected fun reconstructPath(start: PathPosition, target: PathPosition, endNode: Node): Path {
        if (endNode.parent == null && endNode.depth == 0) {
            return PathImpl(start, target, listOf(endNode.position))
        }
        val positions = tracePathPositions(endNode)
        return PathImpl(start, target, positions)
    }

    private fun tracePathPositions(leafNode: Node): List<PathPosition> {
        val positions = ArrayList<PathPosition>()
        var current: Node? = leafNode
        while (current != null) {
            positions.add(current.position)
            current = current.parent
        }
        Collections.reverse(positions)
        return positions
    }

    // -- Abstract methods -----------------------------------------------------

    protected abstract fun insertStartNode(node: Node, fCost: Double, openSet: PrimitiveMinHeap)

    protected abstract fun extractBestNode(openSet: PrimitiveMinHeap): Node

    protected abstract fun initializeSearch()

    protected abstract fun markNodeAsExpanded(node: Node)

    protected abstract fun performAlgorithmCleanup()

    protected abstract fun processSuccessors(
        requestStart: PathPosition,
        requestTarget: PathPosition,
        currentNode: Node,
        openSet: PrimitiveMinHeap,
        searchContext: SearchContext
    )

    companion object {
        private val EMPTY_PATH_POSITIONS: Set<PathPosition> = LinkedHashSet(0)
        private const val TIE_BREAKER_WEIGHT: Double = 1e-6

        private val PATHING_EXECUTOR_SERVICE: ExecutorService =
            Executors.newWorkStealingPool(Math.max(1, Runtime.getRuntime().availableProcessors() / 2))

        init {
            Runtime.getRuntime().addShutdownHook(Thread {
                PATHING_EXECUTOR_SERVICE.shutdown()
                try {
                    if (!PATHING_EXECUTOR_SERVICE.awaitTermination(5, TimeUnit.SECONDS)) {
                        PATHING_EXECUTOR_SERVICE.shutdownNow()
                    }
                } catch (e: InterruptedException) {
                    PATHING_EXECUTOR_SERVICE.shutdownNow()
                    Thread.currentThread().interrupt()
                }
            })
        }
    }
}
