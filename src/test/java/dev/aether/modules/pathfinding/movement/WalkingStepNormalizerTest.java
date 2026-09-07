package dev.aether.modules.pathfinding.movement;

import dev.aether.modules.pathfinding.Node;
import dev.aether.modules.pathfinding.wrapper.PathPosition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class WalkingStepNormalizerTest {
    @Test
    void connectsTheGroundDirectlyToASupportedSlabExit() {
        Node ground = node(0, 0, 0);
        Node lift = node(0, 1, 0);
        Node slab = node(0, 1, 1);
        List<Node> path = List.of(ground, lift, slab);
        var normalized = WalkingStepNormalizer.normalize(path, Set.of(ground, slab)::contains, lift::equals);
        assertEquals(List.of(ground, slab), normalized);
        assertSame(ground, normalized.getFirst());
        assertSame(slab, normalized.getLast());
        assertEquals(3, path.size());
    }

    @Test
    void removesEachLiftOnAlternatingBlockAndSlabInclines() {
        Node ground = node(0, 0, 0);
        Node firstLift = node(0, 1, 0);
        Node slab = node(0, 1, 1);
        Node block = node(0, 1, 2);
        Node secondLift = node(0, 2, 2);
        Node upperSlab = node(0, 2, 3);
        var supported = Set.of(ground, slab, block, upperSlab);
        assertEquals(List.of(ground, slab, block, upperSlab), WalkingStepNormalizer.normalize(
                List.of(ground, firstLift, slab, block, secondLift, upperSlab), supported::contains,
                Set.of(firstLift, secondLift)::contains));
    }

    @Test
    void preservesTheGroundCornerBeforeAStairTurn() {
        Node approach = node(-2, 0, 0);
        Node corner = node(0, 0, 0);
        Node lift = node(0, 1, 0);
        Node stair = node(0, 1, 1);
        assertEquals(List.of(approach, corner, stair), WalkingStepNormalizer.normalize(
                List.of(approach, corner, lift, stair), Set.of(approach, corner, stair)::contains, lift::equals));
    }

    @Test
    void preservesSupportedOrClimbableIntermediatePoints() {
        var path = List.of(node(0, 0, 0), node(0, 1, 0), node(0, 1, 1));
        assertEquals(path, WalkingStepNormalizer.normalize(path, node -> true, node -> false));
        for (Node vertical : path) {
            assertEquals(path, WalkingStepNormalizer.normalize(path,
                    node -> node != vertical, node -> node != vertical));
        }
    }

    @Test
    void preservesExplicitJumpMarkersAndMultiBlockAscents() {
        for (Node.MoveType type : List.of(Node.MoveType.STEP_UP, Node.MoveType.JUMP, Node.MoveType.PARKOUR,
                Node.MoveType.CLIMB, Node.MoveType.SWIM)) {
            Node lift = node(0, 1, 0);
            lift.moveType = type;
            var path = List.of(node(0, 0, 0), lift, node(0, 1, 1));
            assertEquals(path, WalkingStepNormalizer.normalize(path, node -> true, node -> true));
        }
        var highStep = List.of(node(0, 0, 0), node(0, 2, 0), node(0, 2, 1));
        assertEquals(highStep, WalkingStepNormalizer.normalize(highStep, node -> true, node -> true));
    }

    @Test
    void preservesDistantLowerAndUnsupportedExits() {
        for (Node exit : List.of(node(0, 1, 3), node(0, 0, 1), node(0, 2, 0))) {
            var path = List.of(node(0, 0, 0), node(0, 1, 0), exit);
            assertEquals(path, WalkingStepNormalizer.normalize(path, node -> true, node -> true));
        }
        var path = List.of(node(0, 0, 0), node(0, 1, 0), node(0, 1, 1));
        assertEquals(path, WalkingStepNormalizer.normalize(path, path.getFirst()::equals, node -> true));
    }

    @Test
    void preservesShortPathsAndVerticalEndpoints() {
        for (var path : List.of(List.<Node>of(), List.of(node(0, 0, 0)),
                List.of(node(0, 0, 0), node(0, 1, 0)),
                List.of(node(0, 0, -1), node(0, 0, 0), node(0, 1, 0)))) {
            assertEquals(path, WalkingStepNormalizer.normalize(path, node -> true, node -> true));
        }
    }

    private static Node node(int x, int y, int z) {
        return new Node(new PathPosition(x, y, z));
    }
}
