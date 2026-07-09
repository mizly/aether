package dev.aether.modules.pathfinding;

import dev.aether.modules.pathfinding.pathing.heuristic.HeuristicContext;
import dev.aether.modules.pathfinding.pathing.heuristic.HeuristicWeights;
import dev.aether.modules.pathfinding.pathing.heuristic.IHeuristicStrategy;
import dev.aether.modules.pathfinding.wrapper.PathPosition;

public final class Node implements Comparable<Node> {

    public final PathPosition position;
    public final int          depth;
    public final double       heuristic;

    public double   gCost       = 0.0;
    public Node     parent      = null;
    /** Movement type used by PathVisualizer for coloring. */
    public MoveType moveType    = MoveType.WALK;
    /** Whether this node is a path-smooth keynode vs. an intermediate tracking node. */
    public boolean  isKeynode   = false;
    /** True while this node is in the open set (heap). */
    public boolean  inOpen      = false;
    /** True once this node has been expanded (moved to closed). */
    public boolean  inClosed    = false;
    /** Cached f-cost (g + h) set when gCost is first finalized; NaN until then. */
    public double   cachedFCost = Double.NaN;

    public enum MoveType {
        WALK, WALK_DIAGONAL, STEP_UP, JUMP, PARKOUR, FALL, SWIM, CLIMB, FLY, ETHERWARP
    }

    /** Constructor for intermediate tracking nodes (no heuristic needed). */
    public Node(PathPosition position) {
        this.position  = position;
        this.depth     = 0;
        this.heuristic = 0.0;
    }

    public Node(PathPosition position, PathPosition start, PathPosition target,
                HeuristicWeights heuristicWeights, IHeuristicStrategy heuristicStrategy,
                int depth) {
        this.position  = position;
        this.depth     = depth;
        this.heuristic = heuristicStrategy.calculate(
                new HeuristicContext(position, start, target, heuristicWeights));
    }

    public double fCost() { return gCost + heuristic; }

    public boolean isTarget(PathPosition target) { return position.equals(target); }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Node n)) return false;
        return position.equals(n.position);
    }

    @Override
    public int hashCode() { return position.hashCode(); }

    @Override
    public int compareTo(Node other) {
        int fc = Double.compare(fCost(), other.fCost());
        if (fc != 0) return fc;
        int hc = Double.compare(heuristic, other.heuristic);
        if (hc != 0) return hc;
        return Integer.compare(depth, other.depth);
    }
}
