package dev.aether.modules.pathfinding.etherwarp

import dev.aether.modules.pathfinding.movement.WalkabilityChecker
import dev.aether.modules.pathfinding.wrapper.PathPosition
import dev.aether.util.TablistUtils
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.VoxelShape
import java.util.ArrayList
import java.util.function.BiFunction

class EtherwarpHelper private constructor() {
    companion object {
        const val MAX_ETHERWARP_DISTANCE = 60.0
        const val MAX_ETHERWARP_DISTANCE_SQ = MAX_ETHERWARP_DISTANCE * MAX_ETHERWARP_DISTANCE
        const val MODERN_SNEAKING_EYE_HEIGHT = 1.27
        const val LEGACY_SNEAKING_EYE_HEIGHT = 1.54
        const val MODERN_SNEAKING_HEIGHT = 1.8
        const val LEGACY_SNEAKING_HEIGHT = 2.0
        private const val PLAYER_COLLISION_MIN = 0.2
        private const val PLAYER_COLLISION_MAX = 0.8
        private val CENTER_POINT_OFFSETS = arrayOf(doubleArrayOf(0.50, 0.50, 0.50), doubleArrayOf(0.50, 0.98, 0.50), doubleArrayOf(0.50, 0.02, 0.50), doubleArrayOf(0.05, 0.50, 0.50), doubleArrayOf(0.95, 0.50, 0.50), doubleArrayOf(0.50, 0.50, 0.05), doubleArrayOf(0.50, 0.50, 0.95))
        private val TARGET_POINT_OFFSETS: kotlin.collections.List<Vec3> = createTargetPointOffsets()

        @JvmStatic fun resolveTargetFeet(checker: WalkabilityChecker?, x: Int, y: Int, z: Int): PathPosition? {
            if (checker == null) return null
            val topLanding = PathPosition(x.toDouble(), (y + 1).toDouble(), z.toDouble())
            if (checker.hasWalkableTop(x, y, z) && isValidLandingFeet(checker, topLanding)) return topLanding
            val directLanding = PathPosition(x.toDouble(), y.toDouble(), z.toDouble())
            if (isValidLandingFeet(checker, directLanding)) return directLanding
            return null
        }

        @JvmStatic fun isValidLandingFeet(checker: WalkabilityChecker?, feet: PathPosition?): Boolean {
            if (checker == null || feet == null) return false
            val x = feet.flooredX(); val y = feet.flooredY(); val z = feet.flooredZ()
            val height = getSneakingHeight(Minecraft.getInstance())
            return checker.hasWalkableTop(x, y - 1, z) && hasCollisionFreeSpace(checker, x, y, z, height) && hasSafeSpace(checker, x, y, z, height)
        }

        @JvmStatic fun getTargetBlock(feet: PathPosition): BlockPos = BlockPos(feet.flooredX(), feet.flooredY() - 1, feet.flooredZ())
        @JvmStatic fun getCenteredFeet(feet: PathPosition): Vec3 = Vec3(feet.flooredX() + 0.5, feet.flooredY().toDouble(), feet.flooredZ() + 0.5)
        @JvmStatic fun getEyePosition(mc: Minecraft, feet: PathPosition?): Vec3? = if (feet == null) null else getEyePosition(mc, getCenteredFeet(feet))
        @JvmStatic fun getEyePosition(mc: Minecraft, feetPos: Vec3?): Vec3? = feetPos?.add(0.0, getSneakingEyeHeight(mc), 0.0)
        @JvmStatic fun canEtherwarp(mc: Minecraft, checker: WalkabilityChecker, fromFeet: PathPosition?, toFeet: PathPosition?): Boolean = findVisibleTargetPoint(mc, checker, fromFeet, toFeet) != null

        @JvmStatic fun findVisibleTargetPoint(mc: Minecraft, checker: WalkabilityChecker, fromFeet: PathPosition?, toFeet: PathPosition?): Vec3? {
            if (fromFeet == null) return null
            return findVisibleTargetPoint(mc, checker, getEyePosition(mc, fromFeet), toFeet)
        }

        @JvmStatic fun findVisibleTargetPoint(mc: Minecraft, checker: WalkabilityChecker, eyePos: Vec3?, toFeet: PathPosition?): Vec3? {
            if (mc.level == null || eyePos == null || toFeet == null || !isValidLandingFeet(checker, toFeet)) return null
            return findVisibleTargetPoint(eyePos, getTargetBlock(toFeet), BiFunction { from, to -> clip(mc, from, to) })
        }

        internal fun findVisibleTargetPoint(eyePos: Vec3, targetBlock: BlockPos, raycast: BiFunction<Vec3, Vec3, BlockHitResult>): Vec3? {
            for (offset in TARGET_POINT_OFFSETS) {
                val targetPoint = Vec3(targetBlock.x + offset.x, targetBlock.y + offset.y, targetBlock.z + offset.z)
                if (eyePos.distanceToSqr(targetPoint) > MAX_ETHERWARP_DISTANCE_SQ + 1.0) continue
                val hit = raycast.apply(eyePos, targetPoint)
                if (hit.type == HitResult.Type.BLOCK && hit.blockPos == targetBlock) return targetPoint
            }
            return null
        }

        @JvmStatic fun isLookingAtTarget(mc: Minecraft?, eyePos: Vec3, feet: PathPosition): Boolean {
            val player = mc?.player ?: return false
            if (mc.level == null) return false
            return isLookingAtTarget(eyePos, player.getViewVector(1.0f), getTargetBlock(feet), BiFunction { from, to -> clip(mc, from, to) })
        }

        internal fun isLookingAtTarget(eyePos: Vec3, direction: Vec3, targetBlock: BlockPos, raycast: BiFunction<Vec3, Vec3, BlockHitResult>): Boolean {
            val hit = raycast.apply(eyePos, eyePos.add(direction.scale(MAX_ETHERWARP_DISTANCE)))
            return hit.type == HitResult.Type.BLOCK && hit.blockPos == targetBlock
        }

        private fun clip(mc: Minecraft, from: Vec3, to: Vec3): BlockHitResult {
            val level = mc.level
            val player = mc.player
            if (level == null || player == null) return BlockHitResult.miss(to, net.minecraft.core.Direction.UP, BlockPos.containing(to))
            return level.clip(ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
        }

        private fun createTargetPointOffsets(): kotlin.collections.List<Vec3> {
            val offsets = ArrayList<Vec3>()
            for (offset in CENTER_POINT_OFFSETS) offsets.add(Vec3(offset[0], offset[1], offset[2]))
            val coordinates = doubleArrayOf(0.5, 0.05, 0.95)
            for (x in coordinates) for (y in coordinates) for (z in coordinates) {
                val edges = (if (x == 0.5) 0 else 1) + (if (y == 0.5) 0 else 1) + (if (z == 0.5) 0 else 1)
                if (edges >= 2) offsets.add(Vec3(x, y, z))
            }
            return offsets.toList()
        }

        private fun hasCollisionFreeSpace(checker: WalkabilityChecker, x: Int, feetY: Int, z: Int, height: Double): Boolean {
            var remainingHeight = height; var currentY = feetY
            while (remainingHeight > 0.0) {
                val occupiedHeight = Math.min(1.0, remainingHeight)
                if (intersectsPlayerSpace(checker, x, currentY, z, occupiedHeight)) return false
                remainingHeight -= occupiedHeight; currentY++
            }
            return true
        }

        private fun hasSafeSpace(checker: WalkabilityChecker, x: Int, feetY: Int, z: Int, height: Double): Boolean {
            var remainingHeight = height; var currentY = feetY
            while (remainingHeight > 0.0) {
                if (checker.isDangerous(x, currentY, z)) return false
                remainingHeight -= Math.min(1.0, remainingHeight); currentY++
            }
            return true
        }

        private fun intersectsPlayerSpace(checker: WalkabilityChecker, x: Int, y: Int, z: Int, occupiedHeight: Double): Boolean {
            if (occupiedHeight <= 0.0) return false
            val shape: VoxelShape = checker.getState(x, y, z).getCollisionShape(checker.getLevel(), BlockPos(x, y, z))
            if (shape.isEmpty) return false
            for (box: AABB in shape.toAabbs()) if (box.maxX > PLAYER_COLLISION_MIN && box.minX < PLAYER_COLLISION_MAX && box.maxZ > PLAYER_COLLISION_MIN && box.minZ < PLAYER_COLLISION_MAX && box.maxY > 0.0 && box.minY < occupiedHeight) return true
            return false
        }

        private fun getSneakingEyeHeight(mc: Minecraft): Double = if (usesModernSneakingEyeHeight(mc)) MODERN_SNEAKING_EYE_HEIGHT else LEGACY_SNEAKING_EYE_HEIGHT
        private fun getSneakingHeight(mc: Minecraft): Double = if (usesModernSneakingEyeHeight(mc)) MODERN_SNEAKING_HEIGHT else LEGACY_SNEAKING_HEIGHT

        private fun usesModernSneakingEyeHeight(mc: Minecraft?): Boolean {
            val areaLine = if (mc == null) null else TablistUtils.findLine(mc, "Area:")
            if (areaLine == null) return false
            var areaName = areaLine
            val separatorIndex = areaLine.indexOf(':')
            if (separatorIndex >= 0 && separatorIndex + 1 < areaLine.length) areaName = areaLine.substring(separatorIndex + 1).trim()
            return areaName.equals("Galatea", true) || areaName.equals("The Park", true) || areaName.equals("Hub", true) || areaName.equals("Spider Den", true) || areaName.equals("Spider's Den", true)
        }
    }
}
