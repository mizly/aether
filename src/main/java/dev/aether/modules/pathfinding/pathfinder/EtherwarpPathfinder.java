package dev.aether.modules.pathfinding.pathfinder;

import dev.aether.modules.pathfinding.Node;
import dev.aether.modules.pathfinding.debug.PathVisualizer;
import dev.aether.modules.pathfinding.etherwarp.EtherwarpHelper;
import dev.aether.modules.pathfinding.movement.WalkabilityChecker;
import dev.aether.modules.pathfinding.wrapper.PathPosition;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

public final class EtherwarpPathfinder {

    private static final int OPENNESS_RADIUS = 3;
    private static final int BALCONY_RADIUS = 4;
    private static final int BASE_MAX_EXPANSIONS = 192;
    private static final int DETOUR_MAX_EXPANSIONS = 64;
    private static final int MAX_WARPS = 12;
    private static final int TARGET_RAYCAST_CANDIDATE_LIMIT = 24;
    private static final int UPPER_CANDIDATE_LIMIT = 8;
    private static final int[] TARGET_RAYCAST_RING_RADII = {
            0, 1, 2, 3, 5, 8, 13, 21, 34, 55, 84
    };
    private static final double GOAL_DIRECTED_MAX_HOP = 57.0;
    private static final double GOAL_DIRECTED_MIN_PROGRESS = 18.0;
    private static final double[] GOAL_LATERAL_OFFSETS = {
            0.0, -2.0, 2.0, -5.0, 5.0, -9.0, 9.0
    };
    private static final int[] GOAL_VERTICAL_OFFSETS = {
            0, -1, 1, -2, 2, -4, 4, -8, 8
    };
    private static final double DETOUR_HEURISTIC_SLACK = 12.0;
    private static final double WARP_COUNT_HEURISTIC_WEIGHT = 0.75;
    private static final double CONTINUOUS_HEURISTIC_WEIGHT = 0.25;
    private static final double VERTICAL_HEURISTIC_WEIGHT = 0.05;
    private static final double OPENNESS_PRIORITY_WEIGHT = 0.18;
    private static final double BALCONY_PRIORITY_WEIGHT = 0.90;
    private static final double BALCONY_MIN_ISOLATION = 0.30;
    private static final int HIGH_VANTAGE_MIN_RISE = 5;
    private static final double OPENNESS_PASSABLE_SCORE = 0.35;
    private static final double[] BALCONY_SCAN_PITCHES = {
            0.0, -10.0, -22.0, -35.0, -48.0, -62.0
    };
    private static final double[] BALCONY_SCAN_YAW_OFFSETS = {
            0.0, 10.0, -10.0, 20.0, -20.0, 30.0, -30.0, 60.0, -60.0, 90.0,
            -90.0, 120.0, -120.0, 150.0, -150.0, 180.0
    };

    private final Minecraft mc;
    private final WalkabilityChecker checker;
    private final AtomicBoolean aborted = new AtomicBoolean(false);
    private final Map<CandidateCacheKey, List<PathPosition>> candidateCache = new HashMap<>();
    private final Long2DoubleOpenHashMap opennessCache = new Long2DoubleOpenHashMap();
    private final Long2DoubleOpenHashMap balconyIsolationCache = new Long2DoubleOpenHashMap();

    private volatile long exploredCount = 0;

    public EtherwarpPathfinder(Minecraft mc, WalkabilityChecker checker) {
        this.mc = mc;
        this.checker = checker;
        this.opennessCache.defaultReturnValue(Double.NaN);
        this.balconyIsolationCache.defaultReturnValue(Double.NaN);
    }

    public CompletionStage<Result> findPath(PathPosition start, PathPosition target) {
        return CompletableFuture.supplyAsync(() -> findPathSync(start, target));
    }

    public Result findPathSync(PathPosition start, PathPosition target) {
        return search(start.floor(), target.floor());
    }

    public void abort() {
        aborted.set(true);
    }

