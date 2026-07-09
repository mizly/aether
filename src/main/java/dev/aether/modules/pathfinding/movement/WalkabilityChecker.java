package dev.aether.modules.pathfinding.movement;

import dev.aether.modules.pathfinding.util.BlockPosUtil;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Centralises all block-state queries for the pathfinder.
 * Results are cached per session via packed-long keys to avoid redundant world lookups.
 */
public final class WalkabilityChecker {

    // Bit flags used in the byte cache
    private static final byte FLAG_SOLID     = 0x01;
    private static final byte FLAG_PASSABLE  = 0x02;
    private static final byte FLAG_DANGEROUS = 0x04;
    private static final byte FLAG_WATER     = 0x08;
    private static final byte FLAG_CLIMBABLE = 0x10;
    private static final byte FLAG_COMPUTED  = (byte) 0x80; // sentinel: entry has been populated

    // Sentinel for "no shape cached yet" vs "empty shape cached"
    private static final double TOP_Y_NOT_CACHED = Double.NaN;

    private final Level level;
    private final Long2ByteOpenHashMap flagCache;
    private final Long2ObjectOpenHashMap<BlockState> stateCache;
    private final Long2DoubleOpenHashMap topYCache;
    // Cached full-wall results: 0 = not cached, 1 = false, 2 = true
    private final Long2ByteOpenHashMap fullWallCache;
    // Reusable mutable BlockPos - eliminates hundreds of thousands of BlockPos allocations
    private final MutableBlockPos mutablePos = new MutableBlockPos();

    public WalkabilityChecker(Level level) {
        this.level = level;
        this.flagCache = new Long2ByteOpenHashMap(8192);
        this.flagCache.defaultReturnValue((byte) 0);
        this.stateCache = new Long2ObjectOpenHashMap<>(8192);
        this.topYCache = new Long2DoubleOpenHashMap(8192);
        this.topYCache.defaultReturnValue(TOP_Y_NOT_CACHED);
        this.fullWallCache = new Long2ByteOpenHashMap(8192);
        this.fullWallCache.defaultReturnValue((byte) 0);
    }

    public void clearCache() {
        flagCache.clear();
        stateCache.clear();
        topYCache.clear();
        fullWallCache.clear();
    }

    public Level getLevel() {
        return level;
    }

    // --- Public query methods ------------------------------------------------

    /**
     * True if the block has a solid collision surface (maxY of VoxelShape >= 0.5 and block is not passable).
     */
    public boolean isSolid(int x, int y, int z) {
        return (getFlags(x, y, z) & FLAG_SOLID) != 0;
    }

    /**
     * True if an entity can move through this position (air, water, plants, etc.).
     */
    public boolean isPassable(int x, int y, int z) {
        return (getFlags(x, y, z) & FLAG_PASSABLE) != 0;
    }

    /**
     * True if the block state is air (no block).
     */
    public boolean isAir(int x, int y, int z) {
        return getState(x, y, z).isAir();
    }

    /**
     * True if the block at y is a good standing position:
     * feet (y) is passable, head (y+1) is passable, floor (y-1) is solid.
     */
    public boolean isWalkable(int x, int y, int z) {
        return isPassable(x, y, z)
                && isPassable(x, y + 1, z)
                && hasWalkableTop(x, y - 1, z)   // uses getCollisionShape - handles stairs/slabs/walls
                && !isDangerous(x, y, z)
                && !isDangerous(x, y + 1, z);
    }

    /**
     * True if the block contains a hazard (lava, fire, magma, cactus, sweet berry bush).
     */
    public boolean isDangerous(int x, int y, int z) {
        return (getFlags(x, y, z) & FLAG_DANGEROUS) != 0;
    }

    /**
     * True if the block is a water source or flowing water.
     */
    public boolean isWater(int x, int y, int z) {
        return (getFlags(x, y, z) & FLAG_WATER) != 0;
    }

