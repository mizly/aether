package dev.aether.modules.pathfinding.pathfinder;

import dev.aether.modules.pathfinding.Node;
import dev.aether.modules.pathfinding.pathfinder.heap.PrimitiveMinHeap;
import dev.aether.modules.pathfinding.pathfinder.processing.EvaluationContextImpl;
import dev.aether.modules.pathfinding.pathfinder.processing.SearchContextImpl;
import dev.aether.modules.pathfinding.pathing.INeighborStrategy;
import dev.aether.modules.pathfinding.pathing.Pathfinder;
import dev.aether.modules.pathfinding.pathing.configuration.PathfinderConfiguration;
import dev.aether.modules.pathfinding.pathing.context.EnvironmentContext;
import dev.aether.modules.pathfinding.pathing.processing.NodeProcessor;
import dev.aether.modules.pathfinding.pathing.processing.context.SearchContext;
import dev.aether.modules.pathfinding.pathing.result.Path;
import dev.aether.modules.pathfinding.pathing.result.PathState;
import dev.aether.modules.pathfinding.pathing.result.PathfinderResult;
import dev.aether.modules.pathfinding.provider.NavigationPointProvider;
import dev.aether.modules.pathfinding.result.PathImpl;
import dev.aether.modules.pathfinding.result.PathfinderResultImpl;
import dev.aether.modules.pathfinding.wrapper.PathPosition;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class AbstractPathfinder implements Pathfinder {

    private static final Set<PathPosition> EMPTY_PATH_POSITIONS = new LinkedHashSet<>(0);
    private static final double TIE_BREAKER_WEIGHT = 1e-6;

    private static final ExecutorService PATHING_EXECUTOR_SERVICE =
            Executors.newWorkStealingPool(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            PATHING_EXECUTOR_SERVICE.shutdown();
            try {
                if (!PATHING_EXECUTOR_SERVICE.awaitTermination(5, TimeUnit.SECONDS)) {
                    PATHING_EXECUTOR_SERVICE.shutdownNow();
                }
            } catch (InterruptedException e) {
                PATHING_EXECUTOR_SERVICE.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }));
    }

    protected final PathfinderConfiguration pathfinderConfiguration;
    protected final NavigationPointProvider  navigationPointProvider;
    protected final List<NodeProcessor>      processors;
    protected final INeighborStrategy        neighborStrategy;

    private final AtomicBoolean abortRequested = new AtomicBoolean(false);

    protected AbstractPathfinder(PathfinderConfiguration configuration) {
        this.pathfinderConfiguration = configuration;
        this.navigationPointProvider = configuration.provider;
        this.processors              = configuration.processors;
        this.neighborStrategy        = configuration.neighborStrategy;
    }

    @Override
    public CompletionStage<PathfinderResult> findPath(PathPosition start, PathPosition target,
                                                       EnvironmentContext context) {
        abortRequested.set(false);
        return initiatePathing(start, target, context);
    }

    @Override
    public void abort() {
        abortRequested.set(true);
    }

    private CompletionStage<PathfinderResult> initiatePathing(PathPosition start, PathPosition target,
                                                               EnvironmentContext environmentContext) {
        PathPosition effectiveStart  = start.floor();
        PathPosition effectiveTarget = target.floor();

        if (pathfinderConfiguration.async) {
            return CompletableFuture
                    .supplyAsync(() -> executePathingAlgorithm(effectiveStart, effectiveTarget, environmentContext),
                            PATHING_EXECUTOR_SERVICE)
                    .exceptionally(t -> handlePathingException(start, target, t));
        } else {
            try {
                return CompletableFuture.completedFuture(
                        executePathingAlgorithm(effectiveStart, effectiveTarget, environmentContext));
            } catch (Exception e) {
                return CompletableFuture.completedFuture(handlePathingException(start, target, e));
            }
        }
    }

    private PathfinderResult executePathingAlgorithm(PathPosition start, PathPosition target,
                                                      EnvironmentContext environmentContext) {
        initializeSearch();

        SearchContext searchContext = new SearchContextImpl(
                start, target, pathfinderConfiguration, navigationPointProvider, environmentContext);

        try {
            for (NodeProcessor p : processors) p.initializeSearch(searchContext);

            Node startNode = createStartNode(start, target);
            var  startCtx  = new EvaluationContextImpl(searchContext, startNode, null,
                    pathfinderConfiguration.heuristicStrategy);

            if (processors.stream().anyMatch(p -> !p.isValid(startCtx))) {
                return new PathfinderResultImpl(PathState.FAILED,
                        new PathImpl(start, target, EMPTY_PATH_POSITIONS));
            }

            PrimitiveMinHeap openSet = new PrimitiveMinHeap(1024);
            double startKey = calculateHeapKey(startNode, startNode.fCost());
            insertStartNode(startNode, startKey, openSet);

            int  currentDepth    = 0;
            Node bestFallbackNode = startNode;

            while (!openSet.isEmpty() && currentDepth < pathfinderConfiguration.maxIterations) {
                currentDepth++;

                if (abortRequested.get()) {
                    return createAbortedResult(start, target, bestFallbackNode);
                }

                Node currentNode = extractBestNode(openSet);
                markNodeAsExpanded(currentNode);

                if (currentNode.heuristic < bestFallbackNode.heuristic) {
                    bestFallbackNode = currentNode;
                }

                if (hasReachedPathLengthLimit(currentNode)) {
                    return new PathfinderResultImpl(PathState.LENGTH_LIMITED,
                            reconstructPath(start, target, currentNode));
                }

                if (currentNode.isTarget(target)) {
                    return new PathfinderResultImpl(PathState.FOUND,
                            reconstructPath(start, target, currentNode));
                }

                processSuccessors(start, target, currentNode, openSet, searchContext);
            }

            return determinePostLoopResult(currentDepth, start, target, bestFallbackNode);

        } catch (Exception e) {
            return new PathfinderResultImpl(PathState.FAILED,
                    new PathImpl(start, target, EMPTY_PATH_POSITIONS));
        } finally {
            for (NodeProcessor p : processors) {
                try { p.finalizeSearch(searchContext); } catch (Exception ignored) {}
            }
            performAlgorithmCleanup();
        }
    }

    protected double calculateHeapKey(Node neighbor, double fCost) {
        double heuristic  = neighbor.heuristic;
        double tieBreaker = TIE_BREAKER_WEIGHT * (heuristic / (Math.abs(fCost) + 1));
        double heapKey    = fCost - tieBreaker;
        if (Double.isNaN(heapKey) || Double.isInfinite(heapKey)) heapKey = fCost;
        return heapKey;
    }

    private PathfinderResult createAbortedResult(PathPosition start, PathPosition target, Node fallbackNode) {
        abortRequested.set(false);
        return resultWithReconstructedPath(PathState.ABORTED, start, target, fallbackNode);
    }

    private PathfinderResult handlePathingException(PathPosition start, PathPosition target,
                                                     Throwable throwable) {
        return failedEmptyResult(start, target);
    }

    protected Node createStartNode(PathPosition startPos, PathPosition targetPos) {
        return new Node(startPos, startPos, targetPos,
                pathfinderConfiguration.heuristicWeights,
                pathfinderConfiguration.heuristicStrategy, 0);
    }

    private boolean hasReachedPathLengthLimit(Node node) {
        return pathfinderConfiguration.maxLength > 0
                && node.depth >= pathfinderConfiguration.maxLength;
    }

    private PathfinderResult determinePostLoopResult(int depthReached, PathPosition start,
                                                      PathPosition target, Node fallbackNode) {
        if (depthReached >= pathfinderConfiguration.maxIterations) {
            return resultWithReconstructedPath(PathState.MAX_ITERATIONS_REACHED, start, target, fallbackNode);
        }
        if (pathfinderConfiguration.fallback) {
            return resultWithReconstructedPath(PathState.FALLBACK, start, target, fallbackNode);
        }
        return failedEmptyResult(start, target);
    }

    private PathfinderResult resultWithReconstructedPath(PathState state, PathPosition start,
                                                          PathPosition target, Node fallbackNode) {
        Path path = reconstructPath(start, target, fallbackNode);
        return new PathfinderResultImpl(state, path);
    }

    private PathfinderResult failedEmptyResult(PathPosition start, PathPosition target) {
        Path path = new PathImpl(start, target, EMPTY_PATH_POSITIONS);
        return new PathfinderResultImpl(PathState.FAILED, path);
    }

    protected Path reconstructPath(PathPosition start, PathPosition target, Node endNode) {
        if (endNode.parent == null && endNode.depth == 0) {
            return new PathImpl(start, target, List.of(endNode.position));
        }
        List<PathPosition> positions = tracePathPositions(endNode);
        return new PathImpl(start, target, positions);
    }

    private List<PathPosition> tracePathPositions(Node leafNode) {
        List<PathPosition> positions = new ArrayList<>();
        Node current = leafNode;
        while (current != null) {
            positions.add(current.position);
            current = current.parent;
        }
        Collections.reverse(positions);
        return positions;
    }

    // -- Abstract methods -----------------------------------------------------

    protected abstract void insertStartNode(Node node, double fCost, PrimitiveMinHeap openSet);
    protected abstract Node extractBestNode(PrimitiveMinHeap openSet);
    protected abstract void initializeSearch();
    protected abstract void markNodeAsExpanded(Node node);
    protected abstract void performAlgorithmCleanup();
    protected abstract void processSuccessors(PathPosition requestStart, PathPosition requestTarget,
                                              Node currentNode, PrimitiveMinHeap openSet,
                                              SearchContext searchContext);
}
