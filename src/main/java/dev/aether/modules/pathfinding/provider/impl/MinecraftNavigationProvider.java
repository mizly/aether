package dev.aether.modules.pathfinding.provider.impl;

import dev.aether.modules.pathfinding.movement.WalkabilityChecker;
import dev.aether.modules.pathfinding.pathing.context.EnvironmentContext;
import dev.aether.modules.pathfinding.provider.NavigationPoint;
import dev.aether.modules.pathfinding.provider.NavigationPointProvider;
import dev.aether.modules.pathfinding.util.BlockPosUtil;
import dev.aether.modules.pathfinding.wrapper.PathPosition;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;

public final class MinecraftNavigationProvider implements NavigationPointProvider {

    private static final Minecraft mc = Minecraft.getInstance();

    private static final NavigationPoint BLOCKED = new NavigationPoint() {
        @Override public boolean isTraversable() { return false; }
        @Override public boolean hasFloor()      { return false; }
        @Override public double  getFloorLevel() { return 0.0;  }
        @Override public boolean isClimbable()   { return false; }
        @Override public boolean isLiquid()      { return false; }
    };

    private final WalkabilityChecker checker;
    private final Long2ObjectOpenHashMap<NavigationPoint> navPointCache;

    public MinecraftNavigationProvider() {
        this(null);
    }

    public MinecraftNavigationProvider(WalkabilityChecker checker) {
        this.checker = checker;
        // Cache sized for typical Skyblock paths (<100 blocks). This provider is
        // recreated per pathfinding search so stale entries are never an issue.
        this.navPointCache = checker != null ? new Long2ObjectOpenHashMap<>(512) : null;
    }

    @Override
    public NavigationPoint getNavigationPoint(PathPosition position, EnvironmentContext ctx) {
        Level level = checker != null ? checker.getLevel() : mc.level;
        if (level == null) return BLOCKED;

        int x = position.flooredX();
        int y = position.flooredY();
        int z = position.flooredZ();

        // Check cache first
        if (navPointCache != null) {
            long key = BlockPosUtil.pack(x, y, z);
            NavigationPoint cached = navPointCache.get(key);
            if (cached != null) return cached;

            NavigationPoint result = computeNavigationPoint(level, x, y, z);
            navPointCache.put(key, result);
            return result;
        }

        return computeNavigationPoint(level, x, y, z);
    }

    private NavigationPoint computeNavigationPoint(Level level, int x, int y, int z) {
        BlockState feetState;
        BlockState headState;
        BlockState belowState;

        if (checker != null) {
            feetState  = checker.getState(x, y, z);
            headState  = checker.getState(x, y + 1, z);
            belowState = checker.getState(x, y - 1, z);
        } else {
            BlockPos blockPos = new BlockPos(x, y, z);
            feetState  = level.getBlockState(blockPos);
            headState  = level.getBlockState(blockPos.above());
            belowState = level.getBlockState(blockPos.below());
        }

        boolean canPassFeet   = canWalkThrough(feetState);
        boolean canPassHead   = canWalkThrough(headState);
        boolean hasFloor      = checker != null
                ? canWalkOnCached(checker, belowState, x, y - 1, z)
                : canWalkOn(level, belowState, new BlockPos(x, y - 1, z));
        double  floorLevel    = checker != null
                ? calculateFloorLevelCached(checker, x, y, z)
                : calculateFloorLevel(level, new BlockPos(x, y, z));
        boolean isClimbable   = feetState.getBlock() instanceof LadderBlock
                             || feetState.getBlock() instanceof VineBlock;
        boolean isLiquid      = !feetState.getFluidState().isEmpty();

        // Block dangerous positions
        boolean isDangerous   = isDangerous(feetState) || isDangerous(headState);

        final boolean traversable = canPassFeet && canPassHead && !isDangerous;
        final boolean floor       = hasFloor;
        final double  fl          = floorLevel;
        final boolean climb       = isClimbable;
        final boolean liquid      = isLiquid;

        return new NavigationPoint() {
            @Override public boolean isTraversable() { return traversable; }
            @Override public boolean hasFloor()      { return floor;       }
            @Override public double  getFloorLevel() { return fl;          }
            @Override public boolean isClimbable()   { return climb;       }
            @Override public boolean isLiquid()      { return liquid;      }
        };
    }

