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

public final class EtherwarpPathfinder {

    private static final int OPENNESS_RADIUS = 3;
    private static final int BASE_MAX_EXPANSIONS = 192;
    private static final int DETOUR_MAX_EXPANSIONS = 64;
    private static final int MAX_WARPS = 12;
    private static final int RAYCAST_STEP_DEGREES = 1;
    private static final int TARGET_RAYCAST_CANDIDATE_LIMIT = 48;
    private static final int[] TARGET_RAYCAST_RING_RADII = {
            0, 1, 2, 3, 5, 8, 13, 21, 34, 55, 84
    };
    private static final double DETOUR_HEURISTIC_SLACK = 12.0;
    private static final double HORIZONTAL_HEURISTIC_WEIGHT = 0.55;
    private static final double VERTICAL_HEURISTIC_WEIGHT = 0.15;
    private static final double OPENNESS_PRIORITY_WEIGHT = 0.18;
    private static final double OPENNESS_PASSABLE_SCORE = 0.35;

    private final Minecraft mc;
    private final WalkabilityChecker checker;
    private final AtomicBoolean aborted = new AtomicBoolean(false);
    private final Map<CandidateCacheKey, List<PathPosition>> candidateCache = new HashMap<>();
    private final Long2DoubleOpenHashMap opennessCache = new Long2DoubleOpenHashMap();

    private volatile long exploredCount = 0;

    public EtherwarpPathfinder(Minecraft mc, WalkabilityChecker checker) {
        this.mc = mc;
        this.checker = checker;
        this.opennessCache.defaultReturnValue(Double.NaN);
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
                .thenComparingDouble(node -> -node.opennessScore()));
        Long2IntOpenHashMap bestWarps = new Long2IntOpenHashMap();
        bestWarps.defaultReturnValue(Integer.MAX_VALUE);
        Long2DoubleOpenHashMap bestShortcutDistance = new Long2DoubleOpenHashMap();
        bestShortcutDistance.defaultReturnValue(Double.NEGATIVE_INFINITY);

        double startHeuristic = heuristicCost(start, target, heuristicSlack);
        double startOpenness = opennessScore(start);
        SearchNode startNode = new SearchNode(start, null, 0, 0.0, startHeuristic, startOpenness,
                priorityCost(0, startHeuristic, startOpenness));
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

            List<PathPosition> neighbors = new ArrayList<>(getCandidates(current.position, target));
            if (EtherwarpHelper.canEtherwarp(mc, checker, current.position, target) && !neighbors.contains(target)) {
                neighbors.add(0, target);
            }

            neighbors.sort(Comparator
                    .comparingDouble((PathPosition pos) -> prioritizedHeuristicCost(pos, target, heuristicSlack))
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
                open.add(new SearchNode(neighbor, parent, nextWarps, shortcutDistance, heuristic, openness,
                        priorityCost(nextWarps, heuristic, openness)));
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

    private void scanRaycastRing(PathPosition fromFeet, Vec3 eyePos, Rotation center, int radius,
                                 List<PathPosition> candidates, LongOpenHashSet seen) {
        if (radius == 0) {
            addRaycastCandidate(fromFeet, eyePos, center.yaw(), center.pitch(), candidates, seen);
            return;
        }

        for (int yawOffset = -radius; yawOffset <= radius; yawOffset += RAYCAST_STEP_DEGREES) {
            addRaycastCandidate(fromFeet, eyePos, center.yaw() + yawOffset, center.pitch() - radius,
                    candidates, seen);
            addRaycastCandidate(fromFeet, eyePos, center.yaw() + yawOffset, center.pitch() + radius,
                    candidates, seen);
        }
        for (int pitchOffset = -radius + RAYCAST_STEP_DEGREES;
             pitchOffset <= radius - RAYCAST_STEP_DEGREES;
             pitchOffset += RAYCAST_STEP_DEGREES) {
            addRaycastCandidate(fromFeet, eyePos, center.yaw() - radius, center.pitch() + pitchOffset,
                    candidates, seen);
            addRaycastCandidate(fromFeet, eyePos, center.yaw() + radius, center.pitch() + pitchOffset,
                    candidates, seen);
        }
    }

    private void addRaycastCandidate(PathPosition fromFeet, Vec3 eyePos, double yaw, double pitch,
                                     List<PathPosition> candidates, LongOpenHashSet seen) {
        if (candidates.size() >= TARGET_RAYCAST_CANDIDATE_LIMIT || aborted.get()) {
            return;
        }

        double clampedPitch = Mth.clamp(pitch, -84.0, 84.0);
        Vec3 direction = directionFromRotation((float) Mth.wrapDegrees(yaw), (float) clampedPitch);
        Vec3 end = eyePos.add(direction.scale(EtherwarpHelper.MAX_ETHERWARP_DISTANCE));
        BlockHitResult hit = mc.level.clip(new ClipContext(
                eyePos,
                end,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                mc.player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return;
        }

        BlockPos hitBlock = hit.getBlockPos();
        PathPosition landingFeet = EtherwarpHelper.resolveTargetFeet(
                checker,
                hitBlock.getX(),
                hitBlock.getY(),
                hitBlock.getZ());
        if (landingFeet == null || landingFeet.equals(fromFeet)) {
            return;
        }

        long landingKey = pack(landingFeet);
        if (!seen.add(landingKey)) {
            return;
        }

        if (EtherwarpHelper.findVisibleTargetPoint(mc, checker, eyePos, landingFeet) != null) {
            candidates.add(landingFeet);
        }
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

    private static double heuristicCost(PathPosition from, PathPosition to, double slack) {
        double adjustedDistance = Math.max(0.0, from.distance(to) - slack);
        double dx = from.x - to.x;
        double dz = from.z - to.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        double adjustedHorizontalDistance = Math.max(0.0, horizontalDistance - slack);
        double verticalDistance = Math.abs(from.y - to.y);
        return adjustedDistance / EtherwarpHelper.MAX_ETHERWARP_DISTANCE
                + adjustedHorizontalDistance / EtherwarpHelper.MAX_ETHERWARP_DISTANCE * HORIZONTAL_HEURISTIC_WEIGHT
                + verticalDistance / EtherwarpHelper.MAX_ETHERWARP_DISTANCE * VERTICAL_HEURISTIC_WEIGHT;
    }

    private double prioritizedHeuristicCost(PathPosition from, PathPosition to, double slack) {
        return heuristicCost(from, to, slack) - opennessScore(from) * OPENNESS_PRIORITY_WEIGHT;
    }

    private static double priorityCost(int warps, double heuristic, double openness) {
        return warps + heuristic - openness * OPENNESS_PRIORITY_WEIGHT;
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
                              double heuristicCost, double opennessScore, double priorityCost) {
    }
}
