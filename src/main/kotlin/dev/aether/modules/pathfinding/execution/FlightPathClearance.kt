package dev.aether.modules.pathfinding.execution

import net.minecraft.client.Minecraft
import net.minecraft.core.Direction
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.Shapes
import java.util.ArrayList
import java.util.function.BiPredicate
import java.util.function.Function

class FlightPathClearance private constructor() {
    companion object {
        private const val EPSILON = 1.0e-6
        private const val SEGMENT_LENGTH = 4.0
        @JvmStatic fun clearCorner(from: Vec3, to: Vec3, clear: BiPredicate<Vec3, Vec3>): Vec3? {
            val levelled = Vec3(from.x, to.y, from.z)
            if (clear.test(from, levelled) && clear.test(levelled, to)) return levelled
            val across = Vec3(to.x, from.y, to.z)
            return if (clear.test(from, across) && clear.test(across, to)) across else null
        }
        @JvmStatic fun isClear(client: Minecraft, from: Vec3, to: Vec3) = isClear(client, from, to, 0.0)
        @JvmStatic fun isClear(client: Minecraft, from: Vec3, to: Vec3, margin: Double) = isClear(client, from, to, margin, 0.0)
        @JvmStatic fun isClear(client: Minecraft, from: Vec3, to: Vec3, margin: Double, verticalMargin: Double): Boolean {
            val player = client.player
            if (player == null || client.level == null) return false
            val bounds = player.boundingBox.inflate(margin, verticalMargin, margin).move(from.subtract(player.position()))
            return isClear(bounds, to.subtract(from), Function { search -> collisions(client, search) })
        }
        @JvmStatic fun canCoastHorizontally(client: Minecraft): Boolean {
            val player = client.player
            if (player == null || client.level == null) return false
            return canCoastHorizontally(client, player.deltaMovement)
        }
        @JvmStatic fun canCoastHorizontally(client: Minecraft, velocity: Vec3): Boolean {
            val player = client.player
            if (player == null || client.level == null) return false
            return canCoastHorizontally(player.boundingBox, velocity, Function { search -> collisions(client, search) })
        }
        @JvmStatic fun canCoastHorizontally(boundsStart: AABB, velocityStart: Vec3, collisions: Function<AABB, Iterable<AABB>>): Boolean {
            var bounds = boundsStart; var velocity = velocityStart
            for (tick in 0 until 12) {
                val obstacles = collisions.apply(bounds.expandTowards(velocity))
                var vertical = velocity.y
                if (Math.abs(vertical) > EPSILON) for (obstacle in obstacles) vertical = Shapes.create(obstacle).collide(Direction.Axis.Y, bounds, vertical)
                val travel = Vec3(velocity.x, vertical, velocity.z)
                if (!isClear(bounds, travel, Function { obstacles }) || Math.abs(vertical) > EPSILON && !isClear(bounds.move(0.0, vertical, 0.0), Vec3(velocity.x, 0.0, velocity.z), Function { obstacles })) return false
                bounds = bounds.move(travel); velocity = velocity.multiply(0.91, 0.6, 0.91)
            }
            return true
        }
        private fun collisions(client: Minecraft, search: AABB): List<AABB> {
            val obstacles = ArrayList<AABB>()
            val level = client.level
            val player = client.player
            if (level != null && player != null) for (shape in level.getBlockCollisions(player, search)) obstacles.addAll(shape.toAabbs())
            return obstacles
        }
        @JvmStatic fun isClear(bounds: AABB, travel: Vec3, collisions: Function<AABB, Iterable<AABB>>): Boolean {
            val segments = Math.max(1, Math.ceil(travel.length() / SEGMENT_LENGTH).toInt())
            val step = travel.scale(1.0 / segments); val body = bounds.deflate(EPSILON)
            for (segment in 0 until segments) {
                val start = body.move(step.scale(segment.toDouble()))
                for (obstacle in collisions.apply(start.expandTowards(step))) if (intersectsSweep(start, step, obstacle)) return false
            }
            return true
        }
        private fun intersectsSweep(bounds: AABB, travel: Vec3, obstacle: AABB): Boolean {
            var enter = 0.0; var exit = 1.0
            val minimum = doubleArrayOf(obstacle.minX - bounds.maxX, obstacle.minY - bounds.maxY, obstacle.minZ - bounds.maxZ)
            val maximum = doubleArrayOf(obstacle.maxX - bounds.minX, obstacle.maxY - bounds.minY, obstacle.maxZ - bounds.minZ)
            val movement = doubleArrayOf(travel.x, travel.y, travel.z)
            for (axis in movement.indices) {
                if (Math.abs(movement[axis]) < EPSILON) { if (minimum[axis] >= 0.0 || maximum[axis] <= 0.0) return false }
                else {
                    val first = minimum[axis] / movement[axis]; val last = maximum[axis] / movement[axis]
                    enter = Math.max(enter, Math.min(first, last)); exit = Math.min(exit, Math.max(first, last))
                    if (exit <= enter) return false
                }
            }
            return true
        }
    }
}
