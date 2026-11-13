package dev.aether.modules.pathfinding

import dev.aether.config.AetherConfig
import dev.aether.modules.gear.GearManager
import dev.aether.modules.pathfinding.debug.PathVisualizer
import dev.aether.modules.pathfinding.etherwarp.EtherwarpHelper
import dev.aether.modules.pathfinding.execution.EtherwarpExecutor
import dev.aether.modules.pathfinding.execution.FlyExecutor
import dev.aether.modules.pathfinding.execution.FlightGuidance
import dev.aether.modules.pathfinding.execution.FlightPathClearance
import dev.aether.modules.pathfinding.execution.PathExecutor
import dev.aether.modules.pathfinding.movement.FlightCollisionChecker
import dev.aether.modules.pathfinding.movement.FlightPathSmoother
import dev.aether.modules.pathfinding.movement.PathSmoother
import dev.aether.modules.pathfinding.movement.WalkabilityChecker
import dev.aether.modules.pathfinding.pathfinder.AStarPathfinder
import dev.aether.modules.pathfinding.pathfinder.EtherwarpPathfinder
import dev.aether.modules.pathfinding.pathing.NeighborStrategies
import dev.aether.modules.pathfinding.pathing.configuration.PathfinderConfiguration
import dev.aether.modules.pathfinding.pathing.heuristic.HeuristicWeights
import dev.aether.modules.pathfinding.pathing.heuristic.IHeuristicStrategy
import dev.aether.modules.pathfinding.pathing.heuristic.LinearHeuristicStrategy
import dev.aether.modules.pathfinding.pathing.processing.NodeProcessor
import dev.aether.modules.pathfinding.pathing.processing.impl.FlyPathProcessor
import dev.aether.modules.pathfinding.pathing.processing.impl.MinecraftPathProcessor
import dev.aether.modules.pathfinding.pathing.result.PathState
import dev.aether.modules.pathfinding.pathing.result.PathfinderResult
import dev.aether.modules.pathfinding.provider.impl.MinecraftNavigationProvider
import dev.aether.modules.pathfinding.wrapper.PathPosition
import dev.aether.util.ClientUtils
import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import java.util.ArrayList
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.Collection
import kotlin.collections.MutableList

class PathfindingManager private constructor() {
    private enum class NavigationMode { NONE, WALK, FLY, ETHERWARP }

    private class EtherwarpNavigationPlan(private val walkPrefix: kotlin.collections.List<PathPosition>?, private val etherwarpPath: kotlin.collections.List<Node>?, private val exploredCount: Long, private val walkDistance: Double, private val aborted: Boolean) {
        fun walkPrefix(): kotlin.collections.List<PathPosition> = walkPrefix ?: emptyList<PathPosition>()
        fun etherwarpPath(): kotlin.collections.List<Node> = etherwarpPath ?: emptyList<Node>()
        fun exploredCount(): Long = exploredCount
        fun walkDistance(): Double = walkDistance
        fun aborted(): Boolean = aborted

        fun hasPath(): Boolean = etherwarpPath != null && !etherwarpPath.isEmpty()

        fun usesWalkAssist(): Boolean = walkPrefix != null && walkPrefix.size > 1

        override fun toString(): String = "EtherwarpNavigationPlan[walkPrefix=" + walkPrefix + ", etherwarpPath=" + etherwarpPath + ", exploredCount=" + exploredCount + ", walkDistance=" + walkDistance + ", aborted=" + aborted + "]"

        override fun hashCode(): Int {
            var result = walkPrefix?.hashCode() ?: 0
            result = 31 * result + (etherwarpPath?.hashCode() ?: 0)
            result = 31 * result + exploredCount.hashCode()
            result = 31 * result + walkDistance.hashCode()
            result = 31 * result + (if (aborted) 1231 else 1237)
            return result
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || javaClass != other.javaClass) return false
            val that = other as EtherwarpNavigationPlan
            return walkPrefix == that.walkPrefix && etherwarpPath == that.etherwarpPath && exploredCount == that.exploredCount && walkDistance == that.walkDistance && aborted == that.aborted
        }