    public long getExploredCount() {
        return exploredCount;
    }

    private Result search(PathPosition start, PathPosition target) {
        exploredCount = 0;
        if (mc.level == null || mc.player == null || checker == null) {
            return null;
        }
        if (!EtherwarpHelper.isValidLandingFeet(checker, target)) {
            return null;
        }

        SearchOutcome directOutcome = searchPass(start, target, BASE_MAX_EXPANSIONS, 0.0);
        exploredCount += directOutcome.exploredCount();
        if (directOutcome.path() != null || aborted.get()) {
            return directOutcome.path() == null ? null : new Result(directOutcome.path(), exploredCount);
        }

        // Retry with a softer goal bias so small sidesteps or backtracks are not starved
        // behind "closer but still blocked" candidates when a wall sits in front of the target.
        SearchOutcome detourOutcome = searchPass(start, target, DETOUR_MAX_EXPANSIONS, DETOUR_HEURISTIC_SLACK);
        exploredCount += detourOutcome.exploredCount();
        if (detourOutcome.path() == null) {
            return null;
        }
        return new Result(detourOutcome.path(), exploredCount);
    }

    private SearchOutcome searchPass(PathPosition start, PathPosition target, int maxExpansions,
                                     double heuristicSlack) {
        PriorityQueue<SearchNode> open = new PriorityQueue<>(Comparator
                .comparingDouble(SearchNode::priorityCost)
                .thenComparingInt(SearchNode::warps)
                .thenComparingDouble(SearchNode::heuristicCost)
                .thenComparingDouble(node -> -node.balconyScore())
                .thenComparingDouble(node -> -node.opennessScore()));
        Long2IntOpenHashMap bestWarps = new Long2IntOpenHashMap();
        bestWarps.defaultReturnValue(Integer.MAX_VALUE);
        Long2DoubleOpenHashMap bestShortcutDistance = new Long2DoubleOpenHashMap();
        bestShortcutDistance.defaultReturnValue(Double.NEGATIVE_INFINITY);

        double startHeuristic = heuristicCost(start, target, heuristicSlack);
        double startOpenness = opennessScore(start);
        SearchNode startNode = new SearchNode(start, null, 0, 0.0, startHeuristic, startOpenness, 0.0,
                priorityCost(0, startHeuristic, startOpenness, 0.0));
        open.add(startNode);
        bestWarps.put(pack(start), 0);
        bestShortcutDistance.put(pack(start), 0.0);

        long passExploredCount = 0;
        while (!open.isEmpty() && passExploredCount < maxExpansions) {
            if (aborted.get()) {
                return new SearchOutcome(null, passExploredCount);
            }

            SearchNode current = open.poll();
            long currentKey = pack(current.position);
            if (current.warps != bestWarps.get(currentKey)
                    || current.shortcutDistance < bestShortcutDistance.get(currentKey)) {
                continue;
            }

            passExploredCount++;
            if (PathVisualizer.shouldCaptureExploredNodes()) {
                PathVisualizer.addExplored(
                        current.position.flooredX(),
                        current.position.flooredY(),
                        current.position.flooredZ());
            }

            if (current.position.equals(target)) {
                return new SearchOutcome(buildPath(current), passExploredCount);
            }

            if (current.warps >= MAX_WARPS) {
                continue;
            }

            if (EtherwarpHelper.canEtherwarp(mc, checker, current.position, target)) {
                SearchNode parent = current;
                while (parent.parent != null
                        && EtherwarpHelper.canEtherwarp(mc, checker, parent.parent.position, target)) {
                    parent = parent.parent;
                }
                SearchNode goal = new SearchNode(target, parent, parent.warps + 1,
                        parent.position.distance(target), 0.0, 0.0, 0.0, parent.warps + 1);
                return new SearchOutcome(buildPath(goal), passExploredCount);
            }
            List<PathPosition> neighbors = new ArrayList<>(getCandidates(current.position, target));

            neighbors.sort(Comparator
                    .comparingDouble((PathPosition pos) -> prioritizedHeuristicCost(
                            current.position, pos, target, heuristicSlack))
                    .thenComparingDouble(pos -> -balconyScore(current.position, pos))
                    .thenComparingDouble(pos -> -opennessScore(pos))
                    .thenComparingDouble(pos -> -current.position.distance(pos)));

            for (PathPosition neighbor : neighbors) {
                SearchNode parent = current;
                int nextWarps = current.warps + 1;
                if (current.parent != null
                        && EtherwarpHelper.canEtherwarp(mc, checker, current.parent.position, neighbor)) {
                    parent = current.parent;
                    nextWarps = current.parent.warps + 1;
                }

                long neighborKey = pack(neighbor);
                double shortcutDistance = parent.position.distance(neighbor);
                int previousBestWarps = bestWarps.get(neighborKey);
                if (nextWarps > previousBestWarps) {
                    continue;
                }
                if (nextWarps == previousBestWarps
                        && shortcutDistance <= bestShortcutDistance.get(neighborKey)) {
                    continue;
                }

                bestWarps.put(neighborKey, nextWarps);
                bestShortcutDistance.put(neighborKey, shortcutDistance);
                double heuristic = heuristicCost(neighbor, target, heuristicSlack);
                double openness = opennessScore(neighbor);
                double balcony = balconyScore(parent.position, neighbor);
                open.add(new SearchNode(neighbor, parent, nextWarps, shortcutDistance, heuristic, openness, balcony,
                        priorityCost(nextWarps, heuristic, openness, balcony)));
            }
        }

        return new SearchOutcome(null, passExploredCount);
    }