    /**
     * True if the block is a ladder or vine (climbable).
     */
    public boolean isClimbable(int x, int y, int z) {
        return (getFlags(x, y, z) & FLAG_CLIMBABLE) != 0;
    }

    /**
     * True if the block has a top collision surface at or above y=0.5.
     * More reliable than {@link #isSolid} for partial blocks such as stairs,
     * slabs, and walls, because it uses the actual VoxelShape from the level
     * rather than the cached flag which uses a null-context fallback.
     */
    public boolean hasWalkableTop(int x, int y, int z) {
        return getTopY(x, y, z) >= 0.5;
    }

    /**
     * True if the block is solid AND has a full-height collision shape (maxY >= 0.95)
     * AND canOcclude + isCollisionShapeFullBlock.
     * Partial blocks such as slabs, stairs, and half-walls return false even when solid.
     * Use this instead of {@link #isSolid} for wall-proximity checks so that slab edges
     * and stair surfaces are not incorrectly treated as impassable walls.
     */
    public boolean isFullWallBlock(int x, int y, int z) {
        long key = BlockPosUtil.pack(x, y, z);
        byte cached = fullWallCache.get(key);
        if (cached != 0) return cached == 2;

        BlockState state = getState(x, y, z);
        boolean result = state.canOcclude() && state.isCollisionShapeFullBlock(level, mutablePos.set(x, y, z));
        fullWallCache.put(key, result ? (byte) 2 : (byte) 1);
        return result;
    }

    /**
     * True if the block is solid AND has a full-height collision shape (maxY >= 0.95).
     * Partial blocks such as slabs, stairs, and half-walls return false even when solid.
     */
    public boolean isFullWall(int x, int y, int z) {
        if (!isSolid(x, y, z)) return false;
        return getTopY(x, y, z) >= 0.95;
    }

    /**
     * True if a solid block at y+1 would cause suffocation.
     */
    public boolean wouldSuffocate(int x, int y, int z) {
        return isSolid(x, y + 1, z) && !isPassable(x, y + 1, z);
    }

    /**
     * Returns false if falling from fromY to toY would deal fall damage,
     * unless water is present at the landing block.
     */
    public boolean safeToFall(int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
        int fallDistance = fromY - toY;
        if (fallDistance <= 3) return true;
        // Water at the landing position breaks the fall safely
        if (isWater(toX, toY, toZ)) return true;
        // Check if there is water anywhere along the fall path
        for (int checkY = toY + 1; checkY < fromY; checkY++) {
            if (isWater(toX, checkY, toZ)) return true;
        }
        return false;
    }

    /**
     * Returns the VoxelShape collision maxY for the block at (x, y, z), in block-local units [0..1].
     * Returns 0 for an empty/passable shape. Cached per session.
     */
    public double getTopY(int x, int y, int z) {
        long key = BlockPosUtil.pack(x, y, z);
        double cached = topYCache.get(key);
        if (!Double.isNaN(cached)) return cached;

        BlockState state = getState(x, y, z);
        VoxelShape shape = state.getCollisionShape(level, mutablePos.set(x, y, z));
        double topY = shape.isEmpty() ? 0.0 : shape.bounds().maxY;
        topYCache.put(key, topY);
        return topY;
    }

    /**
     * Returns true if the block state at (x,y,z) can occlude (used for ceiling proximity checks).
     */
    public boolean canOcclude(int x, int y, int z) {
        return getState(x, y, z).canOcclude();
    }

    /**
     * Returns the cached (or freshly fetched) BlockState at the given coordinates.
     */
    public BlockState getState(int x, int y, int z) {
        long key = BlockPosUtil.pack(x, y, z);
        BlockState state = stateCache.get(key);
        if (state != null) return state;
        state = level.getBlockState(mutablePos.set(x, y, z));
        stateCache.put(key, state);
        return state;
    }

    // --- Internal helpers ----------------------------------------------------

