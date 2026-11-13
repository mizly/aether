package dev.aether.modules.pathfinding.pathing.heuristic

import dev.aether.modules.pathfinding.pathing.PathfindingProgress

internal object InternalHeuristicUtils {
    private const val EPSILON: Double = 1e-9

    fun calculatePerpendicularDistanceSq(progress: PathfindingProgress): Double {
        val sx = progress.start.centeredX()
        val sy = progress.start.centeredY()
        val sz = progress.start.centeredZ()
        val cx = progress.current.centeredX()
        val cy = progress.current.centeredY()
        val cz = progress.current.centeredZ()
        val tx = progress.target.centeredX()
        val ty = progress.target.centeredY()
        val tz = progress.target.centeredZ()

        val lineX = tx - sx
        val lineY = ty - sy
        val lineZ = tz - sz
        val lineSq = lineX * lineX + lineY * lineY + lineZ * lineZ

        if (lineSq < EPSILON) {
            val dx = cx - sx
            val dy = cy - sy
            val dz = cz - sz
            return dx * dx + dy * dy + dz * dz
        }

        val toX = cx - sx
        val toY = cy - sy
        val toZ = cz - sz
        val crossX = toY * lineZ - toZ * lineY
        val crossY = toZ * lineX - toX * lineZ
        val crossZ = toX * lineY - toY * lineX
        val crossSq = crossX * crossX + crossY * crossY + crossZ * crossZ

        return crossSq / lineSq
    }

    fun calculatePerpendicularDistance(progress: PathfindingProgress): Double =
        Math.sqrt(calculatePerpendicularDistanceSq(progress))
}