    private List<PathPosition> getCandidates(PathPosition fromFeet, PathPosition target) {
        CandidateCacheKey key = new CandidateCacheKey(pack(fromFeet), pack(target));
        List<PathPosition> cached = candidateCache.get(key);
        if (cached != null) {
            return cached;
        }

        List<PathPosition> candidates = new ArrayList<>();
        LongOpenHashSet seen = new LongOpenHashSet();
        Vec3 eyePos = EtherwarpHelper.getEyePosition(mc, fromFeet);
        if (eyePos == null) {
            return List.of();
        }

        Rotation targetRotation = rotationToTargetBlock(eyePos, target);
        List<PathPosition> balconyCandidates = scanBalconyCandidates(fromFeet, eyePos, targetRotation);
        for (PathPosition balconyCandidate : balconyCandidates) {
            if (seen.add(pack(balconyCandidate))) {
                candidates.add(balconyCandidate);
            }
        }
        List<PathPosition> goalCandidates = new ArrayList<>();
        addGoalDirectedCandidates(fromFeet, target, eyePos, goalCandidates, seen);
        candidates.addAll(goalCandidates);
        if (hasStrongGoalProgress(fromFeet, target, goalCandidates)) {
            List<PathPosition> finalized = List.copyOf(candidates);
            candidateCache.put(key, finalized);
            return finalized;
        }

        for (int radius : TARGET_RAYCAST_RING_RADII) {
            if (aborted.get()) {
                return List.of();
            }

            scanRaycastRing(fromFeet, eyePos, targetRotation, radius, candidates, seen);
            if (candidates.size() >= TARGET_RAYCAST_CANDIDATE_LIMIT) {
                break;
            }
        }

        List<PathPosition> finalized = List.copyOf(candidates);
        candidateCache.put(key, finalized);
        return finalized;
    }

