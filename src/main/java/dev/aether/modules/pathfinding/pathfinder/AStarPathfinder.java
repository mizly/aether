package dev.aether.modules.pathfinding.pathfinder;

import dev.aether.modules.pathfinding.Node;
import dev.aether.modules.pathfinding.Node.MoveType;
import dev.aether.modules.pathfinding.debug.PathVisualizer;
import dev.aether.modules.pathfinding.pathfinder.heap.PrimitiveMinHeap;
import dev.aether.modules.pathfinding.pathfinder.processing.EvaluationContextImpl;
import dev.aether.modules.pathfinding.pathing.configuration.PathfinderConfiguration;
import dev.aether.modules.pathfinding.pathing.processing.context.EvaluationContext;
import dev.aether.modules.pathfinding.pathing.processing.context.SearchContext;
import dev.aether.modules.pathfinding.util.RegionKey;
import dev.aether.modules.pathfinding.wrapper.PathPosition;
import dev.aether.modules.pathfinding.wrapper.PathVector;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

public final class AStarPathfinder extends AbstractPathfinder {

    private final ThreadLocal<PathfindingSession> currentSession = new ThreadLocal<>();

    // Captured after each run for external inspection (pathtest)
    private LongSet lastClosedSet = new LongOpenHashSet();
    private long    exploredCount = 0;

    // --- Profiling counters (reset each search) -----------------------------
    private long profNeighborCount     = 0;
    private long profIsValidNanos      = 0;
    private long profCostCalcNanos     = 0;
    private long profNodeCreateNanos   = 0;
    private long profHeapNanos         = 0;
    private long profIsValidRejects    = 0;
    private long profGCostRejects      = 0;

    public AStarPathfinder(PathfinderConfiguration configuration) {
        super(configuration);
    }

    @Override
    protected void insertStartNode(Node node, double fCost, PrimitiveMinHeap openSet) {
        PathfindingSession session = sessionOrThrow();
        long packedPos = RegionKey.pack(node.position);
        node.inOpen = true;
        node.cachedFCost = fCost;
        session.nodes.put(packedPos, node);
        openSet.insertOrUpdate(packedPos, fCost);
    }

    @Override
    protected Node extractBestNode(PrimitiveMinHeap openSet) {
        PathfindingSession session = sessionOrThrow();
        long packedPos = openSet.extractMin();
        return session.nodes.get(packedPos);
    }

    @Override
    protected void initializeSearch() {
        currentSession.set(new PathfindingSession());
        profNeighborCount = 0;
        profIsValidNanos = 0;
        profCostCalcNanos = 0;
        profNodeCreateNanos = 0;
        profHeapNanos = 0;
        profIsValidRejects = 0;
        profGCostRejects = 0;
    }

    @Override
    protected void processSuccessors(PathPosition requestStart, PathPosition requestTarget,
                                     Node currentNode, PrimitiveMinHeap openSet,
                                     SearchContext searchContext) {
        PathfindingSession session = sessionOrThrow();
        Iterable<PathVector> offsets = neighborStrategy.getOffsets(currentNode.position);

        for (PathVector offset : offsets) {
            profNeighborCount++;
            PathPosition neighborPos = currentNode.position.add(offset);
            long          packedPos  = RegionKey.pack(neighborPos);

            Node candidate = session.nodes.get(packedPos);
            if (candidate == null) {
                long t0 = System.nanoTime();
                candidate = createNeighborNode(neighborPos, requestStart, requestTarget, currentNode);
                candidate.moveType = inferMoveType(offset);
                // Sentinel: gCost = POSITIVE_INFINITY means "not yet settled"
                candidate.gCost = Double.POSITIVE_INFINITY;
                session.nodes.put(packedPos, candidate);
                profNodeCreateNanos += System.nanoTime() - t0;
            }

            // Skip already-expanded nodes (consistent heuristic guarantees optimality on first expansion)
            if (candidate.inClosed) continue;

            // Reuse mutable context instead of allocating a new one per neighbor
            session.reusableContext.update(searchContext, candidate, currentNode,
                    pathfinderConfiguration.heuristicStrategy);

            long t1 = System.nanoTime();
            boolean valid = isValidByProcessors(session.reusableContext);
            profIsValidNanos += System.nanoTime() - t1;
            if (!valid) { profIsValidRejects++; continue; }

            long t2 = System.nanoTime();
            double gCost = calculateGCost(session.reusableContext);
            profCostCalcNanos += System.nanoTime() - t2;

            // Reject if not an improvement (POSITIVE_INFINITY for new nodes always passes)
            if (Double.isFinite(candidate.gCost)
                    && gCost + gTolerance(gCost, candidate.gCost) >= candidate.gCost) {
                profGCostRejects++;
                continue;
            }

            candidate.parent = currentNode;
            candidate.gCost = gCost;
            double fCost   = candidate.fCost();
            candidate.cachedFCost = fCost;
            double heapKey = calculateHeapKey(candidate, fCost);

            long t3 = System.nanoTime();
            openSet.insertOrUpdate(packedPos, heapKey);
            candidate.inOpen = true;
            profHeapNanos += System.nanoTime() - t3;
        }
    }

