package dev.aether.modules.pathfinding.movement

import dev.aether.modules.pathfinding.Node
import java.util.ArrayList
import java.util.function.Predicate

internal class WalkingStepNormalizer private constructor() {
    companion object {
        @JvmStatic
        fun normalize(nodes: List<Node>, checker: WalkabilityChecker?): List<Node> {
            if (checker == null) return nodes
            return normalize(nodes, Predicate { node ->
                val pos = node.position
                checker.isWalkable(pos.flooredX(), pos.flooredY(), pos.flooredZ()) && !verticalTravel(node, checker)
            }, Predicate { node ->
                val pos = node.position
                checker.isPassable(pos.flooredX(), pos.flooredY() - 1, pos.flooredZ()) && !verticalTravel(node, checker)
            })
        }

        @JvmStatic
        fun normalize(nodes: List<Node>, supported: Predicate<Node>, unsupportedLift: Predicate<Node>): List<Node> {
            if (nodes.size < 3) return nodes
            val result = ArrayList<Node>(nodes.size)
            result.add(nodes[0])
            for (i in 1 until nodes.size - 1) {
                val before = nodes[i - 1]
                val lift = nodes[i]
                val after = nodes[i + 1]
                if (!isStepLift(before, lift, after) || !supported.test(before)
                    || !unsupportedLift.test(lift) || !supported.test(after)) result.add(lift)
            }
            result.add(nodes[nodes.size - 1])
            return result
        }

        private fun isStepLift(before: Node, lift: Node, after: Node): Boolean {
            if (lift.moveType != Node.MoveType.WALK && lift.moveType != Node.MoveType.WALK_DIAGONAL) return false
            val from = before.position
            val up = lift.position
            val to = after.position
            val dx = Math.abs(to.flooredX() - up.flooredX())
            val dz = Math.abs(to.flooredZ() - up.flooredZ())
            return from.flooredX() == up.flooredX() && from.flooredZ() == up.flooredZ()
                && up.flooredY() == from.flooredY() + 1 && to.flooredY() == up.flooredY()
                && Math.max(dx, dz) == 1
        }

        private fun verticalTravel(node: Node, checker: WalkabilityChecker): Boolean {
            if (node.moveType == Node.MoveType.CLIMB || node.moveType == Node.MoveType.SWIM) return true
            val pos = node.position
            val x = pos.flooredX(); val y = pos.flooredY(); val z = pos.flooredZ()
            return checker.isClimbable(x, y, z) || checker.isClimbable(x, y - 1, z)
                || checker.isWater(x, y, z) || checker.isWater(x, y - 1, z)
        }
    }
}