    private List<PathPosition> scanBalconyCandidates(PathPosition fromFeet, Vec3 eyePos, Rotation targetRotation) {
        List<PathPosition> balconyCandidates = new ArrayList<>();
        LongOpenHashSet balconySeen = new LongOpenHashSet();
        LongOpenHashSet scannedColumns = new LongOpenHashSet();
        for (double pitch : BALCONY_SCAN_PITCHES) {
            for (double yawOffset : BALCONY_SCAN_YAW_OFFSETS) {
                if (aborted.get() || balconyCandidates.size() >= UPPER_CANDIDATE_LIMIT) {
                    break;
                }
                BlockPos hit = raycastBlock(eyePos, targetRotation.yaw() + yawOffset, pitch);
                if (hit == null || !scannedColumns.add(BlockPos.asLong(hit.getX(), 0, hit.getZ()))) {
                    continue;
                }
                PathPosition landing = findUpperLanding(eyePos, hit, fromFeet.flooredY() + 1,
                        block -> EtherwarpHelper.resolveTargetFeet(checker, block.getX(), block.getY(), block.getZ()),
                        feet -> isUsefulUpperLanding(fromFeet, feet)
                                && EtherwarpHelper.findVisibleTargetPoint(mc, checker, eyePos, feet) != null,
                        aborted::get);
                if (landing != null && balconySeen.add(pack(landing))) {
                    balconyCandidates.add(landing);
                }
            }
        }

        return balconyCandidates.stream()
                .sorted(Comparator
                        .comparingDouble((PathPosition candidate) -> -balconyScore(fromFeet, candidate))
                        .thenComparingDouble(candidate -> -fromFeet.distance(candidate)))
                .toList();
    }

    static PathPosition findUpperLanding(Vec3 eyePos, BlockPos hit, int minFeetY,
                                          Function<BlockPos, PathPosition> resolve,
                                          Predicate<PathPosition> reachable, BooleanSupplier aborted) {
        double dx = Math.max(0.0, Math.abs(hit.getX() + 0.5 - eyePos.x) - 0.5);
        double dz = Math.max(0.0, Math.abs(hit.getZ() + 0.5 - eyePos.z) - 0.5);
        double verticalRangeSquared = EtherwarpHelper.MAX_ETHERWARP_DISTANCE_SQ + 1.0 - dx * dx - dz * dz;
        if (verticalRangeSquared < 0.0) {
            return null;
        }
        int maxSupportY = Mth.floor(eyePos.y + Math.sqrt(verticalRangeSquared));
        for (int y = minFeetY - 1; y <= maxSupportY && !aborted.getAsBoolean(); y++) {
            PathPosition landing = resolve.apply(new BlockPos(hit.getX(), y, hit.getZ()));
            if (landing != null && landing.flooredY() >= minFeetY && reachable.test(landing)) {
                return landing;
            }
        }
        return null;
    }

    private boolean isUsefulUpperLanding(PathPosition fromFeet, PathPosition candidate) {
        int rise = candidate.flooredY() - fromFeet.flooredY();
        return rise > 0
                && (rise >= HIGH_VANTAGE_MIN_RISE
                || balconyIsolationScore(candidate) >= BALCONY_MIN_ISOLATION);
    }

    private void addGoalDirectedCandidates(PathPosition fromFeet, PathPosition target, Vec3 eyePos,
                                           List<PathPosition> candidates, LongOpenHashSet seen) {
        PathPosition idealHop = idealHopPosition(fromFeet, target);
        double dx = target.x - fromFeet.x;
        double dz = target.z - fromFeet.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        double perpendicularX = horizontalDistance < 1.0e-6 ? 1.0 : -dz / horizontalDistance;
        double perpendicularZ = horizontalDistance < 1.0e-6 ? 0.0 : dx / horizontalDistance;

        for (double lateralOffset : GOAL_LATERAL_OFFSETS) {
            int x = Mth.floor(idealHop.x + perpendicularX * lateralOffset);
            int z = Mth.floor(idealHop.z + perpendicularZ * lateralOffset);
            addGoalColumnCandidate(fromFeet, target, eyePos, x, z, idealHop.flooredY(), candidates, seen);
        }
    }

