package dev.aether.modules.pathfinding.pathfinder

import dev.aether.modules.pathfinding.Node
import dev.aether.modules.pathfinding.Node.MoveType
import dev.aether.modules.pathfinding.debug.PathVisualizer
import dev.aether.modules.pathfinding.pathfinder.heap.PrimitiveMinHeap
import dev.aether.modules.pathfinding.pathfinder.processing.EvaluationContextImpl
import dev.aether.modules.pathfinding.pathing.configuration.PathfinderConfiguration
import dev.aether.modules.pathfinding.pathing.processing.context.EvaluationContext
import dev.aether.modules.pathfinding.pathing.processing.context.SearchContext
import dev.aether.modules.pathfinding.util.RegionKey
import dev.aether.modules.pathfinding.wrapper.PathPosition
import dev.aether.modules.pathfinding.wrapper.PathVector
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import it.unimi.dsi.fastutil.longs.LongSet
import java.util.Locale
import kotlin.math.abs

class AStarPathfinder(configuration: PathfinderConfiguration) : AbstractPathfinder(configuration) {

    private val currentSession = ThreadLocal<PathfindingSession>()

    // Captured after each run for external inspection (pathtest)
    private var lastClosedSet: LongSet = LongOpenHashSet()
    private var exploredCount: Long = 0

    // --- Profiling counters (reset each search) -----------------------------
    private var profNeighborCount: Long = 0
    private var profIsValidNanos: Long = 0
    private var profCostCalcNanos: Long = 0
    private var profNodeCreateNanos: Long = 0
    private var profHeapNanos: Long = 0
    private var profIsValidRejects: Long = 0
    private var profGCostRejects: Long = 0

    override fun insertStartNode(node: Node, fCost: Double, openSet: PrimitiveMinHeap) {
        val session = sessionOrThrow()
        val packedPos = RegionKey.pack(node.position)
        node.inOpen = true
        node.cachedFCost = fCost
        session.nodes.put(packedPos, node)
        openSet.insertOrUpdate(packedPos, fCost)
    }

    override fun extractBestNode(openSet: PrimitiveMinHeap): Node {
        val session = sessionOrThrow()
        val packedPos = openSet.extractMin()
        return session.nodes.get(packedPos)
    }

    override fun initializeSearch() {
        currentSession.set(PathfindingSession())
        profNeighborCount = 0
        profIsValidNanos = 0
        profCostCalcNanos = 0
        profNodeCreateNanos = 0
        profHeapNanos = 0
        profIsValidRejects = 0
        profGCostRejects = 0
    }

    override fun processSuccessors(
        requestStart: PathPosition,
        requestTarget: PathPosition,
        currentNode: Node,
        openSet: PrimitiveMinHeap,
        searchContext: SearchContext
    ) {
        val session = sessionOrThrow()
        val offsets = neighborStrategy.getOffsets(currentNode.position)

        for (offset in offsets) {
            profNeighborCount++
            val neighborPos = currentNode.position.add(offset)
            val packedPos = RegionKey.pack(neighborPos)

            var candidate = session.nodes.get(packedPos)
            if (candidate == null) {
                val t0 = System.nanoTime()
                candidate = createNeighborNode(neighborPos, requestStart, requestTarget, currentNode)
                candidate.moveType = inferMoveType(offset)
                // Sentinel: gCost = POSITIVE_INFINITY means "not yet settled"
                candidate.gCost = Double.POSITIVE_INFINITY
                session.nodes.put(packedPos, candidate)
                profNodeCreateNanos += System.nanoTime() - t0
            }

            // Skip already-expanded nodes (consistent heuristic guarantees optimality on first expansion)
            if (candidate.inClosed) continue

            // Reuse mutable context instead of allocating a new one per neighbor
            session.reusableContext.update(
                searchContext, candidate, currentNode,
                pathfinderConfiguration.heuristicStrategy
            )

            val t1 = System.nanoTime()
            val valid = isValidByProcessors(session.reusableContext)
            profIsValidNanos += System.nanoTime() - t1
            if (!valid) {
                profIsValidRejects++
                continue
            }

            val t2 = System.nanoTime()
            val gCost = calculateGCost(session.reusableContext)
            profCostCalcNanos += System.nanoTime() - t2

            // Reject if not an improvement (POSITIVE_INFINITY for new nodes always passes)
            if (java.lang.Double.isFinite(candidate.gCost) &&
                gCost + gTolerance(gCost, candidate.gCost) >= candidate.gCost
            ) {
                profGCostRejects++
                continue
            }

            candidate.parent = currentNode
            candidate.gCost = gCost
            val fCost = candidate.fCost()
            candidate.cachedFCost = fCost
            val heapKey = calculateHeapKey(candidate, fCost)

            val t3 = System.nanoTime()
            openSet.insertOrUpdate(packedPos, heapKey)
            candidate.inOpen = true
            profHeapNanos += System.nanoTime() - t3
        }
    }

