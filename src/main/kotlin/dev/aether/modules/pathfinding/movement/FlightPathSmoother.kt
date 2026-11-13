package dev.aether.modules.pathfinding.movement

import dev.aether.modules.pathfinding.Node
import dev.aether.modules.pathfinding.wrapper.PathPosition
import java.util.ArrayList

class FlightPathSmoother private constructor() {
    fun interface Clearance { fun isClear(from: PathPosition, to: PathPosition): Boolean }
    companion object {
        private const val MAX_MERGED_CLIMB=3
        @JvmStatic fun smooth(path: kotlin.collections.List<Node>, clear: Clearance): kotlin.collections.List<Node> {
            if (path.isEmpty()) return path
            val result = ArrayList<Node>()
            result.add(path[0])
            var anchor = 0
            while (anchor < path.size - 1) {
                val from = path[anchor].position
                var last = anchor + 1
                if (!clear.isClear(from, path[last].position)) return emptyList<Node>()
                for (candidate in anchor + 2 until path.size) {
                    if (!mergeable(from, path[candidate].position, clear)) break
                    last = candidate
                }
                result.add(path[last])
                anchor = last
            }
            return result.toList()
        }
        private fun mergeable(from:PathPosition,to:PathPosition,clear:Clearance):Boolean{val level=from.flooredY()==to.flooredY();val column=from.flooredX()==to.flooredX()&&from.flooredZ()==to.flooredZ();if(level||column)return clear.isClear(from,to);if(Math.abs(to.flooredY()-from.flooredY())>MAX_MERGED_CLIMB)return false;val corner=PathPosition(from.flooredX().toDouble(),to.flooredY().toDouble(),from.flooredZ().toDouble());return clear.isClear(from,to)&&clear.isClear(from,corner)&&clear.isClear(corner,to)}
    }
}
