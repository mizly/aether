package dev.aether.modules.pathfinding.pathing;

import dev.aether.modules.pathfinding.wrapper.PathVector;
import java.util.ArrayList;
import java.util.List;

public final class NeighborStrategies {
    private NeighborStrategies() {}

    private static final List<PathVector> VERTICAL_AND_HORIZONTAL_OFFSETS = List.of(
        new PathVector( 1, 0,  0),
        new PathVector(-1, 0,  0),
        new PathVector( 0, 0,  1),
        new PathVector( 0, 0, -1),
        new PathVector( 0, 1,  0),
        new PathVector( 0,-1,  0)
    );

    private static final List<PathVector> HORIZONTAL_DIAGONAL_AND_VERTICAL_OFFSETS = List.of(
        new PathVector( 1, 0,  0),
        new PathVector(-1, 0,  0),
        new PathVector( 0, 0,  1),
        new PathVector( 0, 0, -1),
        new PathVector( 0, 1,  0),
        new PathVector( 0,-1,  0),
        new PathVector( 1, 0,  1),
        new PathVector( 1, 0, -1),
        new PathVector(-1, 0,  1),
        new PathVector(-1, 0, -1)
    );

    public static final INeighborStrategy VERTICAL_AND_HORIZONTAL =
        () -> VERTICAL_AND_HORIZONTAL_OFFSETS;

    public static final INeighborStrategy HORIZONTAL_DIAGONAL_AND_VERTICAL =
        () -> HORIZONTAL_DIAGONAL_AND_VERTICAL_OFFSETS;

    public static INeighborStrategy horizontalDiagonalAndVertical(int maxJumpHeight) {
        int cappedJumpHeight = Math.max(1, maxJumpHeight);
        if (cappedJumpHeight == 1) {
            return HORIZONTAL_DIAGONAL_AND_VERTICAL;
        }

        List<PathVector> offsets = new ArrayList<>(HORIZONTAL_DIAGONAL_AND_VERTICAL_OFFSETS);
        for (int height = 2; height <= cappedJumpHeight; height++) {
            offsets.add(new PathVector(0, height, 0));
        }

        List<PathVector> immutableOffsets = List.copyOf(offsets);
        return () -> immutableOffsets;
    }
}
