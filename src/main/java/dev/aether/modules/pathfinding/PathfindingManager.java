package dev.aether.modules.pathfinding;

import dev.aether.config.AetherConfig;
import dev.aether.modules.gear.GearManager;
import dev.aether.modules.pathfinding.debug.PathVisualizer;
import dev.aether.modules.pathfinding.etherwarp.EtherwarpHelper;
import dev.aether.modules.pathfinding.execution.EtherwarpExecutor;
import dev.aether.modules.pathfinding.execution.FlyExecutor;
import dev.aether.modules.pathfinding.execution.PathExecutor;
import dev.aether.modules.pathfinding.movement.PathSmoother;
import dev.aether.modules.pathfinding.movement.WalkabilityChecker;
import dev.aether.modules.pathfinding.pathfinder.AStarPathfinder;
import dev.aether.modules.pathfinding.pathfinder.EtherwarpPathfinder;
import dev.aether.modules.pathfinding.pathing.heuristic.HeuristicWeights;
import dev.aether.modules.pathfinding.pathing.heuristic.IHeuristicStrategy;
import dev.aether.modules.pathfinding.pathing.heuristic.LinearHeuristicStrategy;
import dev.aether.modules.pathfinding.pathing.NeighborStrategies;
import dev.aether.modules.pathfinding.pathing.configuration.PathfinderConfiguration;
import dev.aether.modules.pathfinding.pathing.processing.impl.FlyPathProcessor;
import dev.aether.modules.pathfinding.pathing.processing.impl.MinecraftPathProcessor;
import dev.aether.modules.pathfinding.pathing.result.PathState;
import dev.aether.modules.pathfinding.pathing.result.PathfinderResult;
import dev.aether.modules.pathfinding.provider.impl.MinecraftNavigationProvider;
import dev.aether.modules.pathfinding.wrapper.PathPosition;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Central coordinator for all pathfinding operations.
 */
public final class PathfindingManager {

    private enum NavigationMode {
        NONE,
        WALK,
        FLY,
        ETHERWARP
    }

    private static final PathExecutor      executor         = new PathExecutor();
    private static final FlyExecutor       flyExecutor      = new FlyExecutor();
    private static final EtherwarpExecutor etherwarpExecutor = new EtherwarpExecutor();

    private static final double ETHERWARP_WALK_ASSIST_BASE_COST = 8.0;
    private static final double ETHERWARP_WALK_ASSIST_DISTANCE_COST = 4.0;
    private static final double ETHERWARP_WALK_ASSIST_MAX_DISTANCE = 48.0;
    private static final double ETHERWARP_WALK_ASSIST_CHECK_SPACING = 4.0;
    private static final double ETHERWARP_WALK_ASSIST_GOAL_TOLERANCE = 0.2;
    private static final int ETHERWARP_WALK_ASSIST_MAX_CANDIDATES = 12;
    private static final int ETHERWARP_REPATH_MAX_RETRIES = 3;
    // A fly that FINISHED further than this from the goal (e.g. off a partial
    // path) is not treated as having reached it.
    private static final double FLY_GOAL_REACHED_DIST = 3.5;

    private static volatile boolean navigating = false;
    // True only when the last fly navigation actually reached its goal. Any other
    // ending (stall/FAILED, no path, abort) leaves this false so callers can recover.
    private static volatile boolean flyReachedGoal = false;
    private static volatile int goalX, goalY, goalZ;
    private static volatile NavigationMode activeMode = NavigationMode.NONE;
    private static Entity rotationTarget = null;
    private static Vec3 walkLookTarget = null;
    private static Runnable walkFinishedCallback = null;
    private static Runnable walkFailureCallback = null;
    private static Runnable etherwarpFinishedCallback = null;
    private static Runnable etherwarpFailureCallback = null;
    private static boolean etherwarpAllowWalkAssist = true;
    private static boolean walkAllowReplan = true;
    private static boolean walkRequireFullPath = false;
    private static boolean walkStrictGoalCompletion = false;
    private static double walkPreciseGoalTolerance = 0.5;
    private static double walkStickySneakDistance = -1.0;
    private static double walkGoalCenterX = 0.5;
    private static double walkGoalCenterZ = 0.5;
    private static boolean walkSneakLatched = false;
    private static final AtomicBoolean abortFlag = new AtomicBoolean(false);

    // Held so we can abort the current async run
    private static volatile AStarPathfinder currentPathfinder = null;
    private static volatile EtherwarpPathfinder currentEtherwarpPathfinder = null;
    private static volatile long etherwarpSearchToken = 0L;
    private static volatile int etherwarpRepathCount = 0;
    private static volatile boolean etherwarpRetryPending = false;
    private static volatile PathPosition etherwarpRetryTarget = null;
    private static volatile boolean etherwarpSearchInProgress = false;

    private PathfindingManager() {}

    private record EtherwarpNavigationPlan(List<PathPosition> walkPrefix,
                                           List<Node> etherwarpPath,
                                           long exploredCount,
                                           double walkDistance,
                                           boolean aborted) {
        private static EtherwarpNavigationPlan none(long exploredCount) {
            return new EtherwarpNavigationPlan(List.of(), List.of(), exploredCount, 0.0, false);
        }

        private static EtherwarpNavigationPlan aborted(long exploredCount) {
            return new EtherwarpNavigationPlan(List.of(), List.of(), exploredCount, 0.0, true);
        }

        private static EtherwarpNavigationPlan pure(List<Node> etherwarpPath, long exploredCount) {
            return new EtherwarpNavigationPlan(List.of(), etherwarpPath, exploredCount, 0.0, false);
        }

        private static EtherwarpNavigationPlan walkAssist(List<PathPosition> walkPrefix,
                                                          List<Node> etherwarpPath,
                                                          long exploredCount,
                                                          double walkDistance) {
            return new EtherwarpNavigationPlan(List.copyOf(walkPrefix), etherwarpPath,
                    exploredCount, walkDistance, false);
        }

        private boolean hasPath() {
            return etherwarpPath != null && !etherwarpPath.isEmpty();
        }

        private boolean usesWalkAssist() {
            return walkPrefix != null && walkPrefix.size() > 1;
        }

    }

    private record WalkAssistCandidate(int prefixEndIndex,
                                       double walkDistance,
                                       double score,
                                       List<Node> etherwarpPath) {
    }

    // --- Tick ----------------------------------------------------------------

    public static void update() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        PathVisualizer.captureCamera(mc);

        // Detect re-plan requests from PathExecutor
        if (activeMode == NavigationMode.WALK && executor.getState() == PathExecutor.State.REPLANNING) {
            doStartPathfind(mc, goalX, goalY, goalZ, false);
            return;
        }

        if (activeMode == NavigationMode.ETHERWARP) {
            if (etherwarpSearchInProgress) {
                return;
            }
            etherwarpExecutor.tick(mc);
            if (etherwarpRetryPending) {
                etherwarpRetryPending = false;
                PathPosition retryTarget = etherwarpRetryTarget;
                etherwarpRetryTarget = null;
                if (retryTarget != null) {
                    restartEtherwarpPathfindFromCurrent(mc, retryTarget);
                    return;
                }
            }
            if (PathVisualizer.shouldRender()) {
                PathVisualizer.updateExecution(etherwarpExecutor.getWaypointIndex(), -1);
            }
            if (etherwarpExecutor.getState() == EtherwarpExecutor.State.FINISHED
                    || etherwarpExecutor.getState() == EtherwarpExecutor.State.FAILED) {
                navigating = false;
                activeMode = NavigationMode.NONE;
                clearTransientDebugRenderingIfActive();
            }
            return;
        }

        FlyExecutor.State flyState = flyExecutor.getState();
        boolean isFlyActive = activeMode == NavigationMode.FLY
                && (flyState == FlyExecutor.State.FLYING || flyState == FlyExecutor.State.DECELERATING);

