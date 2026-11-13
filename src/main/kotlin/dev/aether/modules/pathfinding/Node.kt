package dev.aether.modules.pathfinding

import dev.aether.modules.pathfinding.pathing.heuristic.HeuristicContext
import dev.aether.modules.pathfinding.pathing.heuristic.HeuristicWeights
import dev.aether.modules.pathfinding.pathing.heuristic.IHeuristicStrategy
import dev.aether.modules.pathfinding.wrapper.PathPosition

class Node : Comparable<Node> {

    @JvmField
    val position: PathPosition

    @JvmField
    val depth: Int

    @JvmField
    val heuristic: Double

    @JvmField
    var gCost: Double = 0.0

    @JvmField
    var parent: Node? = null

    @JvmField
    var moveType: MoveType = MoveType.WALK

    // keynode vs. intermediate tracking node, for path smoothing
    @JvmField
    var isKeynode: Boolean = false

    @JvmField
    var inOpen: Boolean = false

    @JvmField
    var inClosed: Boolean = false

    // set when gCost is first finalized; NaN until then
    @JvmField
    var cachedFCost: Double = Double.NaN

    enum class MoveType {
        WALK, WALK_DIAGONAL, STEP_UP, JUMP, PARKOUR, FALL, SWIM, CLIMB, FLY, ETHERWARP
    }

    // intermediate tracking nodes need no heuristic
    constructor(position: PathPosition) {
        this.position = position
        this.depth = 0
        this.heuristic = 0.0
    }

    constructor(
        position: PathPosition,
        start: PathPosition,
        target: PathPosition,
        heuristicWeights: HeuristicWeights,
        heuristicStrategy: IHeuristicStrategy,
        depth: Int
    ) {
        this.position = position
        this.depth = depth
        this.heuristic = heuristicStrategy.calculate(HeuristicContext(position, start, target, heuristicWeights))
    }

    fun fCost(): Double = gCost + heuristic

    fun isTarget(target: PathPosition): Boolean = position == target

    override fun equals(other: Any?): Boolean {
        if (other !is Node) return false
        return position == other.position
    }

    override fun hashCode(): Int = position.hashCode()

    override fun compareTo(other: Node): Int {
        val fc = java.lang.Double.compare(fCost(), other.fCost())
        if (fc != 0) return fc
        val hc = java.lang.Double.compare(heuristic, other.heuristic)
        if (hc != 0) return hc
        return depth.compareTo(other.depth)
    }
}