    private void addGoalColumnCandidate(PathPosition fromFeet, PathPosition target, Vec3 eyePos,
                                        int x, int z, int expectedFeetY,
                                        List<PathPosition> candidates, LongOpenHashSet seen) {
        int[] baseFeetYValues = {
                expectedFeetY,
                fromFeet.flooredY(),
                target.flooredY()
        };
        LongOpenHashSet triedBlocks = new LongOpenHashSet();
        for (int baseFeetY : baseFeetYValues) {
            for (int verticalOffset : GOAL_VERTICAL_OFFSETS) {
                int targetBlockY = baseFeetY + verticalOffset - 1;
                if (!triedBlocks.add(BlockPos.asLong(x, targetBlockY, z))) {
                    continue;
                }

                PathPosition landingFeet = EtherwarpHelper.resolveTargetFeet(checker, x, targetBlockY, z);
                if (landingFeet == null || landingFeet.equals(fromFeet) || !seen.add(pack(landingFeet))) {
                    continue;
                }
                if (EtherwarpHelper.findVisibleTargetPoint(mc, checker, eyePos, landingFeet) != null) {
                    candidates.add(landingFeet);
                    return;
                }
            }
        }
    }

    private static boolean hasStrongGoalProgress(PathPosition fromFeet, PathPosition target,
                                                 List<PathPosition> candidates) {
        double remainingDistance = fromFeet.distance(target);
        double requiredProgress = Math.min(GOAL_DIRECTED_MIN_PROGRESS, remainingDistance * 0.35);
        for (PathPosition candidate : candidates) {
            if (candidate.distance(target) <= remainingDistance - requiredProgress) {
                return true;
            }
        }
        return false;
    }

    static PathPosition idealHopPosition(PathPosition fromFeet, PathPosition target) {
        double distance = fromFeet.distance(target);
        if (distance <= GOAL_DIRECTED_MAX_HOP) {
            return target;
        }

        int remainingWarps = Math.max(1, (int) Math.ceil(distance / GOAL_DIRECTED_MAX_HOP));
        double fraction = 1.0 / remainingWarps;
        return new PathPosition(
                fromFeet.x + (target.x - fromFeet.x) * fraction,
                fromFeet.y + (target.y - fromFeet.y) * fraction,
                fromFeet.z + (target.z - fromFeet.z) * fraction);
    }

    private void scanRaycastRing(PathPosition fromFeet, Vec3 eyePos, Rotation center, int radius,
                                 List<PathPosition> candidates, LongOpenHashSet seen) {
        if (radius == 0) {
            addRaycastCandidate(fromFeet, eyePos, center.yaw(), center.pitch(), candidates, seen);
            return;
        }

        int[][] offsets = {
                {-radius, -radius}, {0, -radius}, {radius, -radius},
                {-radius, 0},                       {radius, 0},
                {-radius, radius},  {0, radius},  {radius, radius}
        };
        for (int[] offset : offsets) {
            addRaycastCandidate(fromFeet, eyePos, center.yaw() + offset[0], center.pitch() + offset[1],
                    candidates, seen);
        }
    }

    private void addRaycastCandidate(PathPosition fromFeet, Vec3 eyePos, double yaw, double pitch,
                                     List<PathPosition> candidates, LongOpenHashSet seen) {
        if (candidates.size() >= TARGET_RAYCAST_CANDIDATE_LIMIT || aborted.get()) {
            return;
        }

        BlockPos hitBlock = raycastBlock(eyePos, yaw, pitch);
        if (hitBlock == null) {
            return;
        }
        PathPosition landingFeet = EtherwarpHelper.resolveTargetFeet(
                checker, hitBlock.getX(), hitBlock.getY(), hitBlock.getZ());
        if (landingFeet != null && !landingFeet.equals(fromFeet) && seen.add(pack(landingFeet))
                && EtherwarpHelper.findVisibleTargetPoint(mc, checker, eyePos, landingFeet) != null) {
            candidates.add(landingFeet);
        }
    }