    private static boolean canWalkThrough(BlockState state) {
        if (state.isAir()) return true;

        // Dangerous but physically walkable (treat as blocked)
        if (isDangerous(state)) return false;

        if (state.is(BlockTags.TRAPDOORS)
                || state.is(Blocks.LILY_PAD)
                || state.is(Blocks.BIG_DRIPLEAF)) {
            return true;
        }

        // Mark hazardous blocks as not traversable
        if (state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.HONEY_BLOCK)
                || state.is(Blocks.COCOA)
                || state.is(Blocks.WITHER_ROSE)
                || state.is(Blocks.POINTED_DRIPSTONE)) {
            return false;
        }

        Block block = state.getBlock();

        if (block instanceof DoorBlock door) {
            return state.getValue(DoorBlock.OPEN) || door.type().canOpenByHand();
        }
        if (block instanceof FenceGateBlock) {
            return state.getValue(FenceGateBlock.OPEN);
        }
        if (block instanceof BaseRailBlock) return true;

        if (state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS)) return false;

        return state.isPathfindable(PathComputationType.LAND)
                || state.getFluidState().is(FluidTags.WATER);
    }

    private static boolean canWalkOn(Level level, BlockState state, BlockPos pos) {
        Block block = state.getBlock();
        if (state.isCollisionShapeFullBlock(level, pos)
                && block != Blocks.MAGMA_BLOCK
                && block != Blocks.BUBBLE_COLUMN
                && block != Blocks.HONEY_BLOCK) {
            return true;
        }
        return isSpecialWalkable(block);
    }

    private static boolean canWalkOnCached(WalkabilityChecker checker, BlockState state, int x, int y, int z) {
        Block block = state.getBlock();
        if (checker.isFullWallBlock(x, y, z)
                && block != Blocks.MAGMA_BLOCK
                && block != Blocks.BUBBLE_COLUMN
                && block != Blocks.HONEY_BLOCK) {
            return true;
        }
        // For non-full-block but still walkable: check canOcclude + collisionShapeFullBlock via checker
        if (state.canOcclude() && block != Blocks.MAGMA_BLOCK
                && block != Blocks.BUBBLE_COLUMN
                && block != Blocks.HONEY_BLOCK) {
            // isFullWallBlock already checks isCollisionShapeFullBlock, so if it returned false
            // the block is not a full block. But canWalkOn originally checks isCollisionShapeFullBlock
            // which isFullWallBlock already wraps. So we just need to check special walkables.
        }
        return isSpecialWalkable(block);
    }

    private static boolean isSpecialWalkable(Block block) {
        return block instanceof AzaleaBlock
            || block instanceof LadderBlock
            || block instanceof VineBlock
            || block == Blocks.FARMLAND
            || block == Blocks.DIRT_PATH
            || block == Blocks.SOUL_SAND
            || block == Blocks.CHEST
            || block == Blocks.ENDER_CHEST
            || block == Blocks.GLASS
            || block instanceof StairBlock
            || block instanceof SlabBlock
            || block instanceof BaseRailBlock;
    }

    private static double calculateFloorLevel(Level level, BlockPos pos) {
        if (level.getFluidState(pos).is(FluidTags.WATER)) {
            return pos.getY() + 0.5;
        }
        BlockPos    belowPos  = pos.below();
        BlockState  belowState = level.getBlockState(belowPos);
        var shape = belowState.getCollisionShape(level, belowPos, CollisionContext.empty());
        if (shape.isEmpty()) return belowPos.getY();
        return belowPos.getY() + shape.max(Axis.Y);
    }

    private static double calculateFloorLevelCached(WalkabilityChecker checker, int x, int y, int z) {
        BlockState feetState = checker.getState(x, y, z);
        if (!feetState.getFluidState().isEmpty()
                && feetState.getFluidState().is(FluidTags.WATER)) {
            return y + 0.5;
        }
        int belowY = y - 1;
        double topY = checker.getTopY(x, belowY, z);
        if (topY <= 0.0) return belowY;
        return belowY + topY;
    }

    /** Detect blocks that deal damage on contact. */
    private static boolean isDangerous(BlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.LAVA
                || block == Blocks.FIRE
                || block == Blocks.SOUL_FIRE
                || block == Blocks.MAGMA_BLOCK
                || block == Blocks.CACTUS
                || block == Blocks.SWEET_BERRY_BUSH
                || block == Blocks.WITHER_ROSE) {
            return true;
        }
        // Lava fluid state
        return state.getFluidState().is(FluidTags.LAVA);
    }

}