    private byte getFlags(int x, int y, int z) {
        long key = BlockPosUtil.pack(x, y, z);
        byte cached = flagCache.get(key);
        if ((cached & FLAG_COMPUTED) != 0) {
            return cached;
        }
        byte flags = computeFlags(x, y, z);
        flagCache.put(key, (byte) (flags | FLAG_COMPUTED));
        return flags;
    }

    private byte computeFlags(int x, int y, int z) {
        BlockState state = getState(x, y, z);
        byte flags = 0;

        // -- SOLID --
        // Use getTopY which is itself cached
        double topY = getTopY(x, y, z);
        boolean hasSolidShape = topY >= 0.5;
        if (hasSolidShape && !isStatePassable(state, x, y, z)) {
            flags |= FLAG_SOLID;
        }

        // -- PASSABLE --
        if (isStatePassable(state, x, y, z)) {
            flags |= FLAG_PASSABLE;
        }

        // -- WATER --
        FluidState fluidState = state.getFluidState();
        if (!fluidState.isEmpty() && fluidState.getType().isSame(net.minecraft.world.level.material.Fluids.WATER)) {
            flags |= FLAG_WATER;
        }
        // Also mark water source blocks
        if (state.getBlock() instanceof LiquidBlock) {
            Block b = state.getBlock();
            if (b == Blocks.WATER) {
                flags |= FLAG_WATER;
            }
        }

        // -- CLIMBABLE --
        if (state.getBlock() instanceof LadderBlock || state.getBlock() instanceof VineBlock) {
            flags |= FLAG_CLIMBABLE;
        }

        // -- DANGEROUS --
        Block block = state.getBlock();
        if (block == Blocks.LAVA
                || block == Blocks.FIRE
                || block == Blocks.SOUL_FIRE
                || block == Blocks.MAGMA_BLOCK
                || block == Blocks.CACTUS
                || block == Blocks.SWEET_BERRY_BUSH) {
            flags |= FLAG_DANGEROUS;
        }
        // Also check for lava fluid state
        FluidState fs = state.getFluidState();
        if (!fs.isEmpty() && fs.getType().isSame(net.minecraft.world.level.material.Fluids.LAVA)) {
            flags |= FLAG_DANGEROUS;
        }

        return flags;
    }

    /**
     * Determines if an entity can move through the given block state (no solid collision, not a full cube).
     * Instance method - uses {@code this.level} and {@code this.mutablePos} to avoid
     * context-dependent blocks (doors, fence gates) throwing on null context.
     */
    private boolean isStatePassable(BlockState state, int x, int y, int z) {
        if (state.isAir()) return true;
        Block block = state.getBlock();
        // Plants, flowers, grass, etc.
        if (block instanceof BushBlock) return true;
        if (block instanceof TallGrassBlock) return true;
        if (block instanceof FlowerBlock) return true;
        if (block instanceof DoublePlantBlock) return true;
        if (block instanceof TorchBlock) return true;
        if (block instanceof WallTorchBlock) return true;
        // Fluids (water/lava) are passable as physical space
        if (block instanceof LiquidBlock) return true;
        // Signs, banners, etc.
        if (block instanceof SignBlock) return true;
        if (block instanceof BannerBlock) return true;
        if (block instanceof WallBannerBlock) return true;
        // Carpet, pressure plates, rails, etc.
        if (block instanceof CarpetBlock) return true;
        if (block instanceof BasePressurePlateBlock) return true;
        if (block instanceof BaseRailBlock) return true;
        // Snow layer
        if (block == Blocks.SNOW) return true;
        // Buttons
        if (block instanceof ButtonBlock) return true;
        // Vines and ladders - entity CAN be in same position
        if (block instanceof VineBlock) return true;
        if (block instanceof LadderBlock) return true;

        // General: no full solid collision - use actual level + position (no exceptions)
        VoxelShape shape = state.getCollisionShape(level, mutablePos.set(x, y, z));
        return shape == null || shape.isEmpty();
    }
}
