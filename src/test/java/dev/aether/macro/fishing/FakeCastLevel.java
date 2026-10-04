package dev.aether.macro.fishing;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// blocks in a map and air everywhere else, which is all the cast sim reads from a level
final class FakeCastLevel implements CollisionGetter {
    private final Map<BlockPos, BlockState> blocks = new HashMap<>();
    private final WorldBorder border = new WorldBorder();

    FakeCastLevel fill(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, BlockState state) {
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    blocks.put(new BlockPos(x, y, z), state);
                }
            }
        }
        return this;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getHeight() {
        return 384;
    }

    @Override
    public int getMinY() {
        return -64;
    }

    @Override
    public WorldBorder getWorldBorder() {
        return border;
    }

    @Override
    public BlockGetter getChunkForCollisions(int chunkX, int chunkZ) {
        return this;
    }

    @Override
    public List<VoxelShape> getEntityCollisions(Entity entity, AABB box) {
        return List.of();
    }
}
