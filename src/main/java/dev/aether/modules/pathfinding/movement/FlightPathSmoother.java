package dev.aether.modules.pathfinding.movement;

import dev.aether.modules.pathfinding.Node;
import dev.aether.modules.pathfinding.wrapper.PathPosition;

import java.util.ArrayList;
import java.util.List;

public final class FlightPathSmoother {
    // height is trimmed independently of the horizontal drive, so a climb is flown as a corner that
    // reaches the new height early rather than as an even slope; both legs of it have to be clear
    private static final int MAX_MERGED_CLIMB = 3;

    @FunctionalInterface
    public interface Clearance {
        boolean isClear(PathPosition from, PathPosition to);
    }

    private FlightPathSmoother() {}

    public static List<Node> smooth(List<Node> path, Clearance clear) {
        if (path.isEmpty()) return path;
        List<Node> result = new ArrayList<>();
        result.add(path.getFirst());
        int anchor = 0;
        while (anchor < path.size() - 1) {
            PathPosition from = path.get(anchor).position;
            int lastValid = anchor + 1;
            if (!clear.isClear(from, path.get(lastValid).position)) return List.of();
            for (int candidate = anchor + 2; candidate < path.size(); candidate++) {
                if (!mergeable(from, path.get(candidate).position, clear)) break;
                lastValid = candidate;
            }
            result.add(path.get(lastValid));
            anchor = lastValid;
        }
        return result;
    }

    private static boolean mergeable(PathPosition from, PathPosition to, Clearance clear) {
        boolean level = from.flooredY() == to.flooredY();
        boolean column = from.flooredX() == to.flooredX() && from.flooredZ() == to.flooredZ();
        if (level || column) {
            return clear.isClear(from, to);
        }
        if (Math.abs(to.flooredY() - from.flooredY()) > MAX_MERGED_CLIMB) {
            return false;
        }
        PathPosition corner = new PathPosition(from.flooredX(), to.flooredY(), from.flooredZ());
        return clear.isClear(from, to) && clear.isClear(from, corner) && clear.isClear(corner, to);
    }
}
