package dev.aether.modules.pathfinding.pathing

import dev.aether.modules.pathfinding.wrapper.PathVector
import java.util.ArrayList

class NeighborStrategies private constructor() {
    companion object {
        private val VERTICAL_AND_HORIZONTAL_OFFSETS: List<PathVector> = listOf(
            PathVector(1.0, 0.0, 0.0),
            PathVector(-1.0, 0.0, 0.0),
            PathVector(0.0, 0.0, 1.0),
            PathVector(0.0, 0.0, -1.0),
            PathVector(0.0, 1.0, 0.0),
            PathVector(0.0, -1.0, 0.0)
        )

        private val HORIZONTAL_DIAGONAL_AND_VERTICAL_OFFSETS: List<PathVector> = listOf(
            PathVector(1.0, 0.0, 0.0),
            PathVector(-1.0, 0.0, 0.0),
            PathVector(0.0, 0.0, 1.0),
            PathVector(0.0, 0.0, -1.0),
            PathVector(0.0, 1.0, 0.0),
            PathVector(0.0, -1.0, 0.0),
            PathVector(1.0, 0.0, 1.0),
            PathVector(1.0, 0.0, -1.0),
            PathVector(-1.0, 0.0, 1.0),
            PathVector(-1.0, 0.0, -1.0)
        )

        @JvmField
        val VERTICAL_AND_HORIZONTAL: INeighborStrategy = INeighborStrategy { VERTICAL_AND_HORIZONTAL_OFFSETS }

        @JvmField
        val HORIZONTAL_DIAGONAL_AND_VERTICAL: INeighborStrategy = INeighborStrategy { HORIZONTAL_DIAGONAL_AND_VERTICAL_OFFSETS }

        @JvmStatic
        fun horizontalDiagonalAndVertical(maxJumpHeight: Int): INeighborStrategy {
            val cappedJumpHeight = maxOf(1, maxJumpHeight)
            if (cappedJumpHeight == 1) {
                return HORIZONTAL_DIAGONAL_AND_VERTICAL
            }

            val offsets = ArrayList(HORIZONTAL_DIAGONAL_AND_VERTICAL_OFFSETS)
            for (height in 2..cappedJumpHeight) {
                offsets.add(PathVector(0.0, height.toDouble(), 0.0))
            }

            val immutableOffsets = java.util.List.copyOf(offsets)
            return INeighborStrategy { immutableOffsets }
        }
    }
}
