package dev.aether.modules.pathfinding.provider.impl

import dev.aether.modules.pathfinding.movement.WalkabilityChecker
import dev.aether.modules.pathfinding.pathing.context.EnvironmentContext
import dev.aether.modules.pathfinding.provider.NavigationPoint
import dev.aether.modules.pathfinding.provider.NavigationPointProvider
import dev.aether.modules.pathfinding.util.BlockPosUtil
import dev.aether.modules.pathfinding.wrapper.PathPosition
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction.Axis
import net.minecraft.tags.BlockTags
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.*
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.pathfinder.PathComputationType
import net.minecraft.world.phys.shapes.CollisionContext

class MinecraftNavigationProvider @JvmOverloads constructor(private val checker: WalkabilityChecker? = null) : NavigationPointProvider {
    private val navPointCache: Long2ObjectOpenHashMap<NavigationPoint>? =
        if (checker != null) Long2ObjectOpenHashMap(512) else null

    override fun getNavigationPoint(position: PathPosition, environmentContext: EnvironmentContext?): NavigationPoint {
        val level = checker?.getLevel() ?: mc.level ?: return BLOCKED
        val x = position.flooredX()
        val y = position.flooredY()
        val z = position.flooredZ()
        val cache = navPointCache
        if (cache != null) {
            val key = BlockPosUtil.pack(x, y, z)
            val cached = cache.get(key)
            if (cached != null) return cached
            val result = computeNavigationPoint(level, x, y, z)
            cache.put(key, result)
            return result
        }
        return computeNavigationPoint(level, x, y, z)
    }

    private fun computeNavigationPoint(level: Level, x: Int, y: Int, z: Int): NavigationPoint {
        val feetState: BlockState
        val headState: BlockState
        val belowState: BlockState
        if (checker != null) {
            feetState = checker.getState(x, y, z)
            headState = checker.getState(x, y + 1, z)
            belowState = checker.getState(x, y - 1, z)
        } else {
            val blockPos = BlockPos(x, y, z)
            feetState = level.getBlockState(blockPos)
            headState = level.getBlockState(blockPos.above())
            belowState = level.getBlockState(blockPos.below())
        }
        val canPassFeet = canWalkThrough(feetState)
        val canPassHead = canWalkThrough(headState)
        val hasFloor = if (checker != null) canWalkOnCached(checker, belowState, x, y - 1, z)
            else canWalkOn(level, belowState, BlockPos(x, y - 1, z))
        val floorLevel = if (checker != null) calculateFloorLevelCached(checker, x, y, z)
            else calculateFloorLevel(level, BlockPos(x, y, z))
        val isClimbable = feetState.block is LadderBlock || feetState.block is VineBlock
        val isLiquid = !feetState.fluidState.isEmpty
        val dangerous = isDangerous(feetState) || isDangerous(headState)
        return object : NavigationPoint {
            override fun isTraversable() = canPassFeet && canPassHead && !dangerous
            override fun hasFloor() = hasFloor
            override fun getFloorLevel() = floorLevel
            override fun isClimbable() = isClimbable
            override fun isLiquid() = isLiquid
        }
    }

    companion object {
        private val mc: Minecraft = Minecraft.getInstance()
        private val BLOCKED = object : NavigationPoint {
            override fun isTraversable() = false
            override fun hasFloor() = false
            override fun getFloorLevel() = 0.0
            override fun isClimbable() = false
            override fun isLiquid() = false
        }

        private fun canWalkThrough(state: BlockState): Boolean {
            if (state.isAir) return true
            if (isDangerous(state)) return false
            if (state.`is`(BlockTags.TRAPDOORS) || state.`is`(Blocks.LILY_PAD) || state.`is`(Blocks.BIG_DRIPLEAF)) return true
            if (state.`is`(Blocks.POWDER_SNOW) || state.`is`(Blocks.CACTUS) || state.`is`(Blocks.SWEET_BERRY_BUSH) ||
                state.`is`(Blocks.HONEY_BLOCK) || state.`is`(Blocks.COCOA) || state.`is`(Blocks.WITHER_ROSE) ||
                state.`is`(Blocks.POINTED_DRIPSTONE)) return false
            val block = state.block
            if (block is DoorBlock) return state.getValue(DoorBlock.OPEN) || block.type().canOpenByHand()
            if (block is FenceGateBlock) return state.getValue(FenceGateBlock.OPEN)
            if (block is BaseRailBlock) return true
            if (state.`is`(BlockTags.FENCES) || state.`is`(BlockTags.WALLS)) return false
            return state.isPathfindable(PathComputationType.LAND) || state.fluidState.`is`(FluidTags.WATER)
        }

        private fun canWalkOn(level: Level, state: BlockState, pos: BlockPos): Boolean {
            val block = state.block
            return (state.isCollisionShapeFullBlock(level, pos) && block != Blocks.MAGMA_BLOCK &&
                block != Blocks.BUBBLE_COLUMN && block != Blocks.HONEY_BLOCK) || isSpecialWalkable(block)
        }

        private fun canWalkOnCached(checker: WalkabilityChecker, state: BlockState, x: Int, y: Int, z: Int): Boolean {
            val block = state.block
            if (checker.isFullWallBlock(x, y, z) && block != Blocks.MAGMA_BLOCK &&
                block != Blocks.BUBBLE_COLUMN && block != Blocks.HONEY_BLOCK) return true
            return isSpecialWalkable(block)
        }

        private fun isSpecialWalkable(block: Block): Boolean = block is AzaleaBlock || block is LadderBlock ||
            block is VineBlock || block == Blocks.FARMLAND || block == Blocks.DIRT_PATH || block == Blocks.SOUL_SAND ||
            block == Blocks.CHEST || block == Blocks.ENDER_CHEST || block is TransparentBlock || block is StairBlock ||
            block is SlabBlock || block is BaseRailBlock

        private fun calculateFloorLevel(level: Level, pos: BlockPos): Double {
            if (level.getFluidState(pos).`is`(FluidTags.WATER)) return pos.y + 0.5
            val belowPos = pos.below()
            val shape = level.getBlockState(belowPos).getCollisionShape(level, belowPos, CollisionContext.empty())
            if (shape.isEmpty) return belowPos.y.toDouble()
            return belowPos.y + shape.max(Axis.Y)
        }

        private fun calculateFloorLevelCached(checker: WalkabilityChecker, x: Int, y: Int, z: Int): Double {
            val feetState = checker.getState(x, y, z)
            if (!feetState.fluidState.isEmpty && feetState.fluidState.`is`(FluidTags.WATER)) return y + 0.5
            val belowY = y - 1
            val topY = checker.getTopY(x, belowY, z)
            if (topY <= 0.0) return belowY.toDouble()
            return belowY + topY
        }

        private fun isDangerous(state: BlockState): Boolean {
            val block = state.block
            if (block == Blocks.LAVA || block == Blocks.FIRE || block == Blocks.SOUL_FIRE || block == Blocks.MAGMA_BLOCK ||
                block == Blocks.CACTUS || block == Blocks.SWEET_BERRY_BUSH || block == Blocks.WITHER_ROSE) return true
            return state.fluidState.`is`(FluidTags.LAVA)
        }
    }
}
