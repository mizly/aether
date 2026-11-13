package dev.aether.modules.pathfinding.execution

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.ArrayList
import java.util.Comparator

class WalkingObstacleProbe private constructor() {
    class Result(private val obstacleAhead: Boolean, private val jumpRequired: Boolean, private val clearance: Double, private val obstacleHeight: Double, private val headroomClear: Boolean) {
        fun obstacleAhead() = obstacleAhead
        fun jumpRequired() = jumpRequired
        fun clearance() = clearance
        fun obstacleHeight() = obstacleHeight
        fun headroomClear() = headroomClear
    }
    private class Sweep(val bounds: AABB, val enter: Double, val exit: Double)
    interface CollisionSpace { fun collisions(bounds: AABB): Iterable<AABB>; fun clear(bounds: AABB): Boolean }

    companion object {
        private const val EPSILON = 0.02
        private const val MAX_LOOKAHEAD = 4.0
        private val CLEAR = Result(false, false, Double.POSITIVE_INFINITY, 0.0, true)

        @JvmStatic
        fun probe(client: Minecraft, movementDirection: Vec3, baseLookahead: Double, predictionTicks: Double, maxJumpHeight: Double): Result {
            val player = client.player
            if (player == null || client.level == null) return CLEAR
            val jumpHeight = Math.min(maxJumpHeight + 0.125, jumpHeight(client) - EPSILON)
            return probe(collisionSpace(client), player.boundingBox, movementDirection, player.deltaMovement, player.maxUpStep().toDouble(), jumpHeight, baseLookahead, predictionTicks)
        }
        @JvmStatic
        fun hasJumpHeadroom(client: Minecraft): Boolean {
            val player = client.player ?: return false
            if (client.level == null) return false
            return hasHeadroom(collisionSpace(client), player.boundingBox, Math.min(1.0, jumpHeight(client)))
        }

        @JvmStatic
        fun probe(space: CollisionSpace, bounds: AABB, movementDirection: Vec3, velocity: Vec3, stepHeight: Double, maxJumpHeight: Double, baseLookahead: Double, predictionTicks: Double): Result {
            val length = movementDirection.horizontalDistance()
            if (length < 1.0e-6) return CLEAR
            val direction = Vec3(movementDirection.x / length, 0.0, movementDirection.z / length)
            val closingSpeed = Math.max(0.0, velocity.x * direction.x + velocity.z * direction.z)
            val reach = Math.min(MAX_LOOKAHEAD, Math.max(0.05, baseLookahead) + closingSpeed * Math.max(0.0, predictionTicks))
            val travel = direction.scale(reach)
            val collisions = ArrayList<Sweep>()
            val search = bounds.expandTowards(travel).expandTowards(0.0, reach + maxJumpHeight, 0.0).inflate(EPSILON)
            for (obstacle in space.collisions(search)) sweep(bounds, obstacle, direction, reach)?.let(collisions::add)
            collisions.sortWith(Comparator.comparingDouble { it.enter })
            var nearestDistance = Double.POSITIVE_INFINITY
            var obstacleHeight = 0.0
            var walkingY = bounds.minY
            var supportedUntil = reach
            for (collision in collisions) {
                val distance = collision.enter
                if (walkingY > bounds.minY + EPSILON && distance > supportedUntil + EPSILON) { walkingY = bounds.minY; supportedUntil = reach }
                var top = walkingY
                for (candidate in collisions) if (candidate.enter <= distance + EPSILON && candidate.exit > distance + 1.0e-6 && candidate.bounds.minY < walkingY + bounds.ysize - EPSILON) top = Math.max(top, candidate.bounds.maxY)
                val step = top - walkingY
                if (step > stepHeight + EPSILON) { nearestDistance = Math.max(0.0, distance); obstacleHeight = top - bounds.minY; break }
                if (step > EPSILON) {
                    val atStep = bounds.move(direction.scale(distance + 2 * EPSILON)).move(0.0, walkingY - bounds.minY, 0.0)
                    if (!hasHeadroom(space, atStep, step) || !space.clear(atStep.deflate(EPSILON).move(0.0, step, 0.0))) return Result(true, false, distance, top - bounds.minY, false)
                    walkingY = top; supportedUntil = distance
                }
                for (candidate in collisions) if (candidate.enter <= supportedUntil + EPSILON && Math.abs(candidate.bounds.maxY - walkingY) <= EPSILON) supportedUntil = Math.max(supportedUntil, candidate.exit)
            }
            if (!java.lang.Double.isFinite(nearestDistance)) return CLEAR
            val heightReachable = obstacleHeight <= maxJumpHeight
            var headroomClear = heightReachable && hasHeadroom(space, bounds, obstacleHeight + EPSILON)
            if (headroomClear) {
                val elevated = bounds.deflate(EPSILON).move(0.0, obstacleHeight + EPSILON, 0.0)
                val landingDistance = nearestDistance + 0.15
                val samples = Math.max(1, Math.ceil(landingDistance / 0.2).toInt())
                for (sample in 1..samples) if (!space.clear(elevated.move(direction.scale(landingDistance * sample / samples)))) { headroomClear = false; break }
            }
            return Result(true, heightReachable && headroomClear, nearestDistance, obstacleHeight, headroomClear)
        }

        private fun sweep(bounds: AABB, obstacle: AABB, direction: Vec3, reach: Double): Sweep? {
            var enter = 0.0; var exit = reach
            val minimum = doubleArrayOf(obstacle.minX - bounds.maxX, obstacle.minZ - bounds.maxZ)
            val maximum = doubleArrayOf(obstacle.maxX - bounds.minX, obstacle.maxZ - bounds.minZ)
            val movement = doubleArrayOf(direction.x, direction.z)
            for (axis in movement.indices) {
                val speed = movement[axis]
                if (Math.abs(speed) < 1.0e-6) { if (minimum[axis] >= 0 || maximum[axis] <= 0) return null }
                else {
                    val first = minimum[axis] / speed; val last = maximum[axis] / speed
                    enter = Math.max(enter, Math.min(first, last)); exit = Math.min(exit, Math.max(first, last))
                    if (exit <= enter + 1.0e-6) return null
                }
            }
            return Sweep(obstacle, enter, exit)
        }

        @JvmStatic
        fun jumpHeight(initialVelocity: Double, gravity: Double): Double {
            if (initialVelocity <= 0.0 || gravity <= 0.0) return 0.0
            var height = 0.0; var velocity = initialVelocity; var tick = 0
            while (tick < 100 && velocity > 0.0) { height += velocity; velocity = (velocity - gravity) * 0.98; tick++ }
            return height
        }
        private fun jumpHeight(client: Minecraft): Double {
            val player = client.player ?: return 0.0
            val level = client.level ?: return 0.0
            var jumpFactor = level.getBlockState(player.blockPosition()).block.jumpFactor
            if (jumpFactor == 1.0f) {
                val support = BlockPos.containing(player.x, player.y - 0.500001, player.z)
                jumpFactor = level.getBlockState(support).block.jumpFactor
            }
            val initialVelocity = player.getAttributeValue(Attributes.JUMP_STRENGTH) * jumpFactor + player.jumpBoostPower
            return jumpHeight(initialVelocity, player.gravity)
        }
        private fun hasHeadroom(space: CollisionSpace, bounds: AABB, rise: Double) = rise > 0.0 && space.clear(AABB(bounds.minX + EPSILON, bounds.maxY, bounds.minZ + EPSILON, bounds.maxX - EPSILON, bounds.maxY + rise, bounds.maxZ - EPSILON))
        private fun collisionSpace(client: Minecraft): CollisionSpace = object : CollisionSpace {
            override fun collisions(bounds: AABB): Iterable<AABB> {
                val boxes = ArrayList<AABB>()
                val level = client.level
                val player = client.player
                if (level != null && player != null) for (shape in level.getBlockCollisions(player, bounds)) boxes.addAll(shape.toAabbs())
                return boxes
            }
            override fun clear(bounds: AABB): Boolean {
                val level = client.level ?: return false
                val player = client.player ?: return false
                return level.noBlockCollision(player, bounds)
            }
        }
    }
}