    private BlockPos raycastBlock(Vec3 eyePos, double yaw, double pitch) {
        double clampedPitch = Mth.clamp(pitch, -84.0, 84.0);
        Vec3 direction = directionFromRotation((float) Mth.wrapDegrees(yaw), (float) clampedPitch);
        Vec3 end = eyePos.add(direction.scale(EtherwarpHelper.MAX_ETHERWARP_DISTANCE));
        BlockHitResult hit = mc.level.clip(new ClipContext(
                eyePos,
                end,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                mc.player));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }

    private static Rotation rotationToTargetBlock(Vec3 eyePos, PathPosition target) {
        BlockPos targetBlock = EtherwarpHelper.getTargetBlock(target);
        Vec3 targetPoint = new Vec3(
                targetBlock.getX() + 0.5,
                targetBlock.getY() + 0.5,
                targetBlock.getZ() + 0.5);
        Vec3 delta = targetPoint.subtract(eyePos);
        double horizontalDistance = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        double yaw = Math.toDegrees(Math.atan2(-delta.x, delta.z));
        double pitch = Math.toDegrees(Math.atan2(-delta.y, horizontalDistance));
        return new Rotation(Mth.wrapDegrees(yaw), Mth.clamp(pitch, -84.0, 84.0));
    }

    private static Vec3 directionFromRotation(float yawDeg, float pitchDeg) {
        float yawRad = yawDeg * ((float) Math.PI / 180.0f);
        float pitchRad = pitchDeg * ((float) Math.PI / 180.0f);
        float cosPitch = Mth.cos(pitchRad);
        return new Vec3(
                -Mth.sin(yawRad) * cosPitch,
                -Mth.sin(pitchRad),
                Mth.cos(yawRad) * cosPitch);
    }

    static double heuristicCost(PathPosition from, PathPosition to, double slack) {
        double adjustedDistance = Math.max(0.0, from.distance(to) - slack);
        double normalizedDistance = adjustedDistance / EtherwarpHelper.MAX_ETHERWARP_DISTANCE;
        double minimumWarps = adjustedDistance <= 1.0e-6 ? 0.0 : Math.ceil(normalizedDistance - 1.0e-6);
        double verticalDistance = Math.abs(from.y - to.y);
        return minimumWarps * WARP_COUNT_HEURISTIC_WEIGHT
                + normalizedDistance * CONTINUOUS_HEURISTIC_WEIGHT
                + verticalDistance / EtherwarpHelper.MAX_ETHERWARP_DISTANCE * VERTICAL_HEURISTIC_WEIGHT;
    }

    private double prioritizedHeuristicCost(PathPosition current, PathPosition candidate,
                                            PathPosition target, double slack) {
        return heuristicCost(candidate, target, slack)
                - opennessScore(candidate) * OPENNESS_PRIORITY_WEIGHT
                - balconyScore(current, candidate) * BALCONY_PRIORITY_WEIGHT;
    }

    private static double priorityCost(int warps, double heuristic, double openness, double balcony) {
        return warps + heuristic
                - openness * OPENNESS_PRIORITY_WEIGHT
                - balcony * BALCONY_PRIORITY_WEIGHT;
    }

    private double balconyScore(PathPosition fromFeet, PathPosition landingFeet) {
        double isolation = balconyIsolationScore(landingFeet);
        int rise = landingFeet.flooredY() - fromFeet.flooredY();
        return balconyPriority(isolation, rise);
    }

    private double balconyIsolationScore(PathPosition feet) {
        long key = pack(feet);
        double cached = balconyIsolationCache.get(key);
        if (!Double.isNaN(cached)) {
            return cached;
        }

        int x = feet.flooredX();
        int supportY = feet.flooredY() - 1;
        int z = feet.flooredZ();
        double supportedWeight = 0.0;
        double totalWeight = 0.0;
        for (int dx = -BALCONY_RADIUS; dx <= BALCONY_RADIUS; dx++) {
            for (int dz = -BALCONY_RADIUS; dz <= BALCONY_RADIUS; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }

                double weight = 1.0 / (1.0 + Math.sqrt(dx * dx + dz * dz));
                totalWeight += weight;
                if (checker.hasWalkableTop(x + dx, supportY, z + dz)) {
                    supportedWeight += weight;
                }
            }
        }

