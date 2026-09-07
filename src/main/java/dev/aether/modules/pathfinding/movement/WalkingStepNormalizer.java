package dev.aether.modules.pathfinding.movement;

import dev.aether.modules.pathfinding.Node;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

final class WalkingStepNormalizer {
    private WalkingStepNormalizer() {}

    static List<Node> normalize(List<Node> nodes, WalkabilityChecker checker) {
        if (checker == null) return nodes;
        return normalize(nodes, node -> {
            var pos = node.position;
            return checker.isWalkable(pos.flooredX(), pos.flooredY(), pos.flooredZ())
                    && !verticalTravel(node, checker);
        }, node -> {
            var pos = node.position;
            return checker.isPassable(pos.flooredX(), pos.flooredY() - 1, pos.flooredZ())
                    && !verticalTravel(node, checker);
        });
    }

    static List<Node> normalize(List<Node> nodes, Predicate<Node> supported, Predicate<Node> unsupportedLift) {
        if (nodes.size() < 3) return nodes;
        List<Node> result = new ArrayList<>(nodes.size());
        result.add(nodes.getFirst());
        for (int i = 1; i + 1 < nodes.size(); i++) {
            Node before = nodes.get(i - 1);
            Node lift = nodes.get(i);
            Node after = nodes.get(i + 1);
            if (!isStepLift(before, lift, after) || !supported.test(before)
                    || !unsupportedLift.test(lift) || !supported.test(after)) {
                result.add(lift);
            }
        }
        result.add(nodes.getLast());
        return result;
    }

    private static boolean isStepLift(Node before, Node lift, Node after) {
        if (lift.moveType != Node.MoveType.WALK && lift.moveType != Node.MoveType.WALK_DIAGONAL) return false;
        var from = before.position;
        var up = lift.position;
        var to = after.position;
        int dx = Math.abs(to.flooredX() - up.flooredX());
        int dz = Math.abs(to.flooredZ() - up.flooredZ());
        return from.flooredX() == up.flooredX() && from.flooredZ() == up.flooredZ()
                && up.flooredY() == from.flooredY() + 1 && to.flooredY() == up.flooredY()
                && Math.max(dx, dz) == 1;
    }

    private static boolean verticalTravel(Node node, WalkabilityChecker checker) {
        if (node.moveType == Node.MoveType.CLIMB || node.moveType == Node.MoveType.SWIM) return true;
        var pos = node.position;
        int x = pos.flooredX(), y = pos.flooredY(), z = pos.flooredZ();
        return checker.isClimbable(x, y, z) || checker.isClimbable(x, y - 1, z)
                || checker.isWater(x, y, z) || checker.isWater(x, y - 1, z);
    }
}