    private static double gTolerance(double a, double b) {
        if (!Double.isFinite(a) || !Double.isFinite(b)) return 0.0;
        return Math.ulp(Math.max(Math.abs(a), Math.abs(b)));
    }

    private Node createNeighborNode(PathPosition position, PathPosition start,
                                     PathPosition target, Node parent) {
        return new Node(position, start, target,
                pathfinderConfiguration.heuristicWeights,
                pathfinderConfiguration.heuristicStrategy,
                parent.depth + 1);
    }

    private boolean isValidByProcessors(EvaluationContext context) {
        for (var p : processors) {
            if (!p.isValid(context)) return false;
        }
        return true;
    }

    private double calculateGCost(EvaluationContext context) {
        double baseCost       = context.getBaseTransitionCost();
        // Direct for-loop instead of stream pipeline - eliminates iterator + pipeline objects
        double additionalCost = 0.0;
        for (var p : processors) {
            additionalCost += p.calculateCostContribution(context).value;
        }
        double transitionCost = Math.max(0.0, baseCost + additionalCost);
        return context.getPathCostToPreviousPosition() + transitionCost;
    }

    @Override
    protected void markNodeAsExpanded(Node node) {
        PathfindingSession session = sessionOrThrow();
        node.inOpen   = false;
        node.inClosed = true;
        session.expandedCount++;
        if (PathVisualizer.shouldCaptureExploredNodes()) {
            session.closedSet.add(RegionKey.pack(node.position));
        }
    }

    @Override
    protected void performAlgorithmCleanup() {
        PathfindingSession session = currentSession.get();
        if (session != null) {
            exploredCount = session.expandedCount;
            if (PathVisualizer.shouldCaptureExploredNodes()) {
                lastClosedSet = new LongOpenHashSet(session.closedSet);
            } else {
                lastClosedSet = new LongOpenHashSet();
            }
        }
        currentSession.remove();
    }

    /** Used by PathfindingManager for pathtest visualization. */
    public LongSet getClosedSet()    { return lastClosedSet; }
    public long    getExploredCount(){ return exploredCount; }

    /** Profiling data from the last search run. */
    public String getProfilingReport() {
        return String.format(
                "neighbors=%d | isValid=%.0fms (rejects=%d) | costCalc=%.0fms | nodeCreate=%.0fms | heap=%.0fms | gRejects=%d",
                profNeighborCount,
                profIsValidNanos / 1e6, profIsValidRejects,
                profCostCalcNanos / 1e6,
                profNodeCreateNanos / 1e6,
                profHeapNanos / 1e6,
                profGCostRejects);
    }

    private PathfindingSession sessionOrThrow() {
        PathfindingSession s = currentSession.get();
        if (s == null) throw new IllegalStateException(
                "Pathfinding session not initialized. Call initializeSearch() first.");
        return s;
    }

    /** Infer MoveType from the offset vector for PathVisualizer coloring. */
    private static MoveType inferMoveType(PathVector offset) {
        int dy = (int) offset.y;
        int dx = Math.abs((int) offset.x);
        int dz = Math.abs((int) offset.z);

        if (dy > 0)           return MoveType.STEP_UP;
        if (dy < -1)          return MoveType.FALL;
        if (dy < 0)           return MoveType.FALL;
        if (dx + dz >= 2)     return MoveType.WALK_DIAGONAL;
        return MoveType.WALK;
    }

    /**
     * Single map replaces the former openSetNodes + allNodes + bestGByPos triple.
     * Node flags (inOpen, inClosed) replace set membership lookups;
     * Node.gCost IS the best-known g-cost (POSITIVE_INFINITY = not yet settled).
     * The closedSet is only populated when PathVisualizer is enabled to avoid GC overhead.
     */
    private static final class PathfindingSession {
        final Long2ObjectOpenHashMap<Node> nodes      = new Long2ObjectOpenHashMap<>();
        final LongSet                      closedSet  = new LongOpenHashSet();
        final EvaluationContextImpl        reusableContext;
        int                                expandedCount = 0;

        PathfindingSession() {
            // Initialize with dummy values; will be updated before each use
            reusableContext = new EvaluationContextImpl(null, null, null, null);
        }
    }
}