        double isolation = supportIsolationScore(supportedWeight, totalWeight) * opennessScore(feet);
        balconyIsolationCache.put(key, isolation);
        return isolation;
    }

    static double supportIsolationScore(double supportedWeight, double totalWeight) {
        if (totalWeight <= 0.0) {
            return 0.0;
        }
        return Mth.clamp(1.0 - supportedWeight / totalWeight, 0.0, 1.0);
    }

    static double balconyPriority(double isolation, int rise) {
        double elevationBonus = Mth.clamp(rise / 8.0, 0.0, 1.0);
        return Mth.clamp(isolation, 0.0, 1.0) * 0.40 + elevationBonus * 0.60;
    }

    private double opennessScore(PathPosition feet) {
        long key = pack(feet);
        double cached = opennessCache.get(key);
        if (!Double.isNaN(cached)) {
            return cached;
        }

        double openness = computeOpennessScore(feet);
        opennessCache.put(key, openness);
        return openness;
    }

    private double computeOpennessScore(PathPosition feet) {
        if (checker == null || feet == null) {
            return 0.0;
        }

        int x = feet.flooredX();
        int y = feet.flooredY();
        int z = feet.flooredZ();

        double openWeight = 0.0;
        double totalWeight = 0.0;

        // Measure how much free player space surrounds the landing block over a 7x7 area.
        for (int dy = 0; dy <= 2; dy++) {
            for (int dx = -OPENNESS_RADIUS; dx <= OPENNESS_RADIUS; dx++) {
                for (int dz = -OPENNESS_RADIUS; dz <= OPENNESS_RADIUS; dz++) {
                    if (dx == 0 && dz == 0 && dy <= 1) {
                        continue;
                    }

                    double weight = opennessSampleWeight(dx, dy, dz);
                    totalWeight += weight;
                    openWeight += weight * opennessSampleScore(x + dx, y + dy, z + dz);
                }
            }
        }

        return totalWeight <= 0.0 ? 0.0 : openWeight / totalWeight;
    }

    private static double opennessSampleWeight(int dx, int dy, int dz) {
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        double distanceWeight = 1.0 / (1.0 + horizontalDistance);
        if (dy == 2) {
            if (horizontalDistance < 0.5) {
                return 0.8;
            }
            return distanceWeight * 0.5;
        }
        return distanceWeight;
    }

    private double opennessSampleScore(int x, int y, int z) {
        if (checker.isDangerous(x, y, z)) {
            return 0.0;
        }
        if (checker.isAir(x, y, z)) {
            return 1.0;
        }
        return checker.isPassable(x, y, z) ? OPENNESS_PASSABLE_SCORE : 0.0;
    }

    private static List<Node> buildPath(SearchNode goal) {
        LinkedList<Node> path = new LinkedList<>();
        SearchNode cursor = goal;
        while (cursor != null) {
            Node node = createNode(cursor.position, Node.MoveType.ETHERWARP);
            node.isKeynode = true;
            path.addFirst(node);
            cursor = cursor.parent;
        }
        return List.copyOf(path);
    }

    private static Node createNode(PathPosition position, Node.MoveType moveType) {
        Node node = new Node(position);
        node.moveType = moveType;
        return node;
    }

    private static long pack(PathPosition pos) {
        return BlockPos.asLong(pos.flooredX(), pos.flooredY(), pos.flooredZ());
    }

    public record Result(List<Node> path, long exploredCount) {
    }

    private record SearchOutcome(List<Node> path, long exploredCount) {
    }

    private record CandidateCacheKey(long from, long target) {
    }

    private record Rotation(double yaw, double pitch) {
    }

    private record SearchNode(PathPosition position, SearchNode parent, int warps, double shortcutDistance,
                              double heuristicCost, double opennessScore, double balconyScore,
                              double priorityCost) {
    }
}