        companion object {
            fun none(exploredCount: Long): EtherwarpNavigationPlan = EtherwarpNavigationPlan(emptyList<PathPosition>(), emptyList<Node>(), exploredCount, 0.0, false)
            fun aborted(exploredCount: Long): EtherwarpNavigationPlan = EtherwarpNavigationPlan(emptyList<PathPosition>(), emptyList<Node>(), exploredCount, 0.0, true)
            fun pure(etherwarpPath: kotlin.collections.List<Node>, exploredCount: Long): EtherwarpNavigationPlan = EtherwarpNavigationPlan(emptyList<PathPosition>(), etherwarpPath, exploredCount, 0.0, false)
            fun walkAssist(walkPrefix: kotlin.collections.List<PathPosition>, etherwarpPath: kotlin.collections.List<Node>, exploredCount: Long, walkDistance: Double): EtherwarpNavigationPlan = EtherwarpNavigationPlan(walkPrefix.toList(), etherwarpPath.toList(), exploredCount, walkDistance, false)
        }
    }

    private class WalkAssistCandidate(private val prefixEndIndex: Int, private val walkDistance: Double, private val score: Double, private val etherwarpPath: kotlin.collections.List<Node>) {
        fun prefixEndIndex(): Int = prefixEndIndex
        fun walkDistance(): Double = walkDistance
        fun score(): Double = score
        fun etherwarpPath(): kotlin.collections.List<Node> = etherwarpPath
    }

    companion object {
        private val executor = PathExecutor()
        private val flyExecutor = FlyExecutor()
        private val etherwarpExecutor = EtherwarpExecutor()

        private const val ETHERWARP_WALK_ASSIST_BASE_COST = 8.0
        private const val ETHERWARP_WALK_ASSIST_DISTANCE_COST = 4.0
        private const val ETHERWARP_WALK_ASSIST_MAX_DISTANCE = 48.0
        private const val ETHERWARP_WALK_ASSIST_CHECK_SPACING = 4.0
        private const val ETHERWARP_WALK_ASSIST_GOAL_TOLERANCE = 0.2
        private const val ETHERWARP_WALK_ASSIST_MAX_CANDIDATES = 12
        private const val ETHERWARP_REPATH_MAX_RETRIES = 3
        private const val FLY_REPATH_MAX_RETRIES = 3
        private var flyRepathCount = 0

        @Volatile private var navigating = false
        @Volatile private var goalX = 0
        @Volatile private var goalY = 0
        @Volatile private var goalZ = 0
        @Volatile private var activeMode = NavigationMode.NONE
        private var rotationTarget: Entity? = null
        private var walkLookTarget: Vec3? = null
        private var walkFinishedCallback: Runnable? = null
        private var walkFailureCallback: Runnable? = null
        private var etherwarpFinishedCallback: Runnable? = null
        private var etherwarpFailureCallback: Runnable? = null
        private var etherwarpAllowWalkAssist = true
        private var walkAllowReplan = true
        private var walkRequireFullPath = false
        private var walkStrictGoalCompletion = false
        private var walkPreciseGoalTolerance = 0.5
        private var walkStickySneakDistance = -1.0
        private var walkGoalCenterX = 0.5
        private var walkGoalCenterZ = 0.5
        private var walkSneakLatched = false
        private val abortFlag = AtomicBoolean(false)

        // Held so we can abort the current async run
        @Volatile private var currentPathfinder: AStarPathfinder? = null
        // invalidates late async a* results when a newer route already started
        @Volatile private var pathSearchToken = 0L
        @Volatile private var currentEtherwarpPathfinder: EtherwarpPathfinder? = null
        @Volatile private var etherwarpSearchToken = 0L
        @Volatile private var etherwarpRepathCount = 0
        @Volatile private var etherwarpRetryPending = false
        @Volatile private var etherwarpRetryTarget: PathPosition? = null
        @Volatile private var etherwarpSearchInProgress = false

        // --- Tick ----------------------------------------------------------------

        @JvmStatic fun update() {
            val mc = Minecraft.getInstance()
            if (mc.player == null) return

            PathVisualizer.captureCamera(mc)

            // Detect re-plan requests from PathExecutor
            if (activeMode == NavigationMode.WALK && executor.getState() == PathExecutor.State.REPLANNING) {
                walkSneakLatched = executor.isSneakLatched()
                doStartPathfind(mc, goalX, goalY, goalZ, false)
                return
            }

            if (activeMode == NavigationMode.ETHERWARP) {
                if (etherwarpSearchInProgress) {
                    return
                }
                etherwarpExecutor.tick(mc)
                if (etherwarpRetryPending) {
                    etherwarpRetryPending = false
                    val retryTarget = etherwarpRetryTarget
                    etherwarpRetryTarget = null
                    if (retryTarget != null) {
                        restartEtherwarpPathfindFromCurrent(mc, retryTarget)
                        return
                    }
                }
                if (PathVisualizer.shouldRender()) {
                    PathVisualizer.updateExecution(etherwarpExecutor.getWaypointIndex(), -1)
                }
                if (etherwarpExecutor.getState() == EtherwarpExecutor.State.FINISHED || etherwarpExecutor.getState() == EtherwarpExecutor.State.FAILED) {
                    navigating = false
                    activeMode = NavigationMode.NONE
                    clearTransientDebugRenderingIfActive()
                }
                return
            }

            val flyState = flyExecutor.getState()
            val isFlyActive = activeMode == NavigationMode.FLY && (flyState == FlyExecutor.State.FLYING || flyState == FlyExecutor.State.DECELERATING)

            if (isFlyActive) {
                // Always ensure the player is flying while the fly executor is active
                if (mc.player!!.getAbilities().mayfly && !mc.player!!.getAbilities().flying) {
                    mc.player!!.getAbilities().flying = true
                    mc.player!!.onUpdateAbilities()
                }
                flyExecutor.tick(mc)
                if (flyExecutor.getState() == FlyExecutor.State.IDLE && flyRepathCount < FLY_REPATH_MAX_RETRIES) {
                    flyRepathCount++
                    doStartPathfind(mc, goalX, goalY, goalZ, true, true)
                    return
                }
                if (flyExecutor.getState() == FlyExecutor.State.FINISHED || flyExecutor.getState() == FlyExecutor.State.IDLE) {
                    navigating = false
                    activeMode = NavigationMode.NONE
                    clearTransientDebugRenderingIfActive()
                }
            } else if (activeMode == NavigationMode.FLY && currentPathfinder != null) {
                return
            } else if (activeMode == NavigationMode.FLY) {
                // A stuck FlyExecutor stops into IDLE. Treat that as a failed route
                // immediately; otherwise `navigating` remains true with no executor
                // running and the pest controller can wait until its 30s timeout.
                navigating = false
                activeMode = NavigationMode.NONE
                clearTransientDebugRenderingIfActive()
                return
            } else if (activeMode == NavigationMode.WALK) {
                if (currentPathfinder != null) {
                    return
                }
                val searchTokenBeforeTick = pathSearchToken
                executor.tick(mc)
                if (activeMode != NavigationMode.WALK || searchTokenBeforeTick != pathSearchToken) {
                    return
                }
                if (PathVisualizer.shouldRender()) {
                    PathVisualizer.updateExecution(executor.getWaypointIndex(), executor.getCamTargetIdx())
                    val aimPoint = executor.getAimPoint()
                    if (aimPoint != null) {
                        PathVisualizer.updateWalkingTargets(aimPoint, executor.getMovementDirection())
                    }
                }
                if (executor.getState() == PathExecutor.State.FINISHED || executor.getState() == PathExecutor.State.FAILED) {
                    val failureCallback = if (executor.getState() == PathExecutor.State.FAILED) walkFailureCallback else null
                    navigating = false
                    activeMode = NavigationMode.NONE
                    clearTransientDebugRenderingIfActive()
                    if (failureCallback != null) {
                        failureCallback.run()
                    }
                }
            }
        }

        // --- Public API ----------------------------------------------------------

        @JvmStatic fun startPathfind(mc: Minecraft, x: Int, y: Int, z: Int) {
            disableTransientDebugRendering()
            resetWalkExecutionOptions()
            doStartPathfind(mc, x, y, z, false)
        }

        @JvmStatic fun startPathfind(mc: Minecraft, x: Int, y: Int, z: Int, fly: Boolean) {
            disableTransientDebugRendering()
            resetWalkExecutionOptions()
            startPathfind(mc, x, y, z, fly, null)
        }

        @JvmStatic fun startPathfind(mc: Minecraft, x: Int, y: Int, z: Int, fly: Boolean, rotTarget: Entity?) {
            disableTransientDebugRendering()
            resetWalkExecutionOptions()
            rotationTarget = rotTarget
            doStartPathfind(mc, x, y, z, fly)
        }

        @JvmStatic fun startFlyPathfind(mc: Minecraft, x: Int, y: Int, z: Int) {
            disableTransientDebugRendering()
            resetWalkExecutionOptions()
            doStartPathfind(mc, x, y, z, true)
        }

        @JvmStatic fun startEtherwarpPathfind(mc: Minecraft, x: Int, y: Int, z: Int) {
            disableTransientDebugRendering()
            resetWalkExecutionOptions()
            resetEtherwarpExecutionOptions()
            doStartEtherwarpPathfind(mc, x, y, z)
        }

        @JvmStatic fun startDebugPathfind(mc: Minecraft, x: Int, y: Int, z: Int) {
            enableTransientDebugRendering()
            resetWalkExecutionOptions()
            doStartPathfind(mc, x, y, z, false)
        }

        @JvmStatic fun startDebugFlyPathfind(mc: Minecraft, x: Int, y: Int, z: Int) {
            enableTransientDebugRendering()
            resetWalkExecutionOptions()
            doStartPathfind(mc, x, y, z, true)
        }

        @JvmStatic fun startDebugEtherwarpPathfind(mc: Minecraft, x: Int, y: Int, z: Int) {
            enableTransientDebugRendering()
            resetWalkExecutionOptions()
            resetEtherwarpExecutionOptions()
            doStartEtherwarpPathfind(mc, x, y, z)
        }

        @JvmStatic fun startConfiguredEtherwarp(mc: Minecraft, x: Int, y: Int, z: Int, onFinished: Runnable?, onFailed: Runnable?) {
            disableTransientDebugRendering()
            resetWalkExecutionOptions()
            resetEtherwarpExecutionOptions()
            configureEtherwarpExecution(onFinished, onFailed)
            doStartEtherwarpPathfind(mc, x, y, z)
        }

        @JvmStatic fun startConfiguredPureEtherwarp(mc: Minecraft, x: Int, y: Int, z: Int, onFinished: Runnable?, onFailed: Runnable?) {
            disableTransientDebugRendering()
            resetWalkExecutionOptions()
            resetEtherwarpExecutionOptions()
            etherwarpAllowWalkAssist = false
            configureEtherwarpExecution(onFinished, onFailed)
            doStartEtherwarpPathfind(mc, x, y, z)
        }

        @JvmStatic fun startConfiguredWalk(mc: Minecraft, x: Int, y: Int, z: Int, onFinished: Runnable?, onFailed: Runnable?, allowReplan: Boolean, preciseGoalTolerance: Double) {
            startConfiguredWalk(mc, x, y, z, onFinished, onFailed, allowReplan, preciseGoalTolerance, false)
        }

        @JvmStatic fun startConfiguredWalk(mc: Minecraft, x: Int, y: Int, z: Int, onFinished: Runnable?, onFailed: Runnable?, allowReplan: Boolean, preciseGoalTolerance: Double, strictGoalCompletion: Boolean) {
            startConfiguredWalk(mc, x, y, z, onFinished, onFailed, allowReplan, preciseGoalTolerance, strictGoalCompletion, false)
        }

        @JvmStatic fun startConfiguredWalk(mc: Minecraft, x: Int, y: Int, z: Int, onFinished: Runnable?, onFailed: Runnable?, allowReplan: Boolean, preciseGoalTolerance: Double, strictGoalCompletion: Boolean, requireFullPath: Boolean) {
            disableTransientDebugRendering()
            resetWalkExecutionOptions()
            walkRequireFullPath = requireFullPath
            configureWalkExecution(null, onFinished, onFailed, allowReplan, preciseGoalTolerance, strictGoalCompletion)
            doStartPathfind(mc, x, y, z, false)
        }

        @JvmStatic fun startConfiguredWalk(mc: Minecraft, target: Vec3, onFinished: Runnable?, onFailed: Runnable?, allowReplan: Boolean, preciseGoalTolerance: Double, strictGoalCompletion: Boolean, requireFullPath: Boolean) {
            val x = Mth.floor(target.x)
            val y = Mth.floor(target.y)
            val z = Mth.floor(target.z)
            disableTransientDebugRendering()
            resetWalkExecutionOptions()
            walkRequireFullPath = requireFullPath
            walkGoalCenterX = target.x - x
            walkGoalCenterZ = target.z - z
            configureWalkExecution(null, onFinished, onFailed, allowReplan, preciseGoalTolerance, strictGoalCompletion)
            doStartPathfind(mc, x, y, z, false)
        }

        // runs a* without moving - results only show in PathVisualizer
        @JvmStatic fun startPathTest(mc: Minecraft, x: Int, y: Int, z: Int) {
            if (mc.player == null || mc.level == null) return

            if (!PathVisualizer.isEnabled()) PathVisualizer.toggle()
            PathVisualizer.clear()

            val level = mc.level!!
            val checker = WalkabilityChecker(level)
            val sx = Math.floor(mc.player!!.x).toInt()
            val sz = Math.floor(mc.player!!.z).toInt()
            val sy = resolveStartY(checker, mc.player!!.x, mc.player!!.y, mc.player!!.z)

            val finalY = if (checker.isSolid(x, y, z)) y + 1 else y

            if (mc.player != null) {
                ClientUtils.sendMessage("\u00A7ePath test: running A* to " + x + ", " + finalY + ", " + z + "...", false)
            }

            val t = Thread(Runnable {
                val startMs = System.currentTimeMillis()

                val config = PathfinderConfiguration.builder()
                    .provider(MinecraftNavigationProvider(checker))
                    .processors(java.util.List.of<NodeProcessor>(MinecraftPathProcessor(checker, AetherConfig.PATHFINDER_MAX_JUMP_HEIGHT.get().toDouble())))
                    .neighborStrategy(NeighborStrategies.horizontalDiagonalAndVertical(AetherConfig.PATHFINDER_MAX_JUMP_HEIGHT.get()))
                    .maxIterations(300000)
                    .maxLength(300000)
                    .async(true)
                    .fallback(true)
                    .build()

                val pathfinder = AStarPathfinder(config)
                val start = PathPosition(sx.toDouble(), sy.toDouble(), sz.toDouble())
                val target = PathPosition(x.toDouble(), finalY.toDouble(), z.toDouble())

                val result: PathfinderResult?
                result = try {
                    pathfinder.findPath(start, target).toCompletableFuture().join()
                } catch (e: Exception) {
                    null
                }

                // Explored node visualisation
                if (pathfinder.getClosedSet() != null) {
                    for (key in pathfinder.getClosedSet()) {
                        PathVisualizer.addExplored(unpackX(key), unpackY(key), unpackZ(key))
                    }
                }

                val finalResult = result
                val finalChecker = checker
                mc.execute(Runnable {
                    if (finalResult == null) {
                        if (mc.player != null) {
                            ClientUtils.sendMessage("\u00A7cPath test error!", false)
                        }
                        return@Runnable
                    }

                    val typeStr: String
                    var pathLen = 0
                    var pathBlocks = 0.0

                    val positions: Collection<PathPosition> = finalResult.getPath().collect()

                    if (finalResult.successful() || finalResult.hasFallenBack()) {
                        typeStr = if (finalResult.successful()) "\u00A7aFull" else "\u00A7ePartial"
                        val nodes = toNodeList(positions, config)
                        val smoothed = PathSmoother.smooth(nodes, finalChecker)!!
                        val keynodes = collapseAscendingStacks(smoothed)
                        for (n in keynodes) n.isKeynode = true
                        val navPath = insertIntermediates(keynodes, finalChecker)
                        pathLen = navPath.size
                        pathBlocks = computePathLength(nodes)
                        PathVisualizer.setPath(navPath, 0)
                        PathVisualizer.setCameraPath(Collections.emptyList())
                    } else {
                        typeStr = "\u00A7cNone"
                    }

                    val elapsedMs = System.currentTimeMillis() - startMs

                    if (mc.player != null) {
                        ClientUtils.sendMessage("\u00A7ePath test result: " + typeStr + "\u00A76 | explored: " + pathfinder.getExploredCount() + " | waypoints: " + pathLen + String.format(" | dist: %.1f blk", pathBlocks) + " | time: " + elapsedMs + "ms", false)
                        ClientUtils.sendDebugMessage("Path profile: " + pathfinder.getProfilingReport())
                    }
                })
            }, "Aether-PathTest")
            t.isDaemon = true
            t.start()
        }

        @JvmStatic fun startGreenhouseWalk(mc: Minecraft, target: Vec3, onFinished: Runnable?, isFirst: Boolean) {
            if (mc.player == null) return
            val tx = Math.floor(target.x).toInt()
            val ty = Math.floor(target.y).toInt()
            val tz = Math.floor(target.z).toInt()

            rotationTarget = null
            walkRequireFullPath = false
            configureWalkExecution(target.add(0.0, -10.0, 0.0), onFinished, null, !isFirst, 0.25, true)
            walkGoalCenterX = target.x - tx
            walkGoalCenterZ = target.z - tz

            if (isFirst) {
                doStartPathfind(mc, tx, ty, tz, false)
                return
            }

            if (navigating) {
                abortCurrentNavigation(mc)
            }

            abortFlag.set(false)
            navigating = true
            activeMode = NavigationMode.WALK
            goalX = tx
            goalY = ty
            goalZ = tz

            val start = PathPosition(mc.player!!.x, mc.player!!.y, mc.player!!.z)
            val targetPos = PathPosition(target.x, target.y, target.z)
            val nodes = buildLinearWalkPath(start, targetPos)

            PathVisualizer.setPath(nodes, 0)
            executor.start(nodes, tx, ty, tz, true, null, onFinished)
            executor.setAllowRotation(false)
            executor.setAllowReplan(false)
            executor.setAllowJumps(false)
            executor.setExactGoalCentering(true)
            executor.setStrictGoalCompletion(true)
            executor.setStickySneakDistance(walkStickySneakDistance)
            executor.setSneakLatched(walkSneakLatched)
            executor.setGoalCenterOffsets(walkGoalCenterX, walkGoalCenterZ)
            executor.setPreciseGoalTolerance(0.25)

            if (mc.player != null) {
                ClientUtils.sendMessage("\u00A77Greenhouse path: direct walk to target.", false)
            }
        }

        @JvmStatic fun stop() = stop(true)

        @JvmStatic fun stop(announce: Boolean) {
            val wasNavigating = navigating
            navigating = false
            val mc = Minecraft.getInstance()
            abortCurrentNavigation(mc)
            disableTransientDebugRendering()
            PathVisualizer.clear()
            if (announce && wasNavigating && mc.player != null) {
                ClientUtils.sendMessage("\u00A7eNavigation stopped.", false)
            }
        }

        @JvmStatic fun isNavigating(): Boolean = navigating

        @JvmStatic fun isFlyingNavigation(): Boolean = activeMode == NavigationMode.FLY

        @JvmStatic fun isWalkingNavigation(): Boolean = navigating && activeMode == NavigationMode.WALK

        @JvmStatic fun isWalkSneakLatched(): Boolean {
            walkSneakLatched = executor.isSneakLatched()
            return walkSneakLatched
        }

        @JvmStatic fun setWalkSneakLatched(walkSneakLatched: Boolean) {
            PathfindingManager.walkSneakLatched = walkSneakLatched
            executor.setSneakLatched(walkSneakLatched)
        }

        @JvmStatic fun setWalkLookTarget(lookTarget: Vec3?) {
            walkLookTarget = lookTarget
            rotationTarget = null
            executor.setRotationTarget(null)
            executor.setLookTarget(lookTarget)
        }

        @JvmStatic fun setWalkRotationTarget(target: Entity?) {
            rotationTarget = target
            walkLookTarget = null
            executor.setLookTarget(null)
            executor.setRotationTarget(target)
        }

        private fun resetWalkExecutionOptions() {
            rotationTarget = null
            walkLookTarget = null
            walkFinishedCallback = null
            walkFailureCallback = null
            walkAllowReplan = true
            walkRequireFullPath = false
            walkStrictGoalCompletion = false
            walkPreciseGoalTolerance = 0.5
            walkStickySneakDistance = -1.0
            walkGoalCenterX = 0.5
            walkGoalCenterZ = 0.5
            walkSneakLatched = false
        }

        private fun resetEtherwarpExecutionOptions() {
            etherwarpFinishedCallback = null
            etherwarpFailureCallback = null
            etherwarpAllowWalkAssist = true
        }

        private fun enableTransientDebugRendering() {
            PathVisualizer.beginTransientSession()
        }

        private fun disableTransientDebugRendering() {
            PathVisualizer.endTransientSession()
        }

        private fun clearTransientDebugRenderingIfActive() {
            if (!PathVisualizer.isTransientSessionActive()) {
                return
            }
            disableTransientDebugRendering()
            PathVisualizer.clear()
        }

        private fun configureWalkExecution(lookTarget: Vec3?, onFinished: Runnable?, onFailed: Runnable?, allowReplan: Boolean, preciseGoalTolerance: Double, strictGoalCompletion: Boolean) {
            walkLookTarget = lookTarget
            walkFinishedCallback = onFinished
            walkFailureCallback = onFailed
            walkAllowReplan = allowReplan
            walkStrictGoalCompletion = strictGoalCompletion
            walkPreciseGoalTolerance = preciseGoalTolerance
            walkStickySneakDistance = 5.0
        }

        private fun configureEtherwarpExecution(onFinished: Runnable?, onFailed: Runnable?) {
            etherwarpFinishedCallback = onFinished
            etherwarpFailureCallback = onFailed
        }

        private fun abortCurrentNavigation(mc: Minecraft?) {
            pathSearchToken++
            abortFlag.set(true)
            etherwarpRetryPending = false
            etherwarpRetryTarget = null
            etherwarpRepathCount = 0
            etherwarpSearchInProgress = false
            val walkPathfinder = currentPathfinder
            if (walkPathfinder != null) {
                walkPathfinder.abort()
            }
            val etherwarpPathfinder = currentEtherwarpPathfinder
            if (etherwarpPathfinder != null) {
                etherwarpPathfinder.abort()
            }
            executor.stop(mc!!)
            flyExecutor.stop(mc!!)
            etherwarpExecutor.stop(mc!!)
            currentPathfinder = null
            currentEtherwarpPathfinder = null
            activeMode = NavigationMode.NONE
        }

        private fun clearEtherwarpSneakState(mc: Minecraft?) {
            if (mc == null) {
                return
            }
            if (mc.options != null) {
                ClientUtils.setKeyMappingState(mc.options.keyShift, false)
                ClientUtils.setKeyMappingState(mc.options.keyUse, false)
            }
            if (mc.player != null) {
                mc.player!!.setShiftKeyDown(false)
            }
        }

        // --- Internal ------------------------------------------------------------

        private fun doStartPathfind(mc: Minecraft, x: Int, y: Int, z: Int, fly: Boolean) {
            doStartPathfind(mc, x, y, z, fly, false)
        }

        private fun doStartPathfind(mc: Minecraft, x: Int, y: Int, z: Int, fly: Boolean, retry: Boolean) {
            if (!retry) flyRepathCount = 0
            if (navigating || currentPathfinder != null) {
                abortCurrentNavigation(mc)
            }
            PathVisualizer.clear()

            abortFlag.set(false)
            navigating = true
            activeMode = if (fly) NavigationMode.FLY else NavigationMode.WALK
            currentEtherwarpPathfinder = null
            val searchToken = ++pathSearchToken

            // Create checker once; reused for solid-check, pathfinding, and smoothing.
            val sharedChecker = if (mc.level != null) WalkabilityChecker(mc.level!!) else null
            val finalY: Int
            val flightChecker = if (fly && sharedChecker != null) FlightCollisionChecker(sharedChecker) else null
            val flyProcessor = if (flightChecker != null) FlyPathProcessor(flightChecker) else null
            if (flyProcessor != null && !flyProcessor.hasFlightClearance(x, y, z) && flyProcessor.hasFlightClearance(x, y + 1, z)) {
                finalY = y + 1
            } else if (!fly && sharedChecker != null && sharedChecker.isSolid(x, y, z)) {
                finalY = y + 1
            } else {
                finalY = y
            }

            goalX = x
            goalY = finalY
            goalZ = z

            if (mc.player != null && (!fly || PathVisualizer.isTransientSessionActive() && flyRepathCount == 0)) {
                ClientUtils.sendMessage("\u00A7eFinding path to " + x + ", " + finalY + ", " + z + "...", false)
            }

            val target = PathPosition(x.toDouble(), finalY.toDouble(), z.toDouble())

            if (fly) {
                if (sharedChecker == null || flyProcessor == null) {
                    navigating = false
                    activeMode = NavigationMode.NONE
                    clearTransientDebugRenderingIfActive()
                    if (mc.player != null) {
                        ClientUtils.sendMessage("\u00A7cCannot fly pathfind without a loaded world.", false)
                    }
                    return
                }

                val start = flightChecker!!.findStart(mc.player!!.position(), mc.player!!.boundingBox)
                if (start == null) {
                    val announce = PathVisualizer.isTransientSessionActive()
                    navigating = false
                    activeMode = NavigationMode.NONE
                    clearTransientDebugRenderingIfActive()
                    ClientUtils.sendDebugMessage("No reachable fly route start near the player.")
                    if (announce) ClientUtils.sendMessage("\u00A7cNo fly path found!", false)
                    return
                }

                val config = createFlyPathfinderConfiguration(sharedChecker, flyProcessor, true)

                val pathfinder = AStarPathfinder(config)
                currentPathfinder = pathfinder
                val startMs = System.currentTimeMillis()

                pathfinder.findPath(start, target).thenAccept { result ->
                    mc.execute(Runnable {
                        if (abortFlag.get() || searchToken != pathSearchToken || currentPathfinder !== pathfinder) return@Runnable
                        handleFlyResult(mc, result, config, x, finalY, z, startMs, pathfinder)
                    })
                }
            } else {
                val start = PathPosition(Mth.floor(mc.player!!.x).toDouble(), resolveStartY(sharedChecker, mc.player!!.x, mc.player!!.y, mc.player!!.z).toDouble(), Mth.floor(mc.player!!.z).toDouble())
                // Walk pathfinding - async via CompletableFuture work-stealing pool
                // sharedChecker is reused for pathfinding and smoothing (no redundant allocation)
                val config = createWalkPathfinderConfiguration(sharedChecker, true)

                val pathfinder = AStarPathfinder(config)
                currentPathfinder = pathfinder
                val startMs = System.currentTimeMillis()

                pathfinder.findPath(start, target).thenAccept { result ->
                    mc.execute(Runnable {
                        if (abortFlag.get() || searchToken != pathSearchToken || currentPathfinder !== pathfinder) return@Runnable
                        handleWalkResult(mc, result, config, sharedChecker, x, finalY, z, startMs, pathfinder)
                    })
                }
            }
        }

        private fun createWalkPathfinderConfiguration(checker: WalkabilityChecker?, async: Boolean): PathfinderConfiguration {
            return PathfinderConfiguration.builder()
                .provider(MinecraftNavigationProvider(checker))
                .processors(java.util.List.of<NodeProcessor>(MinecraftPathProcessor(checker, AetherConfig.PATHFINDER_MAX_JUMP_HEIGHT.get().toDouble())))
                .neighborStrategy(NeighborStrategies.horizontalDiagonalAndVertical(AetherConfig.PATHFINDER_MAX_JUMP_HEIGHT.get()))
                .maxIterations(300000)
                .maxLength(25000)
                .async(async)
                .fallback(true)
                .build()
        }

        private fun createFlyPathfinderConfiguration(checker: WalkabilityChecker?, flyProcessor: FlyPathProcessor, async: Boolean): PathfinderConfiguration {
            return PathfinderConfiguration.builder()
                .provider(MinecraftNavigationProvider(checker))
                .processors(java.util.List.of<NodeProcessor>(flyProcessor))
                .neighborStrategy(NeighborStrategies.HORIZONTAL_DIAGONAL_AND_VERTICAL)
                .maxIterations(300000)
                .maxLength(50000)
                .async(async)
                .fallback(true)
                .build()
        }

        private fun doStartEtherwarpPathfind(mc: Minecraft, x: Int, y: Int, z: Int) {
            if (mc.player == null || mc.level == null) {
                return
            }
            if (navigating) {
                abortCurrentNavigation(mc)
            }
            PathVisualizer.clear()

            val checker = WalkabilityChecker(mc.level!!)
            val target = EtherwarpHelper.resolveTargetFeet(checker, x, y, z)
            if (target == null) {
                ClientUtils.sendMessage("\u00A7cEtherwarp target must have enough crouched headroom above it.", false)
                etherwarpFailureCallback?.run()
                return
            }
            if (GearManager.findEtherwarpAspectOfTheVoidHotbarSlot(mc) < 0) {
                ClientUtils.sendMessage("\u00A7cNo hotbar AOTV with Ether Transmission found.", false)
                etherwarpFailureCallback?.run()
                return
            }

            etherwarpRepathCount = 0
            etherwarpRetryPending = false
            etherwarpRetryTarget = null
            startEtherwarpPathSearch(mc, target, false)
        }

        private fun startEtherwarpPathSearch(mc: Minecraft, target: PathPosition?, retry: Boolean) {
            if (mc.player == null || mc.level == null || target == null) {
                return
            }

            val checker = WalkabilityChecker(mc.level!!)
            if (!EtherwarpHelper.isValidLandingFeet(checker, target)) {
                clearEtherwarpSneakState(mc)
                navigating = false
                activeMode = NavigationMode.NONE
                clearTransientDebugRenderingIfActive()
                if (mc.player != null) {
                    ClientUtils.sendMessage("\u00A7cEtherwarp target no longer has enough crouched headroom.", false)
                }
                etherwarpFailureCallback?.run()
                return
            }

            abortFlag.set(false)
            navigating = true
            activeMode = NavigationMode.ETHERWARP
            etherwarpSearchInProgress = true
            currentPathfinder = null

            goalX = target.flooredX()
            goalY = target.flooredY()
            goalZ = target.flooredZ()

            val startY = resolveStartY(checker, mc.player!!.x, mc.player!!.y, mc.player!!.z)
            val start = PathPosition(Math.floor(mc.player!!.x), startY.toDouble(), Math.floor(mc.player!!.z))
            val searchToken = ++etherwarpSearchToken

            if (mc.player != null) {
                val message = if (retry) String.format("\u00A7eRepathing etherwarp route from current position (%d/%d)...", etherwarpRepathCount, ETHERWARP_REPATH_MAX_RETRIES) else "\u00A7eFinding etherwarp path to " + goalX + ", " + goalY + ", " + goalZ + "..."
                ClientUtils.sendMessage(message, false)
            }

            val startMs = System.currentTimeMillis()
            val allowWalkAssist = etherwarpAllowWalkAssist
            CompletableFuture.supplyAsync { computeEtherwarpNavigationPlan(mc, checker, start, target, allowWalkAssist) }
                .thenAccept { plan ->
                    mc.execute(Runnable {
                        if (abortFlag.get() || searchToken != etherwarpSearchToken) {
                            return@Runnable
                        }
                        handleEtherwarpResult(mc, checker, plan, start, target, startMs, searchToken)
                    })
                }
        }

        private fun handleEtherwarpResult(mc: Minecraft, checker: WalkabilityChecker, plan: EtherwarpNavigationPlan?, start: PathPosition, target: PathPosition, startMs: Long, searchToken: Long) {
            currentPathfinder = null
            currentEtherwarpPathfinder = null
            etherwarpSearchInProgress = false
            if (plan == null || plan.aborted()) {
                clearEtherwarpSneakState(mc)
                navigating = false
                activeMode = NavigationMode.NONE
                clearTransientDebugRenderingIfActive()
                return
            }

            if (!plan.hasPath()) {
                clearEtherwarpSneakState(mc)
                navigating = false
                activeMode = NavigationMode.NONE
                clearTransientDebugRenderingIfActive()
                if (mc.player != null) {
                    ClientUtils.sendMessage("\u00A7cNo etherwarp path found!", false)
                }
                etherwarpFailureCallback?.run()
                return
            }

            val path = ArrayList(plan.etherwarpPath())
            if (path.isEmpty() || !sameBlock(path[0], createSyntheticNode(start, Node.MoveType.ETHERWARP))) {
                val pathStart = if (plan.usesWalkAssist()) plan.walkPrefix().last() else start
                path.add(0, createSyntheticNode(pathStart, Node.MoveType.ETHERWARP))
            }
            for (node in path) {
                node.moveType = Node.MoveType.ETHERWARP
                node.isKeynode = true
            }

            val elapsedMs = System.currentTimeMillis() - startMs
            val pathBlocks = plan.walkDistance() + computePathLength(path)
            if (mc.player != null) {
                val assistPrefix = if (plan.usesWalkAssist()) String.format("walk %.1f blk -> ", plan.walkDistance()) else ""
                ClientUtils.sendMessage("\u00A7aEtherwarp path found (" + assistPrefix + Math.max(0, path.size - 1) + " warps" + " | explored: " + plan.exploredCount() + String.format(" | dist: %.1f blk", pathBlocks) + " | time: " + elapsedMs + "ms)", false)
            }

            if (!plan.usesWalkAssist() && (path.size <= 1 || start == target)) {
                clearEtherwarpSneakState(mc)
                navigating = false
                activeMode = NavigationMode.NONE
                clearTransientDebugRenderingIfActive()
                etherwarpFinishedCallback?.run()
                return
            }

            if (plan.usesWalkAssist()) {
                startWalkAssistExecution(mc, checker, plan, target, searchToken)
                return
            }

            startEtherwarpExecution(mc, path.toList(), target)
        }

        private fun computeEtherwarpNavigationPlan(mc: Minecraft, checker: WalkabilityChecker, start: PathPosition, target: PathPosition, allowWalkAssist: Boolean): EtherwarpNavigationPlan {
            var exploredCount = 0L

            val purePathfinder = EtherwarpPathfinder(mc, checker)
            currentPathfinder = null
            currentEtherwarpPathfinder = purePathfinder
            val pureResult = purePathfinder.findPathSync(start, target)
            exploredCount += purePathfinder.getExploredCount()
            if (abortFlag.get()) {
                return EtherwarpNavigationPlan.aborted(exploredCount)
            }
            if (pureResult != null && pureResult.path() != null && !pureResult.path().isEmpty()) {
                return EtherwarpNavigationPlan.pure(pureResult.path().toList(), exploredCount)
            }
            if (!allowWalkAssist) {
                return EtherwarpNavigationPlan.none(exploredCount)
            }

            val walkConfig = createWalkPathfinderConfiguration(checker, false)
            val walkPathfinder = AStarPathfinder(walkConfig)
            currentPathfinder = walkPathfinder
            currentEtherwarpPathfinder = null

            val walkResult: PathfinderResult?
            try {
                walkResult = walkPathfinder.findPath(start, target).toCompletableFuture().join()
            } catch (ignored: Exception) {
                return EtherwarpNavigationPlan.none(exploredCount + walkPathfinder.getExploredCount())
            }

            exploredCount += walkPathfinder.getExploredCount()
            recordExploredNodes(walkPathfinder)
            if (abortFlag.get()) {
                return EtherwarpNavigationPlan.aborted(exploredCount)
            }

            if (walkResult == null || walkResult.getPathState() == PathState.ABORTED) {
                return if (walkResult != null && walkResult.getPathState() == PathState.ABORTED) EtherwarpNavigationPlan.aborted(exploredCount) else EtherwarpNavigationPlan.none(exploredCount)
            }

            val hasWalkPath = walkResult.successful() || walkResult.hasFallenBack()
            val walkPositions: Collection<PathPosition> = walkResult.getPath().collect()
            if (!hasWalkPath || walkPositions.isEmpty()) {
                return EtherwarpNavigationPlan.none(exploredCount)
            }

            val walkPath = ArrayList(walkPositions)
            var walkDistance = 0.0
            var lastCheckedDistance = 0.0
            var checkedCandidates = 0
            var bestCandidate: WalkAssistCandidate? = null

            for (i in 1 until walkPath.size) {
                val previous = walkPath[i - 1]
                val candidatePos = walkPath[i]
                walkDistance += previous.distance(candidatePos)
                if (walkDistance > ETHERWARP_WALK_ASSIST_MAX_DISTANCE) {
                    break
                }
                if (checkedCandidates >= ETHERWARP_WALK_ASSIST_MAX_CANDIDATES) {
                    break
                }
                if (!shouldCheckWalkAssistCandidate(walkPath, i, walkDistance, lastCheckedDistance)) {
                    continue
                }

                val candidatePathfinder = EtherwarpPathfinder(mc, checker)
                currentEtherwarpPathfinder = candidatePathfinder
                val candidateResult = candidatePathfinder.findPathSync(candidatePos, target)
                val candidateExplored = candidatePathfinder.getExploredCount()
                exploredCount += candidateExplored
                checkedCandidates++
                lastCheckedDistance = walkDistance

                if (abortFlag.get()) {
                    return EtherwarpNavigationPlan.aborted(exploredCount)
                }
                if (candidateResult == null || candidateResult.path() == null || candidateResult.path().isEmpty()) {
                    continue
                }

                val score = ETHERWARP_WALK_ASSIST_BASE_COST + walkDistance * ETHERWARP_WALK_ASSIST_DISTANCE_COST + Math.max(0, candidateResult.path().size - 1)
                if (bestCandidate == null || score < bestCandidate.score()) {
                    bestCandidate = WalkAssistCandidate(i, walkDistance, score, candidateResult.path().toList())
                }
            }

            currentEtherwarpPathfinder = null
            currentPathfinder = null
            if (bestCandidate == null) {
                return EtherwarpNavigationPlan.none(exploredCount)
            }

            val walkPrefix: kotlin.collections.List<PathPosition> = walkPath.subList(0, bestCandidate.prefixEndIndex() + 1).toList()
            return EtherwarpNavigationPlan.walkAssist(walkPrefix, bestCandidate.etherwarpPath(), exploredCount, bestCandidate.walkDistance())
        }

        private fun shouldCheckWalkAssistCandidate(walkPath: kotlin.collections.List<PathPosition>, candidateIndex: Int, walkDistance: Double, lastCheckedDistance: Double): Boolean {
            if (candidateIndex >= walkPath.size - 1) {
                return true
            }
            if (walkDistance <= 8.0) {
                return true
            }
            return walkDistance - lastCheckedDistance >= ETHERWARP_WALK_ASSIST_CHECK_SPACING
        }

        private fun startWalkAssistExecution(mc: Minecraft, checker: WalkabilityChecker, plan: EtherwarpNavigationPlan, finalTarget: PathPosition, searchToken: Long) {
            val walkNodes = toNodeList(plan.walkPrefix(), PathfinderConfiguration.DEFAULT)
            val smoothed = PathSmoother.smooth(walkNodes, checker)!!
            val keynodes = collapseAscendingStacks(smoothed)
            for (node in keynodes) {
                node.isKeynode = true
            }
            val navPath = insertIntermediates(keynodes, checker)
            val launch = plan.walkPrefix().last()

            goalX = launch.flooredX()
            goalY = launch.flooredY()
            goalZ = launch.flooredZ()
            activeMode = NavigationMode.WALK

            PathVisualizer.setPath(navPath, 0)
            PathVisualizer.setCameraPath(Collections.emptyList())

            if (mc.player != null) {
                ClientUtils.sendMessage(String.format("\u00A7eWalking %.1f blocks to an etherwarp launch point...", plan.walkDistance()), false)
            }

            resetWalkExecutionOptions()
            configureWalkExecution(null, Runnable {
                mc.execute(Runnable {
                    if (abortFlag.get() || searchToken != etherwarpSearchToken) {
                        return@Runnable
                    }
                    val etherwarpPath = ArrayList(plan.etherwarpPath())
                    if (etherwarpPath.isEmpty() || etherwarpPath.size <= 1) {
                        clearEtherwarpSneakState(mc)
                        navigating = false
                        activeMode = NavigationMode.NONE
                        clearTransientDebugRenderingIfActive()
                        return@Runnable
                    }
                    startEtherwarpExecution(mc, etherwarpPath.toList(), finalTarget)
                })
            }, etherwarpFailureCallback, true, ETHERWARP_WALK_ASSIST_GOAL_TOLERANCE, false)
            walkStickySneakDistance = -1.0
            val finishedCallback = walkFinishedCallback
            executor.start(navPath, launch.flooredX(), launch.flooredY(), launch.flooredZ(), true, null, finishedCallback)
            executor.setAllowRotation(true)
            executor.setAllowReplan(true)
            executor.setPreciseGoalTolerance(ETHERWARP_WALK_ASSIST_GOAL_TOLERANCE)
            executor.setExactGoalCentering(true)
            executor.setStickySneakDistance(-1.0)
            executor.setSneakLatched(false)
            executor.setGoalCenterOffsets(0.5, 0.5)
            PathVisualizer.setCameraPath(executor.getCameraPath())
            PathVisualizer.updateExecution(executor.getWaypointIndex(), executor.getCamTargetIdx())
        }

        private fun startEtherwarpExecution(mc: Minecraft, path: kotlin.collections.List<Node>, target: PathPosition) {
            goalX = target.flooredX()
            goalY = target.flooredY()
            goalZ = target.flooredZ()
            activeMode = NavigationMode.ETHERWARP

            PathVisualizer.setPath(path, Math.min(1, Math.max(0, path.size - 1)))
            PathVisualizer.setCameraPath(Collections.emptyList())

            etherwarpExecutor.start(path.toList(), etherwarpFinishedCallback) { reason -> handleEtherwarpExecutionFailure(mc, target, reason) }
            PathVisualizer.updateExecution(etherwarpExecutor.getWaypointIndex(), -1)
        }

        private fun handleEtherwarpExecutionFailure(mc: Minecraft?, target: PathPosition, reason: EtherwarpExecutor.FailureReason): Boolean {
            if (reason == EtherwarpExecutor.FailureReason.LOST_LINE_OF_SIGHT || reason == EtherwarpExecutor.FailureReason.WARP_TIMEOUT) {
                val failureLabel = if (reason == EtherwarpExecutor.FailureReason.LOST_LINE_OF_SIGHT) "lost line of sight" else "timed out"
                if (etherwarpRepathCount >= ETHERWARP_REPATH_MAX_RETRIES) {
                    if (mc != null && mc.player != null) {
                        ClientUtils.sendMessage("\u00A7cEtherwarp " + failureLabel + " after " + ETHERWARP_REPATH_MAX_RETRIES + " replans. Cancelling.", false)
                    }
                    navigating = false
                    activeMode = NavigationMode.NONE
                    clearTransientDebugRenderingIfActive()
                    etherwarpFailureCallback?.run()
                    return true
                }

                etherwarpRepathCount++
                etherwarpRetryTarget = target
                etherwarpRetryPending = true
                return true
            }

            navigating = false
            activeMode = NavigationMode.NONE
            clearTransientDebugRenderingIfActive()
            if (etherwarpFailureCallback != null) {
                etherwarpFailureCallback!!.run()
                return true
            }
            return false
        }

        private fun restartEtherwarpPathfindFromCurrent(mc: Minecraft?, target: PathPosition?) {
            if (mc == null || target == null) {
                return
            }
            etherwarpSearchInProgress = false
            etherwarpExecutor.stop(mc)
            currentPathfinder = null
            currentEtherwarpPathfinder = null
            startEtherwarpPathSearch(mc, target, true)
        }

        private fun handleWalkResult(mc: Minecraft, result: PathfinderResult, config: PathfinderConfiguration, checker: WalkabilityChecker?, x: Int, y: Int, z: Int, startMs: Long, pathfinder: AStarPathfinder) {
            currentPathfinder = null
            val exploredCount = pathfinder.getExploredCount()
            recordExploredNodes(pathfinder)
            val positions: Collection<PathPosition> = result.getPath().collect()
            val hasPath = result.successful() || result.hasFallenBack()

            if (!hasPath || positions.isEmpty()) {
                navigating = false
                activeMode = NavigationMode.NONE
                clearTransientDebugRenderingIfActive()
                if (mc.player != null) {
                    ClientUtils.sendMessage("\u00A7cNo path found!", false)
                }
                walkFailureCallback?.run()
                return
            }

            if (result.getPathState() == PathState.ABORTED) {
                navigating = false
                activeMode = NavigationMode.NONE
                clearTransientDebugRenderingIfActive()
                return
            }

            if (walkRequireFullPath && !result.successful()) {
                navigating = false
                activeMode = NavigationMode.NONE
                clearTransientDebugRenderingIfActive()
                if (mc.player != null) {
                    ClientUtils.sendMessage("\u00A7eWalk path result was partial. Falling back to the next recovery path...", false)
                }
                walkFailureCallback?.run()
                return
            }

            val nodes = toNodeList(positions, config)
            val smoothed = PathSmoother.smooth(nodes, checker)!!
            // Use the smooth path directly - subsampleKeynodes would drop detour nodes whose
            // direction change is < 25 deg, creating chords that cut through obstacles.
            val keynodes = collapseAscendingStacks(smoothed)
            for (n in keynodes) n.isKeynode = true
            val navPath = insertIntermediates(keynodes, checker)
            PathVisualizer.setPath(navPath, 0)
            PathVisualizer.setCameraPath(Collections.emptyList())

            val resultTypeStr = if (result.successful()) "\u00A7aFull" else "\u00A7ePartial"
            val elapsedMs = System.currentTimeMillis() - startMs
            val pathLen = nodes.size
            val pathBlocks = computePathLength(nodes)

            val walkingColor = if (result.successful()) "\u00A7a" else "\u00A7e"
            if (mc.player != null) {
                ClientUtils.sendMessage("\u00A7ePath result: " + resultTypeStr + "\u00A7e | explored: " + exploredCount + " | waypoints: " + pathLen + String.format(" | dist: %.1f blk", pathBlocks) + " | time: " + elapsedMs + "ms", false)
                ClientUtils.sendMessage(walkingColor + "Path found (" + nodes.size + " waypoints). Walking...", false)
            }

            executor.start(navPath, x, y, z, walkPreciseGoalTolerance != 0.5, rotationTarget, walkFinishedCallback)
            executor.setAllowRotation(true)
            executor.setAllowReplan(walkAllowReplan)
            executor.setStrictGoalCompletion(walkStrictGoalCompletion)
            executor.setPreciseGoalTolerance(walkPreciseGoalTolerance)
            executor.setExactGoalCentering(walkPreciseGoalTolerance != 0.5)
            executor.setStickySneakDistance(walkStickySneakDistance)
            executor.setSneakLatched(walkSneakLatched)
            executor.setGoalCenterOffsets(walkGoalCenterX, walkGoalCenterZ)
            if (walkLookTarget != null) {
                executor.setLookTarget(walkLookTarget)
            }
            PathVisualizer.setCameraPath(executor.getCameraPath())
            PathVisualizer.updateExecution(executor.getWaypointIndex(), executor.getCamTargetIdx())
        }

        private fun handleFlyResult(mc: Minecraft, result: PathfinderResult, config: PathfinderConfiguration, x: Int, y: Int, z: Int, startMs: Long, pathfinder: AStarPathfinder) {
            currentPathfinder = null
            val exploredCount = pathfinder.getExploredCount()
            recordExploredNodes(pathfinder)
            val positions: Collection<PathPosition> = result.getPath().collect()
            val hasPath = result.successful() || result.hasFallenBack()

            if (result.getPathState() == PathState.ABORTED) {
                navigating = false
                activeMode = NavigationMode.NONE
                clearTransientDebugRenderingIfActive()
                return
            }

            if (!hasPath || positions.isEmpty()) {
                val announce = PathVisualizer.isTransientSessionActive()
                navigating = false
                activeMode = NavigationMode.NONE
                clearTransientDebugRenderingIfActive()
                ClientUtils.sendDebugMessage("No fly path found.")
                if (mc.player != null && announce) {
                    ClientUtils.sendMessage("\u00A7cNo fly path found!", false)
                }
                return
            }

            val nodes = toNodeList(positions, config)
            for (node in nodes) {
                node.moveType = Node.MoveType.FLY
            }
            val smoothed = smoothFlyPath(mc, nodes)
            for (node in smoothed) {
                node.moveType = Node.MoveType.FLY
                node.isKeynode = true
            }

            if (smoothed.isEmpty()) {
                val announce = PathVisualizer.isTransientSessionActive()
                navigating = false
                activeMode = NavigationMode.NONE
                clearTransientDebugRenderingIfActive()
                ClientUtils.sendDebugMessage("Fly path build failed.")
                if (mc.player != null && announce) {
                    ClientUtils.sendMessage("\u00A7cFly path build failed!", false)
                }
                return
            }

            PathVisualizer.setPath(smoothed, 0)
            PathVisualizer.setCameraPath(Collections.emptyList())

            val resultTypeStr = if (result.successful()) "\u00A7aFull" else "\u00A7ePartial"
            val elapsedMs = System.currentTimeMillis() - startMs
            val pathBlocks = computePathLength(nodes)

            ClientUtils.sendDebugMessage("fly path built: " + smoothed.size + " waypoint(s), " + exploredCount + " explored, " + elapsedMs + "ms search")
            if (mc.player != null && PathVisualizer.isTransientSessionActive() && flyRepathCount == 0) {
                ClientUtils.sendMessage("\u00A7eFly path result: " + resultTypeStr + "\u00A7e | explored: " + exploredCount + " | waypoints: " + smoothed.size + String.format(" | dist: %.1f blk", pathBlocks) + " | time: " + elapsedMs + "ms", false)
                ClientUtils.sendMessage("\u00A7aFly path found (" + smoothed.size + " waypoints). Flying...", false)
            }

            flyExecutor.start(smoothed, x, y, z)
        }

        private fun buildLinearWalkPath(start: PathPosition, target: PathPosition): kotlin.collections.List<Node> {
            val nodes = ArrayList<Node>()
            val x0 = start.flooredX()
            val y0 = start.flooredY()
            val z0 = start.flooredZ()
            val x1 = target.flooredX()
            val y1 = target.flooredY()
            val z1 = target.flooredZ()

            val dx = Math.abs(x1 - x0)
            val dy = Math.abs(y1 - y0)
            val dz = Math.abs(z1 - z0)
            val steps = Math.max(Math.max(dx, dy), dz)

            if (steps == 0) {
                nodes.add(makeWalkNode(x0, y0, z0))
                return nodes.toList()
            }

            for (i in 0..steps) {
                val t = i.toDouble() / steps
                val nx = Math.round(x0 + t * (x1 - x0)).toInt()
                val ny = Math.round(y0 + t * (y1 - y0)).toInt()
                val nz = Math.round(z0 + t * (z1 - z0)).toInt()
                if (!nodes.isEmpty()) {
                    val prev = nodes[nodes.size - 1]
                    if (prev.position.flooredX() == nx && prev.position.flooredY() == ny && prev.position.flooredZ() == nz) {
                        continue
                    }
                }
                nodes.add(makeWalkNode(nx, ny, nz))
            }

            for (i in 1 until nodes.size) {
                nodes[i].moveType = inferMoveType(nodes[i - 1].position, nodes[i].position)
            }
            return nodes.toList()
        }

        private val ZERO_HEURISTIC: IHeuristicStrategy = LinearHeuristicStrategy()

        private fun makeWalkNode(x: Int, y: Int, z: Int): Node {
            val pos = PathPosition(x.toDouble(), y.toDouble(), z.toDouble())
            val node = Node(pos, pos, pos, HeuristicWeights(0.0, 0.0, 0.0, 0.0), ZERO_HEURISTIC, 0)
            node.moveType = Node.MoveType.WALK
            return node
        }

        @JvmStatic fun createSyntheticNode(position: PathPosition, moveType: Node.MoveType): Node {
            val node = Node(position)
            node.moveType = moveType
            return node
        }

        // thin floors like carpet keep the player on the current block, slabs and stairs bump the start node into the air block above
        private fun resolveStartY(checker: WalkabilityChecker?, playerX: Double, playerY: Double, playerZ: Double): Int {
            val x = Mth.floor(playerX)
            val y = Mth.floor(playerY)
            val z = Mth.floor(playerZ)
            if (checker == null) {
                return y
            }
            if (checker.isPassable(x, y, z)) {
                return y
            }
            if (checker.isPassable(x, y + 1, z)) {
                return y + 1
            }
            return Mth.ceil(playerY)
        }

        private fun smoothFlyPath(mc: Minecraft, path: kotlin.collections.List<Node>): kotlin.collections.List<Node> {
            if (mc.level == null) return emptyList<Node>()
            return FlightPathSmoother.smooth(path) { from, to -> hasFreePath(mc, from, to) }
        }

        private fun hasFreePath(mc: Minecraft, from: PathPosition, to: PathPosition): Boolean {
            return FlightPathClearance.isClear(mc, FlightGuidance.waypoint(from.flooredX(), from.flooredY(), from.flooredZ()), FlightGuidance.waypoint(to.flooredX(), to.flooredY(), to.flooredZ()), 0.05)
        }

        // --- Utilities -----------------------------------------------------------

        // infers MoveTypes so PathVisualizer can colour them
        private fun toNodeList(positions: Collection<PathPosition>?, config: PathfinderConfiguration?): kotlin.collections.List<Node> {
            if (positions == null || positions.isEmpty()) return emptyList<Node>()

            val posList: kotlin.collections.List<PathPosition> = if (positions is kotlin.collections.List<*>) positions as kotlin.collections.List<PathPosition> else ArrayList<PathPosition>(positions).toList()

            val start = posList[0]
            val target = posList[posList.size - 1]

            // Use a minimal config if none provided (fly case)
            val cfg = config ?: PathfinderConfiguration.DEFAULT

            val nodes = ArrayList<Node>(posList.size)
            var prev: PathPosition? = null
            for (pos in posList) {
                val node = Node(pos, start, target, cfg.heuristicWeights, cfg.heuristicStrategy, nodes.size)
                if (prev != null) {
                    node.moveType = inferMoveType(prev, pos)
                }
                nodes.add(node)
                prev = pos
            }
            return nodes.toList()
        }

        private fun inferMoveType(from: PathPosition, to: PathPosition): Node.MoveType {
            val dy = to.flooredY() - from.flooredY()
            val dx = Math.abs(to.flooredX() - from.flooredX())
            val dz = Math.abs(to.flooredZ() - from.flooredZ())
            if (dy > 1) return Node.MoveType.STEP_UP // 2+ block ascent - needs explicit jump; dy=1 left as WALK so collapseAscendingStacks doesn't swallow slab steps
            if (dy < 0) return Node.MoveType.FALL
            if (dx + dz >= 2) return Node.MoveType.WALK_DIAGONAL
            return Node.MoveType.WALK
        }

        private fun computePathLength(path: kotlin.collections.List<Node>): Double {
            var total = 0.0
            for (i in 0 until path.size - 1) {
                val a = path[i].position
                val b = path[i + 1].position
                total += a.distance(b)
            }
            return total
        }

        private fun recordExploredNodes(pathfinder: AStarPathfinder?) {
            if (pathfinder == null || pathfinder.getClosedSet() == null) {
                return
            }
            for (key in pathfinder.getClosedSet()) {
                PathVisualizer.addExplored(unpackX(key), unpackY(key), unpackZ(key))
            }
        }

        // closedSet is packed with RegionKey format; unpack before passing to PathVisualizer:
        private const val MASK_Y = 0xFFFL
        private const val MASK_XZ = 0x3FFFFFFL
        private const val SHIFT_Z = 12
        private const val SHIFT_X = 38

        private const val KEYNODE_MIN_SPACING = 12.0
        // degrees of direction change that forces a keynode regardless of distance
        private const val KEYNODE_ANGLE_THRESH = 25.0

        // spaces keynodes at least KEYNODE_MIN_SPACING apart, but always keeps a turn past KEYNODE_ANGLE_THRESH, plus the first and last node
        private fun subsampleKeynodes(smoothed: kotlin.collections.List<Node>): kotlin.collections.List<Node> {
            if (smoothed.size <= 2) return smoothed.toList()
            val result = ArrayList<Node>()
            appendDistinct(result, smoothed[0])
            var lastDirX = 0.0
            var lastDirZ = 0.0
            var accumDist = 0.0
            var prev = smoothed[0]
            for (i in 1 until smoothed.size - 1) {
                val cur = smoothed[i]
                val dx = cur.position.centeredX() - prev.position.centeredX()
                val dy = cur.position.centeredY() - prev.position.centeredY()
                val dz = cur.position.centeredZ() - prev.position.centeredZ()
                accumDist += Math.sqrt(dx * dx + dy * dy + dz * dz)
                // Compute horizontal direction change from last kept keynode
                val last = result[result.size - 1]
                val kDx = cur.position.centeredX() - last.position.centeredX()
                val kDz = cur.position.centeredZ() - last.position.centeredZ()
                val kLen = Math.sqrt(kDx * kDx + kDz * kDz)
                var angleDeg = 0.0
                if (kLen > 0.001 && (lastDirX * lastDirX + lastDirZ * lastDirZ) > 0.001) {
                    val dot = (kDx / kLen) * lastDirX + (kDz / kLen) * lastDirZ
                    angleDeg = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))))
                }
                val yChangedRaw = cur.position.flooredY() != last.position.flooredY()
                val yChanged = yChangedRaw && (kLen >= 0.75 || isVerticalCritical(cur.moveType) || isVerticalCritical(last.moveType))
                val farEnough = accumDist >= KEYNODE_MIN_SPACING
                val cornerChange = angleDeg >= KEYNODE_ANGLE_THRESH
                if (farEnough || cornerChange || yChanged) {
                    if (appendDistinct(result, cur)) {
                        if (kLen > 0.001) {
                            lastDirX = kDx / kLen
                            lastDirZ = kDz / kLen
                        }
                        accumDist = 0.0
                        prev = cur
                    }
                }
            }
            appendDistinct(result, smoothed[smoothed.size - 1])
            return collapseAscendingStacks(result.toList())
        }

        private const val INTERMEDIATE_SPACING = 4.0

        private fun insertIntermediates(keynodes: kotlin.collections.List<Node>, checker: WalkabilityChecker?): kotlin.collections.List<Node> {
            if (keynodes.size < 2) return keynodes
            val result = ArrayList<Node>()
            for (i in 0 until keynodes.size - 1) {
                appendDistinct(result, keynodes[i])
                val from = keynodes[i]
                val to = keynodes[i + 1]
                // Don't interpolate Y - intermediates at fractional heights land inside terrain
                // on height-changing segments (cliffs, stairs). Skip intermediates for those.
                if (from.position.flooredY() != to.position.flooredY()) continue
                val dx = to.position.centeredX() - from.position.centeredX()
                val dz = to.position.centeredZ() - from.position.centeredZ()
                val dist = Math.sqrt(dx * dx + dz * dz)
                if (dist > INTERMEDIATE_SPACING) {
                    val steps = (dist / INTERMEDIATE_SPACING).toInt()
                    val iy = from.position.flooredY()
                    for (s in 1 until steps) {
                        val t = s.toDouble() / steps
                        val ix = Math.floor(from.position.centeredX() + dx * t).toInt()
                        val iz = Math.floor(from.position.centeredZ() + dz * t).toInt()
                        // Skip intermediates that land inside solid geometry.
                        if (checker != null && (checker.getTopY(ix, iy, iz) > 0.0 || checker.getTopY(ix, iy + 1, iz) > 0.0)) {
                            continue
                        }
                        val inter = Node(PathPosition(ix.toDouble(), iy.toDouble(), iz.toDouble()))
                        inter.moveType = from.moveType
                        appendDistinct(result, inter)
                    }
                }
            }
            appendDistinct(result, keynodes[keynodes.size - 1])
            return result.toList()
        }

        private fun isVerticalCritical(moveType: Node.MoveType): Boolean {
            return moveType == Node.MoveType.STEP_UP || moveType == Node.MoveType.JUMP || moveType == Node.MoveType.PARKOUR || moveType == Node.MoveType.FALL
        }

        private fun appendDistinct(list: MutableList<Node>, node: Node?): Boolean {
            if (node == null) return false
            if (list.isEmpty()) {
                list.add(node)
                return true
            }
            val last = list[list.size - 1]
            if (sameBlock(last, node)) return false
            list.add(node)
            return true
        }

        private fun collapseAscendingStacks(nodes: kotlin.collections.List<Node>): kotlin.collections.List<Node> {
            if (nodes.size <= 2) return nodes

            val out = ArrayList<Node>(nodes.size)
            out.add(nodes[0])

            for (i in 1 until nodes.size - 1) {
                val cur = nodes[i]
                val last = out[out.size - 1]

                // Stair ascents can produce vertical stacks at same X/Z; keep only the top node.
                if (sameXZ(last, cur) && cur.position.flooredY() >= last.position.flooredY() && isAscendingMove(last.moveType, cur.moveType)) {
                    out[out.size - 1] = cur
                    continue
                }

                out.add(cur)
            }

            appendDistinct(out, nodes[nodes.size - 1])
            return out.toList()
        }

        private fun sameXZ(a: Node, b: Node): Boolean {
            return a.position.flooredX() == b.position.flooredX() && a.position.flooredZ() == b.position.flooredZ()
        }

        private fun isAscendingMove(a: Node.MoveType, b: Node.MoveType): Boolean {
            return a == Node.MoveType.STEP_UP || b == Node.MoveType.STEP_UP || a == Node.MoveType.JUMP || b == Node.MoveType.JUMP || a == Node.MoveType.PARKOUR || b == Node.MoveType.PARKOUR
        }

        private fun sameBlock(a: Node, b: Node): Boolean {
            return a.position.flooredX() == b.position.flooredX() && a.position.flooredY() == b.position.flooredY() && a.position.flooredZ() == b.position.flooredZ()
        }

        private fun unpackX(key: Long): Int {
            val raw = (key shr SHIFT_X) and MASK_XZ
            // Sign extend from 26 bits: if bit 25 is set, value is negative
            return if (raw and (1L shl 25) != 0L) (raw or MASK_XZ.inv()).toInt() else raw.toInt()
        }

        private fun unpackY(key: Long): Int {
            val raw = key and MASK_Y
            // Sign extend from 12 bits: if bit 11 is set, value is negative
            return if (raw and (1L shl 11) != 0L) (raw or MASK_Y.inv()).toInt() else raw.toInt()
        }

        private fun unpackZ(key: Long): Int {
            val raw = (key shr SHIFT_Z) and MASK_XZ
            return if (raw and (1L shl 25) != 0L) (raw or MASK_XZ.inv()).toInt() else raw.toInt()
        }
    }
}