    override fun markNodeAsExpanded(node: Node) {
        val session = sessionOrThrow()
        node.inOpen = false
        node.inClosed = true
        session.expandedCount++
        if (PathVisualizer.shouldCaptureExploredNodes()) {
            session.closedSet.add(RegionKey.pack(node.position))
        }
    }

    override fun performAlgorithmCleanup() {
        val session = currentSession.get()
        if (session != null) {
            exploredCount = session.expandedCount.toLong()
            lastClosedSet = if (PathVisualizer.shouldCaptureExploredNodes()) {
                LongOpenHashSet(session.closedSet)
            } else {
                LongOpenHashSet()
            }
        }
        currentSession.remove()
    }

    // used by PathfindingManager for pathtest visualization
    fun getClosedSet(): LongSet = lastClosedSet

    fun getExploredCount(): Long = exploredCount

    fun getProfilingReport(): String =
        String.format(
            Locale.ROOT,
            "neighbors=%d | isValid=%.0fms (rejects=%d) | costCalc=%.0fms | nodeCreate=%.0fms | heap=%.0fms | gRejects=%d",
            profNeighborCount,
            profIsValidNanos / 1e6, profIsValidRejects,
            profCostCalcNanos / 1e6,
            profNodeCreateNanos / 1e6,
            profHeapNanos / 1e6,
            profGCostRejects
        )

    private fun sessionOrThrow(): PathfindingSession {
        val s = currentSession.get()
        if (s == null) throw IllegalStateException(
            "Pathfinding session not initialized. Call initializeSearch() first."
        )
        return s
    }

    // infers MoveType from the offset vector for PathVisualizer colouring
    private fun createNeighborNode(position: PathPosition, start: PathPosition, target: PathPosition, parent: Node): Node =
        Node(
            position, start, target,
            pathfinderConfiguration.heuristicWeights,
            pathfinderConfiguration.heuristicStrategy,
            parent.depth + 1
        )

    private fun isValidByProcessors(context: EvaluationContext): Boolean {
        for (p in processors) {
            if (!p.isValid(context)) return false
        }
        return true
    }

    private fun calculateGCost(context: EvaluationContext): Double {
        val baseCost = context.getBaseTransitionCost()
        // Direct for-loop instead of stream pipeline - eliminates iterator + pipeline objects
        var additionalCost = 0.0
        for (p in processors) {
            additionalCost += p.calculateCostContribution(context).value
        }
        val transitionCost = Math.max(0.0, baseCost + additionalCost)
        return context.getPathCostToPreviousPosition() + transitionCost
    }

    // node flags replace set membership, and gCost is the best-known cost (infinity = unsettled)
    // closedSet is only filled when PathVisualizer is on, to avoid the gc
    private class PathfindingSession {
        @JvmField
        val nodes: Long2ObjectOpenHashMap<Node> = Long2ObjectOpenHashMap()

        @JvmField
        val closedSet: LongSet = LongOpenHashSet()

        @JvmField
        val reusableContext: EvaluationContextImpl

        @JvmField
        var expandedCount: Int = 0

        init {
            // Initialize with dummy values; will be updated before each use
            reusableContext = EvaluationContextImpl(null, null, null, null)
        }
    }

    companion object {
        private fun gTolerance(a: Double, b: Double): Double {
            if (!java.lang.Double.isFinite(a) || !java.lang.Double.isFinite(b)) return 0.0
            return Math.ulp(Math.max(Math.abs(a), Math.abs(b)))
        }

        private fun inferMoveType(offset: PathVector): MoveType {
            val dy = offset.y.toInt()
            val dx = abs(offset.x.toInt())
            val dz = abs(offset.z.toInt())

            if (dy > 0) return MoveType.STEP_UP
            if (dy < -1) return MoveType.FALL
            if (dy < 0) return MoveType.FALL
            if (dx + dz >= 2) return MoveType.WALK_DIAGONAL
            return MoveType.WALK
        }
    }
}