        if (isFlyActive) {
            // Always ensure the player is flying while the fly executor is active
            if (mc.player.getAbilities().mayfly && !mc.player.getAbilities().flying) {
                mc.player.getAbilities().flying = true;
                mc.player.onUpdateAbilities();
            }
            flyExecutor.tick(mc);
            FlyExecutor.State postState = flyExecutor.getState();
            if (postState == FlyExecutor.State.FINISHED || postState == FlyExecutor.State.FAILED) {
                double goalDx = mc.player.getX() - (goalX + 0.5);
                double goalDz = mc.player.getZ() - (goalZ + 0.5);
                double horizDistToGoal = Math.sqrt(goalDx * goalDx + goalDz * goalDz);
                flyReachedGoal = postState == FlyExecutor.State.FINISHED
                        && horizDistToGoal <= FLY_GOAL_REACHED_DIST;
                ClientUtils.sendDebugMessage(String.format(
                        "fly end: %s, %.1f blocks from goal (reached=%s)",
                        postState, horizDistToGoal, flyReachedGoal));
                navigating = false;
                activeMode = NavigationMode.NONE;
                clearTransientDebugRenderingIfActive();
            }
        } else if (activeMode == NavigationMode.FLY && currentPathfinder != null) {
            return;
        } else {
            NavigationMode modeBeforeTick = activeMode;
            executor.tick(mc);
            if (PathVisualizer.shouldRender() && activeMode == modeBeforeTick) {
                PathVisualizer.updateExecution(executor.getWaypointIndex(), executor.getCamTargetIdx());
            }
            if (activeMode == modeBeforeTick
                    && (executor.getState() == PathExecutor.State.FINISHED
                    || executor.getState() == PathExecutor.State.FAILED)) {
                if (executor.getState() == PathExecutor.State.FAILED && walkFailureCallback != null) {
                    walkFailureCallback.run();
                }
                navigating = false;
                activeMode = NavigationMode.NONE;
                clearTransientDebugRenderingIfActive();
            }
        }
    }

    // --- Public API ----------------------------------------------------------

    public static void startPathfind(Minecraft mc, int x, int y, int z) {
        disableTransientDebugRendering();
        resetWalkExecutionOptions();
        doStartPathfind(mc, x, y, z, false);
    }

    public static void startPathfind(Minecraft mc, int x, int y, int z, boolean fly) {
        disableTransientDebugRendering();
        resetWalkExecutionOptions();
        startPathfind(mc, x, y, z, fly, null);
    }

    public static void startPathfind(Minecraft mc, int x, int y, int z, boolean fly, Entity rotTarget) {
        disableTransientDebugRendering();
        resetWalkExecutionOptions();
        rotationTarget = rotTarget;
        doStartPathfind(mc, x, y, z, fly);
    }

    public static void startFlyPathfind(Minecraft mc, int x, int y, int z) {
        disableTransientDebugRendering();
        resetWalkExecutionOptions();
        doStartPathfind(mc, x, y, z, true);
    }

    public static void startEtherwarpPathfind(Minecraft mc, int x, int y, int z) {
        disableTransientDebugRendering();
        resetWalkExecutionOptions();
        resetEtherwarpExecutionOptions();
        doStartEtherwarpPathfind(mc, x, y, z);
    }

    public static void startDebugPathfind(Minecraft mc, int x, int y, int z) {
        enableTransientDebugRendering();
        resetWalkExecutionOptions();
        doStartPathfind(mc, x, y, z, false);
    }

    public static void startDebugFlyPathfind(Minecraft mc, int x, int y, int z) {
        enableTransientDebugRendering();
        resetWalkExecutionOptions();
        doStartPathfind(mc, x, y, z, true);
    }

    public static void startDebugEtherwarpPathfind(Minecraft mc, int x, int y, int z) {
        enableTransientDebugRendering();
        resetWalkExecutionOptions();
        resetEtherwarpExecutionOptions();
        doStartEtherwarpPathfind(mc, x, y, z);
    }

    public static void startConfiguredEtherwarp(Minecraft mc, int x, int y, int z,
                                                Runnable onFinished, Runnable onFailed) {
        disableTransientDebugRendering();
        resetWalkExecutionOptions();
        resetEtherwarpExecutionOptions();
        configureEtherwarpExecution(onFinished, onFailed);
        doStartEtherwarpPathfind(mc, x, y, z);
    }

    public static void startConfiguredPureEtherwarp(Minecraft mc, int x, int y, int z,
                                                    Runnable onFinished, Runnable onFailed) {
        disableTransientDebugRendering();
        resetWalkExecutionOptions();
        resetEtherwarpExecutionOptions();
        etherwarpAllowWalkAssist = false;
        configureEtherwarpExecution(onFinished, onFailed);
        doStartEtherwarpPathfind(mc, x, y, z);
    }

    public static void startConfiguredWalk(Minecraft mc, int x, int y, int z,
                                           Runnable onFinished, Runnable onFailed,
                                           boolean allowReplan, double preciseGoalTolerance) {
        startConfiguredWalk(mc, x, y, z, onFinished, onFailed, allowReplan, preciseGoalTolerance, false);
    }

    public static void startConfiguredWalk(Minecraft mc, int x, int y, int z,
                                           Runnable onFinished, Runnable onFailed,
                                           boolean allowReplan, double preciseGoalTolerance,
                                           boolean strictGoalCompletion) {
        startConfiguredWalk(mc, x, y, z, onFinished, onFailed, allowReplan,
                preciseGoalTolerance, strictGoalCompletion, false);
    }

    public static void startConfiguredWalk(Minecraft mc, int x, int y, int z,
                                           Runnable onFinished, Runnable onFailed,
                                           boolean allowReplan, double preciseGoalTolerance,
                                           boolean strictGoalCompletion,
                                           boolean requireFullPath) {
        disableTransientDebugRendering();
        resetWalkExecutionOptions();
        walkRequireFullPath = requireFullPath;
        configureWalkExecution(null, onFinished, onFailed, allowReplan, preciseGoalTolerance, strictGoalCompletion);
        doStartPathfind(mc, x, y, z, false);
    }

    public static void startConfiguredWalk(Minecraft mc, Vec3 target,
                                           Runnable onFinished, Runnable onFailed,
                                           boolean allowReplan, double preciseGoalTolerance,
                                           boolean strictGoalCompletion,
                                           boolean requireFullPath) {
        int x = Mth.floor(target.x);
        int y = Mth.floor(target.y);
        int z = Mth.floor(target.z);
        disableTransientDebugRendering();
        resetWalkExecutionOptions();
        walkRequireFullPath = requireFullPath;
        walkGoalCenterX = target.x - x;
        walkGoalCenterZ = target.z - z;
        configureWalkExecution(null, onFinished, onFailed, allowReplan, preciseGoalTolerance, strictGoalCompletion);
        doStartPathfind(mc, x, y, z, false);
    }

    /**
     * Runs A* without any movement - results are shown in PathVisualizer only.
     */
    public static void startPathTest(Minecraft mc, int x, int y, int z) {
        if (mc.player == null || mc.level == null) return;

        if (!PathVisualizer.isEnabled()) PathVisualizer.toggle();
        PathVisualizer.clear();

        WalkabilityChecker checker = new WalkabilityChecker(mc.level);
        final int sx = (int) Math.floor(mc.player.getX());
        final int sz = (int) Math.floor(mc.player.getZ());
        final int sy = resolveStartY(checker, mc.player.getX(), mc.player.getY(), mc.player.getZ());

        final int finalY;
        finalY = checker.isSolid(x, y, z) ? y + 1 : y;

        if (mc.player != null) {
            ClientUtils.sendMessage("\u00A7ePath test: running A* to "
                            + x + ", " + finalY + ", " + z + "...", false);
        }

        Thread t = new Thread(() -> {
            long startMs = System.currentTimeMillis();

            PathfinderConfiguration config = PathfinderConfiguration.builder()
                    .provider(new MinecraftNavigationProvider(checker))
                    .processors(List.of(new MinecraftPathProcessor(checker,
                            AetherConfig.PATHFINDER_MAX_JUMP_HEIGHT.get())))
                    .neighborStrategy(NeighborStrategies.horizontalDiagonalAndVertical(
                            AetherConfig.PATHFINDER_MAX_JUMP_HEIGHT.get()))
                    .maxIterations(300000)
                    .maxLength(300000)
                    .async(true)
                    .fallback(true)
                    .build();

            AStarPathfinder pathfinder = new AStarPathfinder(config);
            PathPosition start  = new PathPosition(sx, sy, sz);
            PathPosition target = new PathPosition(x,  finalY, z);

            PathfinderResult result;
            try {
                result = pathfinder.findPath(start, target).toCompletableFuture().join();
            } catch (Exception e) {
                result = null;
            }

            // Explored node visualisation
            if (pathfinder.getClosedSet() != null) {
                for (long key : pathfinder.getClosedSet()) {
                    PathVisualizer.addExplored(
                            unpackX(key), unpackY(key), unpackZ(key));
                }
            }

            final PathfinderResult finalResult = result;
            final WalkabilityChecker finalChecker = checker;
            mc.execute(() -> {
                if (finalResult == null) {
                    if (mc.player != null) {
                        ClientUtils.sendMessage("\u00A7cPath test error!", false);
                    }
                    return;
                }

                String typeStr;
                int    pathLen    = 0;
                double pathBlocks = 0;

                Collection<PathPosition> positions = finalResult.getPath().collect();

                if (finalResult.successful() || finalResult.hasFallenBack()) {
                    typeStr = finalResult.successful() ? "\u00A7aFull" : "\u00A7ePartial";
                    List<Node> nodes    = toNodeList(positions, config);
                    List<Node> smoothed = PathSmoother.smooth(nodes, finalChecker);
                    List<Node> keynodes = collapseAscendingStacks(smoothed);
                    for (Node n : keynodes) n.isKeynode = true;
                    List<Node> navPath  = insertIntermediates(keynodes, finalChecker);
                    pathLen    = navPath.size();
                    pathBlocks = computePathLength(nodes);
                    PathVisualizer.setPath(navPath, 0);
                    PathVisualizer.setCameraPath(Collections.emptyList());
                } else {
                    typeStr = "\u00A7cNone";
                }

                long elapsedMs = System.currentTimeMillis() - startMs;

                if (mc.player != null) {
                    ClientUtils.sendMessage("\u00A7ePath test result: "
                                    + typeStr
                                    + "\u00A76 | explored: " + pathfinder.getExploredCount()
                                    + " | waypoints: " + pathLen
                                    + String.format(" | dist: %.1f blk", pathBlocks)
                                    + " | time: " + elapsedMs + "ms",
                            false);
                    ClientUtils.sendDebugMessage("Path profile: " + pathfinder.getProfilingReport());
                }
            });
        }, "Aether-PathTest");
        t.setDaemon(true);
        t.start();
    }

    public static void startGreenhouseWalk(Minecraft mc, Vec3 target, Runnable onFinished, boolean isFirst) {
        if (mc.player == null) return;
        int tx = (int) Math.floor(target.x);
        int ty = (int) Math.floor(target.y);
        int tz = (int) Math.floor(target.z);

        configureWalkExecution(target.add(0, -10.0, 0), onFinished, null, !isFirst, 0.25, true);
        walkGoalCenterX = target.x - tx;
        walkGoalCenterZ = target.z - tz;

        if (isFirst) {
            doStartPathfind(mc, tx, ty, tz, false);
            return;
        }

        if (navigating) {
            abortCurrentNavigation(mc);
        }

        abortFlag.set(false);
        navigating = true;
        activeMode = NavigationMode.WALK;
        goalX = tx;
        goalY = ty;
        goalZ = tz;

        PathPosition start = new PathPosition(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        PathPosition targetPos = new PathPosition(target.x, target.y, target.z);
        List<Node> nodes = buildLinearWalkPath(start, targetPos);

        PathVisualizer.setPath(nodes, 0);
        executor.start(nodes, tx, ty, tz, true, null, onFinished);
        executor.setAllowRotation(false);
        executor.setAllowReplan(false);
        executor.setAllowJumps(false);
        executor.setExactGoalCentering(true);
        executor.setStrictGoalCompletion(true);
        executor.setStickySneakDistance(walkStickySneakDistance);
        executor.setSneakLatched(walkSneakLatched);
        executor.setGoalCenterOffsets(walkGoalCenterX, walkGoalCenterZ);
        executor.setPreciseGoalTolerance(0.25);

        if (mc.player != null) {
            ClientUtils.sendMessage("\u00A77Greenhouse path: direct walk to target.", false);
        }
    }

    public static void stop() {
        stop(true);
    }

    public static void stop(boolean announce) {
        boolean wasNavigating = navigating;
        navigating = false;
        Minecraft mc = Minecraft.getInstance();
        abortCurrentNavigation(mc);
        disableTransientDebugRendering();
        PathVisualizer.clear();
        if (announce && wasNavigating && mc.player != null) {
            ClientUtils.sendMessage("\u00A7eNavigation stopped.", false);
        }
    }

    public static boolean isNavigating() { return navigating; }

    /** True when the most recent fly navigation reached its goal (vs stalled/failed/aborted). */
    public static boolean lastFlyReachedGoal() { return flyReachedGoal; }

    public static boolean isWalkSneakLatched() {
        walkSneakLatched = executor.isSneakLatched();
        return walkSneakLatched;
    }

    public static void setWalkSneakLatched(boolean walkSneakLatched) {
        PathfindingManager.walkSneakLatched = walkSneakLatched;
        executor.setSneakLatched(walkSneakLatched);
    }

    private static void resetWalkExecutionOptions() {
        walkLookTarget = null;
        walkFinishedCallback = null;
        walkFailureCallback = null;
        walkAllowReplan = true;
        walkRequireFullPath = false;
        walkStrictGoalCompletion = false;
        walkPreciseGoalTolerance = 0.5;
        walkStickySneakDistance = -1.0;
        walkGoalCenterX = 0.5;
        walkGoalCenterZ = 0.5;
        walkSneakLatched = false;
    }

    private static void resetEtherwarpExecutionOptions() {
        etherwarpFinishedCallback = null;
        etherwarpFailureCallback = null;
        etherwarpAllowWalkAssist = true;
    }

    private static void enableTransientDebugRendering() {
        PathVisualizer.beginTransientSession();
    }

    private static void disableTransientDebugRendering() {
        PathVisualizer.endTransientSession();
    }

    private static void clearTransientDebugRenderingIfActive() {
        if (!PathVisualizer.isTransientSessionActive()) {
            return;
        }
        disableTransientDebugRendering();
        PathVisualizer.clear();
    }

    private static void configureWalkExecution(Vec3 lookTarget, Runnable onFinished, Runnable onFailed,
                                               boolean allowReplan, double preciseGoalTolerance,
                                               boolean strictGoalCompletion) {
        walkLookTarget = lookTarget;
        walkFinishedCallback = onFinished;
        walkFailureCallback = onFailed;
        walkAllowReplan = allowReplan;
        walkStrictGoalCompletion = strictGoalCompletion;
        walkPreciseGoalTolerance = preciseGoalTolerance;
        walkStickySneakDistance = 5.0;
    }

    private static void configureEtherwarpExecution(Runnable onFinished, Runnable onFailed) {
        etherwarpFinishedCallback = onFinished;
        etherwarpFailureCallback = onFailed;
    }

    private static void abortCurrentNavigation(Minecraft mc) {
        abortFlag.set(true);
        etherwarpRetryPending = false;
        etherwarpRetryTarget = null;
        etherwarpRepathCount = 0;
        etherwarpSearchInProgress = false;
        AStarPathfinder walkPathfinder = currentPathfinder;
        if (walkPathfinder != null) {
            walkPathfinder.abort();
        }
        EtherwarpPathfinder etherwarpPathfinder = currentEtherwarpPathfinder;
        if (etherwarpPathfinder != null) {
            etherwarpPathfinder.abort();
        }
        executor.stop(mc);
        flyExecutor.stop(mc);
        etherwarpExecutor.stop(mc);
        currentPathfinder = null;
        currentEtherwarpPathfinder = null;
        activeMode = NavigationMode.NONE;
    }

    private static void clearEtherwarpSneakState(Minecraft mc) {
        if (mc == null) {
            return;
        }
        if (mc.options != null) {
            ClientUtils.setKeyMappingState(mc.options.keyShift, false);
            ClientUtils.setKeyMappingState(mc.options.keyUse, false);
        }
        if (mc.player != null) {
            mc.player.setShiftKeyDown(false);
        }
    }

    // --- Internal ------------------------------------------------------------

    private static void doStartPathfind(Minecraft mc, int x, int y, int z, boolean fly) {
        if (navigating) {
            abortCurrentNavigation(mc);
        }
        PathVisualizer.clear();

        abortFlag.set(false);
        navigating = true;
        activeMode = fly ? NavigationMode.FLY : NavigationMode.WALK;
        currentEtherwarpPathfinder = null;
        // Reset before any async result can land; only a true FINISHED sets this back on.
        if (fly) {
            flyReachedGoal = false;
        }

        // Create checker once; reused for solid-check, pathfinding, and smoothing.
        final WalkabilityChecker sharedChecker = mc.level != null ? new WalkabilityChecker(mc.level) : null;
        final int finalY;
        FlyPathProcessor flyProcessor = fly && sharedChecker != null ? new FlyPathProcessor(sharedChecker) : null;
        if (flyProcessor != null && !flyProcessor.hasFlightClearance(x, y, z)
                && flyProcessor.hasFlightClearance(x, y + 1, z)) {
            finalY = y + 1;
        } else if (!fly && sharedChecker != null && sharedChecker.isSolid(x, y, z)) {
            finalY = y + 1;
        } else {
            finalY = y;
        }

        goalX = x;
        goalY = finalY;
        goalZ = z;

        if (mc.player != null) {
            ClientUtils.sendMessage("\u00A7eFinding path to "
                            + x + ", " + finalY + ", " + z + "...", false);
        }

        final int sx = (int) Math.floor(mc.player.getX());
        final int sz = (int) Math.floor(mc.player.getZ());
        final int sy = resolveStartY(sharedChecker, mc.player.getX(), mc.player.getY(), mc.player.getZ());

        PathPosition start  = new PathPosition(sx, sy, sz);
        PathPosition target = new PathPosition(x, finalY, z);

        if (fly) {
            if (sharedChecker == null || flyProcessor == null) {
                navigating = false;
                activeMode = NavigationMode.NONE;
                clearTransientDebugRenderingIfActive();
                if (mc.player != null) {
                    ClientUtils.sendMessage("\u00A7cCannot fly pathfind without a loaded world.", false);
                }
                return;
            }

            PathfinderConfiguration config = createFlyPathfinderConfiguration(sharedChecker, flyProcessor, true);

            AStarPathfinder pathfinder = new AStarPathfinder(config);
            currentPathfinder = pathfinder;
            long startMs = System.currentTimeMillis();

            pathfinder.findPath(start, target)
                    .thenAccept(result -> mc.execute(() -> {
                        if (abortFlag.get()) return;
                        handleFlyResult(mc, result, config, x, finalY, z, startMs, pathfinder);
                    }));
        } else {
            // Walk pathfinding - async via CompletableFuture work-stealing pool
            // sharedChecker is reused for pathfinding and smoothing (no redundant allocation)
            PathfinderConfiguration config = createWalkPathfinderConfiguration(sharedChecker, true);

            AStarPathfinder pathfinder = new AStarPathfinder(config);
            currentPathfinder = pathfinder;
            long startMs = System.currentTimeMillis();

            pathfinder.findPath(start, target)
                    .thenAccept(result -> mc.execute(() -> {
                        if (abortFlag.get()) return;
                        handleWalkResult(mc, result, config, sharedChecker, x, finalY, z,
                                startMs, pathfinder);
                    }));
        }
    }

    private static PathfinderConfiguration createWalkPathfinderConfiguration(WalkabilityChecker checker, boolean async) {
        return PathfinderConfiguration.builder()
                .provider(new MinecraftNavigationProvider(checker))
                .processors(List.of(new MinecraftPathProcessor(checker,
                        AetherConfig.PATHFINDER_MAX_JUMP_HEIGHT.get())))
                .neighborStrategy(NeighborStrategies.horizontalDiagonalAndVertical(
                        AetherConfig.PATHFINDER_MAX_JUMP_HEIGHT.get()))
                .maxIterations(300000)
                .maxLength(25000)
                .async(async)
                .fallback(true)
                .build();
    }

    private static PathfinderConfiguration createFlyPathfinderConfiguration(WalkabilityChecker checker,
                                                                            FlyPathProcessor flyProcessor,
                                                                            boolean async) {
        return PathfinderConfiguration.builder()
                .provider(new MinecraftNavigationProvider(checker))
                .processors(List.of(flyProcessor))
                .neighborStrategy(NeighborStrategies.HORIZONTAL_DIAGONAL_AND_VERTICAL)
                .maxIterations(300000)
                .maxLength(50000)
                .async(async)
                .fallback(true)
                .build();
    }

    private static void doStartEtherwarpPathfind(Minecraft mc, int x, int y, int z) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (navigating) {
            abortCurrentNavigation(mc);
        }
        PathVisualizer.clear();

        WalkabilityChecker checker = new WalkabilityChecker(mc.level);
        PathPosition target = EtherwarpHelper.resolveTargetFeet(checker, x, y, z);
        if (target == null) {
            ClientUtils.sendMessage("\u00A7cEtherwarp target must have enough crouched headroom above it.", false);
            if (etherwarpFailureCallback != null) {
                etherwarpFailureCallback.run();
            }
            return;
        }
        if (GearManager.findEtherwarpAspectOfTheVoidHotbarSlot(mc) < 0) {
            ClientUtils.sendMessage("\u00A7cNo hotbar AOTV with Ether Transmission found.", false);
            if (etherwarpFailureCallback != null) {
                etherwarpFailureCallback.run();
            }
            return;
        }

        etherwarpRepathCount = 0;
        etherwarpRetryPending = false;
        etherwarpRetryTarget = null;
        startEtherwarpPathSearch(mc, target, false);
    }

    private static void startEtherwarpPathSearch(Minecraft mc, PathPosition target, boolean retry) {
        if (mc.player == null || mc.level == null || target == null) {
            return;
        }

        WalkabilityChecker checker = new WalkabilityChecker(mc.level);
        if (!EtherwarpHelper.isValidLandingFeet(checker, target)) {
            clearEtherwarpSneakState(mc);
            navigating = false;
            activeMode = NavigationMode.NONE;
            clearTransientDebugRenderingIfActive();
            if (mc.player != null) {
                ClientUtils.sendMessage("\u00A7cEtherwarp target no longer has enough crouched headroom.", false);
            }
            if (etherwarpFailureCallback != null) {
                etherwarpFailureCallback.run();
            }
            return;
        }

        abortFlag.set(false);
        navigating = true;
        activeMode = NavigationMode.ETHERWARP;
        etherwarpSearchInProgress = true;
        currentPathfinder = null;

        goalX = target.flooredX();
        goalY = target.flooredY();
        goalZ = target.flooredZ();

        int startY = resolveStartY(checker, mc.player.getX(), mc.player.getY(), mc.player.getZ());
        PathPosition start = new PathPosition(
                Math.floor(mc.player.getX()),
                startY,
                Math.floor(mc.player.getZ()));
        long searchToken = ++etherwarpSearchToken;

        if (mc.player != null) {
            String message = retry
                    ? String.format("\u00A7eRepathing etherwarp route from current position (%d/%d)...",
                    etherwarpRepathCount, ETHERWARP_REPATH_MAX_RETRIES)
                    : "\u00A7eFinding etherwarp path to " + goalX + ", " + goalY + ", " + goalZ + "...";
            ClientUtils.sendMessage(message, false);
        }

        long startMs = System.currentTimeMillis();
        boolean allowWalkAssist = etherwarpAllowWalkAssist;
        CompletableFuture.supplyAsync(() -> computeEtherwarpNavigationPlan(mc, checker, start, target, allowWalkAssist))
                .thenAccept(plan -> mc.execute(() -> {
                    if (abortFlag.get() || searchToken != etherwarpSearchToken) {
                        return;
                    }
                    handleEtherwarpResult(mc, checker, plan, start, target, startMs, searchToken);
                }));
    }

    private static void handleEtherwarpResult(Minecraft mc,
                                              WalkabilityChecker checker,
                                              EtherwarpNavigationPlan plan,
                                              PathPosition start,
                                              PathPosition target,
                                              long startMs,
                                              long searchToken) {
        currentPathfinder = null;
        currentEtherwarpPathfinder = null;
        etherwarpSearchInProgress = false;
        if (plan == null || plan.aborted()) {
            clearEtherwarpSneakState(mc);
            navigating = false;
            activeMode = NavigationMode.NONE;
            clearTransientDebugRenderingIfActive();
            return;
        }

        if (!plan.hasPath()) {
            clearEtherwarpSneakState(mc);
            navigating = false;
            activeMode = NavigationMode.NONE;
            clearTransientDebugRenderingIfActive();
            if (mc.player != null) {
                ClientUtils.sendMessage("\u00A7cNo etherwarp path found!", false);
            }
            if (etherwarpFailureCallback != null) {
                etherwarpFailureCallback.run();
            }
            return;
        }

        List<Node> path = new ArrayList<>(plan.etherwarpPath());
        if (path.isEmpty() || !sameBlock(path.get(0), createSyntheticNode(start, Node.MoveType.ETHERWARP))) {
            PathPosition pathStart = plan.usesWalkAssist() ? plan.walkPrefix().getLast() : start;
            path.add(0, createSyntheticNode(pathStart, Node.MoveType.ETHERWARP));
        }
        for (Node node : path) {
            node.moveType = Node.MoveType.ETHERWARP;
            node.isKeynode = true;
        }

        long elapsedMs = System.currentTimeMillis() - startMs;
        double pathBlocks = plan.walkDistance() + computePathLength(path);
        if (mc.player != null) {
            String assistPrefix = plan.usesWalkAssist()
                    ? String.format("walk %.1f blk -> ", plan.walkDistance())
                    : "";
            ClientUtils.sendMessage("\u00A7aEtherwarp path found ("
                            + assistPrefix
                            + Math.max(0, path.size() - 1) + " warps"
                            + " | explored: " + plan.exploredCount()
                            + String.format(" | dist: %.1f blk", pathBlocks)
                            + " | time: " + elapsedMs + "ms)",
                    false);
        }

        if (!plan.usesWalkAssist() && (path.size() <= 1 || start.equals(target))) {
            clearEtherwarpSneakState(mc);
            navigating = false;
            activeMode = NavigationMode.NONE;
            clearTransientDebugRenderingIfActive();
            if (etherwarpFinishedCallback != null) {
                etherwarpFinishedCallback.run();
            }
            return;
        }

        if (plan.usesWalkAssist()) {
            startWalkAssistExecution(mc, checker, plan, target, searchToken);
            return;
        }

        startEtherwarpExecution(mc, path, target);
    }

    private static EtherwarpNavigationPlan computeEtherwarpNavigationPlan(Minecraft mc,
                                                                          WalkabilityChecker checker,
                                                                          PathPosition start,
                                                                          PathPosition target,
                                                                          boolean allowWalkAssist) {
        long exploredCount = 0L;

        EtherwarpPathfinder purePathfinder = new EtherwarpPathfinder(mc, checker);
        currentPathfinder = null;
        currentEtherwarpPathfinder = purePathfinder;
        EtherwarpPathfinder.Result pureResult = purePathfinder.findPathSync(start, target);
        exploredCount += purePathfinder.getExploredCount();
        if (abortFlag.get()) {
            return EtherwarpNavigationPlan.aborted(exploredCount);
        }
        if (pureResult != null && pureResult.path() != null && !pureResult.path().isEmpty()) {
            return EtherwarpNavigationPlan.pure(List.copyOf(pureResult.path()), exploredCount);
        }
        if (!allowWalkAssist) {
            return EtherwarpNavigationPlan.none(exploredCount);
        }

        PathfinderConfiguration walkConfig = createWalkPathfinderConfiguration(checker, false);
        AStarPathfinder walkPathfinder = new AStarPathfinder(walkConfig);
        currentPathfinder = walkPathfinder;
        currentEtherwarpPathfinder = null;

        PathfinderResult walkResult;
        try {
            walkResult = walkPathfinder.findPath(start, target).toCompletableFuture().join();
        } catch (Exception ignored) {
            walkResult = null;
        }

        exploredCount += walkPathfinder.getExploredCount();
        recordExploredNodes(walkPathfinder);
        if (abortFlag.get()) {
            return EtherwarpNavigationPlan.aborted(exploredCount);
        }

        if (walkResult == null || walkResult.getPathState() == PathState.ABORTED) {
            return walkResult != null && walkResult.getPathState() == PathState.ABORTED
                    ? EtherwarpNavigationPlan.aborted(exploredCount)
                    : EtherwarpNavigationPlan.none(exploredCount);
        }

        boolean hasWalkPath = walkResult.successful() || walkResult.hasFallenBack();
        Collection<PathPosition> walkPositions = walkResult.getPath().collect();
        if (!hasWalkPath || walkPositions.isEmpty()) {
            return EtherwarpNavigationPlan.none(exploredCount);
        }

        List<PathPosition> walkPath = new ArrayList<>(walkPositions);
        double walkDistance = 0.0;
        double lastCheckedDistance = 0.0;
        int checkedCandidates = 0;
        WalkAssistCandidate bestCandidate = null;

        for (int i = 1; i < walkPath.size(); i++) {
            PathPosition previous = walkPath.get(i - 1);
            PathPosition candidatePos = walkPath.get(i);
            walkDistance += previous.distance(candidatePos);
            if (walkDistance > ETHERWARP_WALK_ASSIST_MAX_DISTANCE) {
                break;
            }
            if (checkedCandidates >= ETHERWARP_WALK_ASSIST_MAX_CANDIDATES) {
                break;
            }
            if (!shouldCheckWalkAssistCandidate(walkPath, i, walkDistance, lastCheckedDistance)) {
                continue;
            }

            EtherwarpPathfinder candidatePathfinder = new EtherwarpPathfinder(mc, checker);
            currentEtherwarpPathfinder = candidatePathfinder;
            EtherwarpPathfinder.Result candidateResult = candidatePathfinder.findPathSync(candidatePos, target);
            long candidateExplored = candidatePathfinder.getExploredCount();
            exploredCount += candidateExplored;
            checkedCandidates++;
            lastCheckedDistance = walkDistance;

            if (abortFlag.get()) {
                return EtherwarpNavigationPlan.aborted(exploredCount);
            }
            if (candidateResult == null || candidateResult.path() == null || candidateResult.path().isEmpty()) {
                continue;
            }

            double score = ETHERWARP_WALK_ASSIST_BASE_COST
                    + walkDistance * ETHERWARP_WALK_ASSIST_DISTANCE_COST
                    + Math.max(0, candidateResult.path().size() - 1);
            if (bestCandidate == null || score < bestCandidate.score()) {
                bestCandidate = new WalkAssistCandidate(i, walkDistance, score, List.copyOf(candidateResult.path()));
            }
        }

        currentEtherwarpPathfinder = null;
        currentPathfinder = null;
        if (bestCandidate == null) {
            return EtherwarpNavigationPlan.none(exploredCount);
        }

        List<PathPosition> walkPrefix = new ArrayList<>(walkPath.subList(0, bestCandidate.prefixEndIndex() + 1));
        return EtherwarpNavigationPlan.walkAssist(walkPrefix, bestCandidate.etherwarpPath(),
                exploredCount, bestCandidate.walkDistance());
    }

    private static boolean shouldCheckWalkAssistCandidate(List<PathPosition> walkPath,
                                                          int candidateIndex,
                                                          double walkDistance,
                                                          double lastCheckedDistance) {
        if (candidateIndex >= walkPath.size() - 1) {
            return true;
        }
        if (walkDistance <= 8.0) {
            return true;
        }
        return walkDistance - lastCheckedDistance >= ETHERWARP_WALK_ASSIST_CHECK_SPACING;
    }

    private static void startWalkAssistExecution(Minecraft mc,
                                                 WalkabilityChecker checker,
                                                 EtherwarpNavigationPlan plan,
                                                 PathPosition finalTarget,
                                                 long searchToken) {
        List<Node> walkNodes = toNodeList(plan.walkPrefix(), PathfinderConfiguration.DEFAULT);
        List<Node> smoothed = PathSmoother.smooth(walkNodes, checker);
        List<Node> keynodes = collapseAscendingStacks(smoothed);
        for (Node node : keynodes) {
            node.isKeynode = true;
        }
        List<Node> navPath = insertIntermediates(keynodes, checker);
        PathPosition launch = plan.walkPrefix().getLast();

        goalX = launch.flooredX();
        goalY = launch.flooredY();
        goalZ = launch.flooredZ();
        activeMode = NavigationMode.WALK;

        PathVisualizer.setPath(navPath, 0);
        PathVisualizer.setCameraPath(Collections.emptyList());

        if (mc.player != null) {
            ClientUtils.sendMessage(String.format("\u00A7eWalking %.1f blocks to an etherwarp launch point...", plan.walkDistance()),
                    false);
        }

        executor.start(navPath, launch.flooredX(), launch.flooredY(), launch.flooredZ(), true, null,
                () -> mc.execute(() -> {
                    if (abortFlag.get() || searchToken != etherwarpSearchToken) {
                        return;
                    }
                    List<Node> etherwarpPath = new ArrayList<>(plan.etherwarpPath());
                    if (etherwarpPath.isEmpty() || etherwarpPath.size() <= 1) {
                        clearEtherwarpSneakState(mc);
                        navigating = false;
                        activeMode = NavigationMode.NONE;
                        clearTransientDebugRenderingIfActive();
                        return;
                    }
                    startEtherwarpExecution(mc, etherwarpPath, finalTarget);
                }));
        executor.setAllowRotation(true);
        executor.setAllowReplan(true);
        executor.setPreciseGoalTolerance(ETHERWARP_WALK_ASSIST_GOAL_TOLERANCE);
        executor.setExactGoalCentering(true);
        executor.setStickySneakDistance(-1.0);
        executor.setSneakLatched(false);
        executor.setGoalCenterOffsets(0.5, 0.5);
        PathVisualizer.setCameraPath(executor.getCameraPath());
        PathVisualizer.updateExecution(executor.getWaypointIndex(), executor.getCamTargetIdx());
    }

    private static void startEtherwarpExecution(Minecraft mc, List<Node> path, PathPosition target) {
        goalX = target.flooredX();
        goalY = target.flooredY();
        goalZ = target.flooredZ();
        activeMode = NavigationMode.ETHERWARP;

        PathVisualizer.setPath(path, Math.min(1, Math.max(0, path.size() - 1)));
        PathVisualizer.setCameraPath(Collections.emptyList());

        etherwarpExecutor.start(path, etherwarpFinishedCallback,
                reason -> handleEtherwarpExecutionFailure(mc, target, reason));
        PathVisualizer.updateExecution(etherwarpExecutor.getWaypointIndex(), -1);
    }

    private static boolean handleEtherwarpExecutionFailure(Minecraft mc,
                                                           PathPosition target,
                                                           EtherwarpExecutor.FailureReason reason) {
        if (reason == EtherwarpExecutor.FailureReason.LOST_LINE_OF_SIGHT
                || reason == EtherwarpExecutor.FailureReason.WARP_TIMEOUT) {
            String failureLabel = reason == EtherwarpExecutor.FailureReason.LOST_LINE_OF_SIGHT
                    ? "lost line of sight"
                    : "timed out";
            if (etherwarpRepathCount >= ETHERWARP_REPATH_MAX_RETRIES) {
                if (mc != null && mc.player != null) {
                    ClientUtils.sendMessage("\u00A7cEtherwarp " + failureLabel + " after "
                                    + ETHERWARP_REPATH_MAX_RETRIES + " replans. Cancelling.",
                            false);
                }
                return true;
            }

            etherwarpRepathCount++;
            etherwarpRetryTarget = target;
            etherwarpRetryPending = true;
            return true;
        }

        navigating = false;
        activeMode = NavigationMode.NONE;
        clearTransientDebugRenderingIfActive();
        if (etherwarpFailureCallback != null) {
            etherwarpFailureCallback.run();
            return true;
        }
        return false;
    }

    private static void restartEtherwarpPathfindFromCurrent(Minecraft mc, PathPosition target) {
        if (mc == null || target == null) {
            return;
        }
        etherwarpSearchInProgress = false;
        etherwarpExecutor.stop(mc);
        currentPathfinder = null;
        currentEtherwarpPathfinder = null;
        startEtherwarpPathSearch(mc, target, true);
    }

    private static void handleWalkResult(Minecraft mc, PathfinderResult result,
                                          PathfinderConfiguration config,
                                          WalkabilityChecker checker,
                                          int x, int y, int z,
                                          long startMs, AStarPathfinder pathfinder) {
        currentPathfinder = null;
        long exploredCount = pathfinder.getExploredCount();
        recordExploredNodes(pathfinder);
        Collection<PathPosition> positions = result.getPath().collect();
        boolean hasPath = result.successful() || result.hasFallenBack();

        if (!hasPath || positions.isEmpty()) {
            navigating = false;
            activeMode = NavigationMode.NONE;
            clearTransientDebugRenderingIfActive();
            if (mc.player != null) {
                ClientUtils.sendMessage("\u00A7cNo path found!", false);
            }
            if (walkFailureCallback != null) {
                walkFailureCallback.run();
            }
            return;
        }

        if (result.getPathState() == PathState.ABORTED) {
            navigating = false;
            activeMode = NavigationMode.NONE;
            clearTransientDebugRenderingIfActive();
            return;
        }

        if (walkRequireFullPath && !result.successful()) {
            navigating = false;
            activeMode = NavigationMode.NONE;
            clearTransientDebugRenderingIfActive();
            if (mc.player != null) {
                ClientUtils.sendMessage("\u00A7eWalk path result was partial. Falling back to the next recovery path...",
                        false);
            }
            if (walkFailureCallback != null) {
                walkFailureCallback.run();
            }
            return;
        }

        List<Node> nodes = toNodeList(positions, config);
        List<Node> smoothed = PathSmoother.smooth(nodes, checker);
        // Use the smooth path directly - subsampleKeynodes would drop detour nodes whose
        // direction change is < 25 deg, creating chords that cut through obstacles.
        List<Node> keynodes = collapseAscendingStacks(smoothed);
        for (Node n : keynodes) n.isKeynode = true;
        List<Node> navPath = insertIntermediates(keynodes, checker);
        PathVisualizer.setPath(navPath, 0);
        PathVisualizer.setCameraPath(Collections.emptyList());

        String resultTypeStr = result.successful() ? "\u00A7aFull" : "\u00A7ePartial";
        long elapsedMs = System.currentTimeMillis() - startMs;
        int pathLen = nodes.size();
        double pathBlocks = computePathLength(nodes);

        String walkingColor = result.successful() ? "\u00A7a" : "\u00A7e";
        if (mc.player != null) {
            ClientUtils.sendMessage("§ePath result: " + resultTypeStr
                            + "§e | explored: " + exploredCount
                            + " | waypoints: " + pathLen
                            + String.format(" | dist: %.1f blk", pathBlocks)
                            + " | time: " + elapsedMs + "ms",
                    false);
            ClientUtils.sendMessage(walkingColor + "Path found (" + nodes.size() + " waypoints). Walking...",
                    false);
        }

        executor.start(navPath, x, y, z, walkPreciseGoalTolerance != 0.5, rotationTarget, walkFinishedCallback);
        executor.setAllowRotation(true);
        executor.setAllowReplan(walkAllowReplan);
        executor.setStrictGoalCompletion(walkStrictGoalCompletion);
        executor.setPreciseGoalTolerance(walkPreciseGoalTolerance);
        executor.setExactGoalCentering(walkPreciseGoalTolerance != 0.5);
        executor.setStickySneakDistance(walkStickySneakDistance);
        executor.setSneakLatched(walkSneakLatched);
        executor.setGoalCenterOffsets(walkGoalCenterX, walkGoalCenterZ);
        if (walkLookTarget != null) {
            executor.setLookTarget(walkLookTarget);
        }
        PathVisualizer.setCameraPath(executor.getCameraPath());
        PathVisualizer.updateExecution(executor.getWaypointIndex(), executor.getCamTargetIdx());
    }

    private static void handleFlyResult(Minecraft mc, PathfinderResult result,
                                        PathfinderConfiguration config,
                                        int x, int y, int z,
                                        long startMs, AStarPathfinder pathfinder) {
        currentPathfinder = null;
        long exploredCount = pathfinder.getExploredCount();
        recordExploredNodes(pathfinder);
        Collection<PathPosition> positions = result.getPath().collect();
        boolean hasPath = result.successful() || result.hasFallenBack();

        if (result.getPathState() == PathState.ABORTED) {
            navigating = false;
            activeMode = NavigationMode.NONE;
            clearTransientDebugRenderingIfActive();
            return;
        }

        if (!hasPath || positions.isEmpty()) {
            navigating = false;
            activeMode = NavigationMode.NONE;
            clearTransientDebugRenderingIfActive();
            if (mc.player != null) {
                ClientUtils.sendMessage("\u00A7cNo fly path found!", false);
            }
            return;
        }

        List<Node> nodes = toNodeList(positions, config);
        for (Node node : nodes) {
            node.moveType = Node.MoveType.FLY;
        }
        List<Node> smoothed = smoothFlyPath(mc, nodes);
        for (Node node : smoothed) {
            node.moveType = Node.MoveType.FLY;
            node.isKeynode = true;
        }

        if (smoothed.isEmpty()) {
            navigating = false;
            activeMode = NavigationMode.NONE;
            clearTransientDebugRenderingIfActive();
            if (mc.player != null) {
                ClientUtils.sendMessage("\u00A7cFly path build failed!", false);
            }
            return;
        }

        PathVisualizer.setPath(smoothed, 0);
        PathVisualizer.setCameraPath(Collections.emptyList());

        String resultTypeStr = result.successful() ? "\u00A7aFull" : "\u00A7ePartial";
        long elapsedMs = System.currentTimeMillis() - startMs;
        double pathBlocks = computePathLength(nodes);

        if (mc.player != null) {
            ClientUtils.sendMessage("\u00A7eFly path result: " + resultTypeStr
                            + "\u00A7e | explored: " + exploredCount
                            + " | waypoints: " + smoothed.size()
                            + String.format(" | dist: %.1f blk", pathBlocks)
                            + " | time: " + elapsedMs + "ms",
                    false);
            ClientUtils.sendMessage("\u00A7aFly path found (" + smoothed.size()
                            + " waypoints). Flying...",
                    false);
        }

        Node lastWp = smoothed.get(smoothed.size() - 1);
        ClientUtils.sendDebugMessage("fly path final wp: (" + lastWp.position.flooredX()
                + ", " + lastWp.position.flooredY() + ", " + lastWp.position.flooredZ()
                + "), goal (" + x + ", " + y + ", " + z + ")");
        flyExecutor.start(smoothed, x, y, z);
    }

    private static List<Node> buildLinearWalkPath(PathPosition start, PathPosition target) {
        List<Node> nodes = new ArrayList<>();
        int x0 = start.flooredX(), y0 = start.flooredY(), z0 = start.flooredZ();
        int x1 = target.flooredX(), y1 = target.flooredY(), z1 = target.flooredZ();

        int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0), dz = Math.abs(z1 - z0);
        int steps = Math.max(Math.max(dx, dy), dz);

        if (steps == 0) {
            nodes.add(makeWalkNode(x0, y0, z0));
            return nodes;
        }

        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            int nx = (int) Math.round(x0 + t * (x1 - x0));
            int ny = (int) Math.round(y0 + t * (y1 - y0));
            int nz = (int) Math.round(z0 + t * (z1 - z0));
            if (!nodes.isEmpty()) {
                Node prev = nodes.get(nodes.size() - 1);
                if (prev.position.flooredX() == nx && prev.position.flooredY() == ny
                        && prev.position.flooredZ() == nz) {
                    continue;
                }
            }
            nodes.add(makeWalkNode(nx, ny, nz));
        }

        for (int i = 1; i < nodes.size(); i++) {
            nodes.get(i).moveType = inferMoveType(nodes.get(i - 1).position, nodes.get(i).position);
        }
        return nodes;
    }

    private static final IHeuristicStrategy ZERO_HEURISTIC =
            new LinearHeuristicStrategy();

    private static Node makeWalkNode(int x, int y, int z) {
        PathPosition pos  = new PathPosition(x, y, z);
        Node         node = new Node(pos, pos, pos,
                new HeuristicWeights(0, 0, 0, 0),
                ZERO_HEURISTIC,
                0);
        node.moveType = Node.MoveType.WALK;
        return node;
    }

    static Node createSyntheticNode(PathPosition position, Node.MoveType moveType) {
        Node node = new Node(position);
        node.moveType = moveType;
        return node;
    }

    /**
     * Normalizes the player's current feet Y onto the walk layer used by the pathfinder.
     * Thin floor blocks like carpet should keep the player on the current block, while
     * slabs/stairs still bump the start node up into the air block above them.
     */
    private static int resolveStartY(WalkabilityChecker checker, double playerX, double playerY, double playerZ) {
        int x = Mth.floor(playerX);
        int y = Mth.floor(playerY);
        int z = Mth.floor(playerZ);
        if (checker == null) {
            return y;
        }
        if (checker.isPassable(x, y, z)) {
            return y;
        }
        if (checker.isPassable(x, y + 1, z)) {
            return y + 1;
        }
        return Mth.ceil(playerY);
    }

    /**
     * Simplifies a fly path using line-of-sight raycasting (FarmHelper smoothPath style).
     * For each node, tries to skip as many subsequent nodes as possible while still
     * having a clear 4-corner hitbox path. Drastically reduces waypoint count on
     * open paths (e.g. straight flight at altitude).
     */
    private static List<Node> smoothFlyPath(Minecraft mc, List<Node> path) {
        if (mc.level == null || path.size() < 3) return path;

        List<Node> smoothed = new ArrayList<>();
        smoothed.add(path.get(0));
        int lowerIdx = 0;

        while (lowerIdx < path.size() - 1) {
            PathPosition from = path.get(lowerIdx).position;
            int lastValid = lowerIdx + 1;

            // Try extending as far forward as possible with clear LOS
            for (int upper = lowerIdx + 2; upper < path.size(); upper++) {
                PathPosition to = path.get(upper).position;
                if (hasFreePath(mc, from, to)) {
                    lastValid = upper;
                } else {
                    break; // path is blocked - stop extending
                }
            }

            smoothed.add(path.get(lastValid));
            lowerIdx = lastValid;
        }

        return smoothed;
    }

    /**
     * Checks 4-corner line-of-sight between two path positions at feet+head height.
     * Uses the same offsets as FarmHelper's traversable() check.
     */
    private static final double[][] LOS_OFFSETS = {
        {0.05, 0.05}, {0.05, 0.95}, {0.95, 0.05}, {0.95, 0.95}
    };

    private static boolean hasFreePath(Minecraft mc, PathPosition from, PathPosition to) {
        double fx = from.flooredX(), fz = from.flooredZ();
        double tx = to.flooredX(),   tz = to.flooredZ();
        double fy = from.flooredY(), ty = to.flooredY();

        // Check at 4 heights: feet bottom, feet top, head bottom, head top
        double[] checkY = { fy + 0.1, fy + 0.9, fy + 1.1, fy + 1.9 };
        double[] checkTY = { ty + 0.1, ty + 0.9, ty + 1.1, ty + 1.9 };

        for (double[] xzOff : LOS_OFFSETS) {
            for (int h = 0; h < checkY.length; h++) {
                Vec3 start = new Vec3(
                        fx + xzOff[0], checkY[h], fz + xzOff[1]);
                Vec3 end = new Vec3(
                        tx + xzOff[0], checkTY[h], tz + xzOff[1]);
                HitResult hit = mc.level.clip(
                        new ClipContext(
                                start, end,
                                ClipContext.Block.COLLIDER,
                                ClipContext.Fluid.NONE,
                                mc.player));
                if (hit.getType() == HitResult.Type.BLOCK) {
                    return false;
                }
            }
        }
        return true;
    }

    // --- Utilities -----------------------------------------------------------

    /**
     * Converts a PathPosition collection (from PathfinderResult) to a Node list
     * with inferred MoveTypes for PathVisualizer coloring.
     */
    private static List<Node> toNodeList(Collection<PathPosition> positions,
                                          PathfinderConfiguration config) {
        if (positions == null || positions.isEmpty()) return Collections.emptyList();

        List<PathPosition> posList = (positions instanceof List)
                ? (List<PathPosition>) positions
                : new ArrayList<>(positions);

        PathPosition start  = posList.get(0);
        PathPosition target = posList.get(posList.size() - 1);

        // Use a minimal config if none provided (fly case)
        PathfinderConfiguration cfg = config != null ? config : PathfinderConfiguration.DEFAULT;

        List<Node> nodes = new ArrayList<>(posList.size());
        PathPosition prev = null;
        for (PathPosition pos : posList) {
            Node node = new Node(pos, start, target,
                    cfg.heuristicWeights, cfg.heuristicStrategy, nodes.size());
            if (prev != null) {
                node.moveType = inferMoveType(prev, pos);
            }
            nodes.add(node);
            prev = pos;
        }
        return nodes;
    }

    private static Node.MoveType inferMoveType(PathPosition from, PathPosition to) {
        int dy = to.flooredY() - from.flooredY();
        int dx = Math.abs(to.flooredX() - from.flooredX());
        int dz = Math.abs(to.flooredZ() - from.flooredZ());
        if (dy > 1) return Node.MoveType.STEP_UP;   // 2+ block ascent - needs explicit jump; dy=1 left as WALK so collapseAscendingStacks doesn't swallow slab steps
        if (dy < 0) return Node.MoveType.FALL;
        if (dx + dz >= 2) return Node.MoveType.WALK_DIAGONAL;
        return Node.MoveType.WALK;
    }

    private static double computePathLength(List<Node> path) {
        double total = 0;
        for (int i = 0; i < path.size() - 1; i++) {
            PathPosition a = path.get(i).position;
            PathPosition b = path.get(i + 1).position;
            total += a.distance(b);
        }
        return total;
    }

    private static void recordExploredNodes(AStarPathfinder pathfinder) {
        if (pathfinder == null || pathfinder.getClosedSet() == null) {
            return;
        }
        for (long key : pathfinder.getClosedSet()) {
            PathVisualizer.addExplored(unpackX(key), unpackY(key), unpackZ(key));
        }
    }

    // closedSet is packed with RegionKey format; unpack before passing to PathVisualizer:
    private static final long MASK_Y  = 0xFFFL;
    private static final long MASK_XZ = 0x3FFFFFFL;
    private static final int  SHIFT_Z = 12;
    private static final int  SHIFT_X = 38;

    /** Minimum distance between keynodes on straight segments. */
    private static final double KEYNODE_MIN_SPACING  = 12.0;
    /** Direction change (degrees) that forces a new keynode regardless of distance. */
    private static final double KEYNODE_ANGLE_THRESH = 25.0;

    /**
     * Subsamples a smoothed path so keynodes are spaced >= KEYNODE_MIN_SPACING apart,
     * but always kept when the horizontal direction changes by >= KEYNODE_ANGLE_THRESH degrees.
     * First and last nodes are always kept.
     */
    private static List<Node> subsampleKeynodes(List<Node> smoothed) {
        if (smoothed.size() <= 2) return new ArrayList<>(smoothed);
        List<Node> result = new ArrayList<>();
        appendDistinct(result, smoothed.get(0));
        double lastDirX = 0, lastDirZ = 0;
        double accumDist = 0;
        Node prev = smoothed.get(0);
        for (int i = 1; i < smoothed.size() - 1; i++) {
            Node cur = smoothed.get(i);
            double dx = cur.position.centeredX() - prev.position.centeredX();
            double dy = cur.position.centeredY() - prev.position.centeredY();
            double dz = cur.position.centeredZ() - prev.position.centeredZ();
            accumDist += Math.sqrt(dx * dx + dy * dy + dz * dz);
            // Compute horizontal direction change from last kept keynode
            Node last = result.get(result.size() - 1);
            double kDx = cur.position.centeredX() - last.position.centeredX();
            double kDz = cur.position.centeredZ() - last.position.centeredZ();
            double kLen = Math.sqrt(kDx * kDx + kDz * kDz);
            double angleDeg = 0;
            if (kLen > 0.001 && (lastDirX * lastDirX + lastDirZ * lastDirZ) > 0.001) {
                double dot = (kDx / kLen) * lastDirX + (kDz / kLen) * lastDirZ;
                angleDeg = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
            }
            boolean yChangedRaw  = cur.position.flooredY() != last.position.flooredY();
            boolean yChanged     = yChangedRaw
                    && (kLen >= 0.75
                    || isVerticalCritical(cur.moveType)
                    || isVerticalCritical(last.moveType));
            boolean farEnough    = accumDist >= KEYNODE_MIN_SPACING;
            boolean cornerChange = angleDeg >= KEYNODE_ANGLE_THRESH;
            if (farEnough || cornerChange || yChanged) {
                if (appendDistinct(result, cur)) {
                    if (kLen > 0.001) { lastDirX = kDx / kLen; lastDirZ = kDz / kLen; }
                    accumDist = 0;
                    prev = cur;
                }
            }
        }
        appendDistinct(result, smoothed.get(smoothed.size() - 1));
        return collapseAscendingStacks(result);
    }

    private static final double INTERMEDIATE_SPACING = 4.0;

    private static List<Node> insertIntermediates(List<Node> keynodes, WalkabilityChecker checker) {
        if (keynodes.size() < 2) return keynodes;
        List<Node> result = new ArrayList<>();
        for (int i = 0; i < keynodes.size() - 1; i++) {
            appendDistinct(result, keynodes.get(i));
            Node from = keynodes.get(i);
            Node to   = keynodes.get(i + 1);
            // Don't interpolate Y - intermediates at fractional heights land inside terrain
            // on height-changing segments (cliffs, stairs). Skip intermediates for those.
            if (from.position.flooredY() != to.position.flooredY()) continue;
            double dx = to.position.centeredX() - from.position.centeredX();
            double dz = to.position.centeredZ() - from.position.centeredZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > INTERMEDIATE_SPACING) {
                int steps = (int)(dist / INTERMEDIATE_SPACING);
                int iy = from.position.flooredY();
                for (int s = 1; s < steps; s++) {
                    double t = (double) s / steps;
                    int ix = (int) Math.floor(from.position.centeredX() + dx * t);
                    int iz = (int) Math.floor(from.position.centeredZ() + dz * t);
                    // Skip intermediates that land inside solid geometry.
                    if (checker != null
                            && (checker.getTopY(ix, iy, iz) > 0.0
                            || checker.getTopY(ix, iy + 1, iz) > 0.0)) {
                        continue;
                    }
                    Node inter = new Node(new PathPosition(ix, iy, iz));
                    inter.moveType = from.moveType;
                    appendDistinct(result, inter);
                }
            }
        }
        appendDistinct(result, keynodes.getLast());
        return result;
    }

    private static boolean isVerticalCritical(Node.MoveType moveType) {
        return moveType == Node.MoveType.STEP_UP
                || moveType == Node.MoveType.JUMP
                || moveType == Node.MoveType.PARKOUR
                || moveType == Node.MoveType.FALL;
    }

    private static boolean appendDistinct(List<Node> list, Node node) {
        if (node == null) return false;
        if (list.isEmpty()) {
            list.add(node);
            return true;
        }
        Node last = list.get(list.size() - 1);
        if (sameBlock(last, node)) return false;
        list.add(node);
        return true;
    }

    private static List<Node> collapseAscendingStacks(List<Node> nodes) {
        if (nodes.size() <= 2) return nodes;

        List<Node> out = new ArrayList<>(nodes.size());
        out.add(nodes.get(0));

        for (int i = 1; i < nodes.size() - 1; i++) {
            Node cur = nodes.get(i);
            Node last = out.get(out.size() - 1);

            // Stair ascents can produce vertical stacks at same X/Z; keep only the top node.
            if (sameXZ(last, cur)
                    && cur.position.flooredY() >= last.position.flooredY()
                    && isAscendingMove(last.moveType, cur.moveType)) {
                out.set(out.size() - 1, cur);
                continue;
            }

            out.add(cur);
        }

        appendDistinct(out, nodes.get(nodes.size() - 1));
        return out;
    }

    private static boolean sameXZ(Node a, Node b) {
        return a.position.flooredX() == b.position.flooredX()
                && a.position.flooredZ() == b.position.flooredZ();
    }

    private static boolean isAscendingMove(Node.MoveType a, Node.MoveType b) {
        return a == Node.MoveType.STEP_UP || b == Node.MoveType.STEP_UP
                || a == Node.MoveType.JUMP || b == Node.MoveType.JUMP
                || a == Node.MoveType.PARKOUR || b == Node.MoveType.PARKOUR;
    }

    private static boolean sameBlock(Node a, Node b) {
        return a.position.flooredX() == b.position.flooredX()
                && a.position.flooredY() == b.position.flooredY()
                && a.position.flooredZ() == b.position.flooredZ();
    }

    private static int unpackX(long key) {
        long raw = (key >> SHIFT_X) & MASK_XZ;
        // Sign extend from 26 bits: if bit 25 is set, value is negative
        return (raw & (1L << 25)) != 0 ? (int)(raw | ~MASK_XZ) : (int) raw;
    }
    private static int unpackY(long key) {
        long raw = key & MASK_Y;
        // Sign extend from 12 bits: if bit 11 is set, value is negative
        return (raw & (1L << 11)) != 0 ? (int)(raw | ~MASK_Y) : (int) raw;
    }
    private static int unpackZ(long key) {
        long raw = (key >> SHIFT_Z) & MASK_XZ;
        return (raw & (1L << 25)) != 0 ? (int)(raw | ~MASK_XZ) : (int) raw;
    }
}

