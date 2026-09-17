package dev.aether.modules.pathfinding.harness;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// a bare voxel grid that can answer the same collision queries a Level does
public final class BlockWorld {
    private final Set<Long> solid = new HashSet<>();
    private final List<AABB> shapes = new ArrayList<>();
    private int bedrockY = Integer.MIN_VALUE;

    public BlockWorld ground(int y) {
        bedrockY = y;
        return this;
    }

    public BlockWorld shape(AABB shape) {
        shapes.add(shape);
        return this;
    }

    public BlockWorld set(int x, int y, int z) {
        solid.add(BlockPos.asLong(x, y, z));
        return this;
    }

    public BlockWorld clear(int x, int y, int z) {
        solid.remove(BlockPos.asLong(x, y, z));
        return this;
    }

    public boolean isSolid(int x, int y, int z) {
        return y <= bedrockY || solid.contains(BlockPos.asLong(x, y, z));
    }

    public BlockWorld fill(int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                    set(x, y, z);
                }
            }
        }
        return this;
    }

    public BlockWorld carve(int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                    clear(x, y, z);
                }
            }
        }
        return this;
    }

    // walls, floor and ceiling of the box, interior left as air
    public BlockWorld shell(int x0, int y0, int z0, int x1, int y1, int z1) {
        fill(x0, y0, z0, x1, y1, z1);
        carve(x0 + 1, y0 + 1, z0 + 1, x1 - 1, y1 - 1, z1 - 1);
        return this;
    }

    public List<AABB> collisions(AABB search) {
        List<AABB> result = new ArrayList<>();
        for (AABB shape : shapes) {
            if (shape.intersects(search)) result.add(shape);
        }
        int minX = Mth.floor(search.minX) - 1, maxX = Mth.floor(search.maxX) + 1;
        int minY = Mth.floor(search.minY) - 1, maxY = Mth.floor(search.maxY) + 1;
        int minZ = Mth.floor(search.minZ) - 1, maxZ = Mth.floor(search.maxZ) + 1;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (!isSolid(x, y, z)) {
                        continue;
                    }
                    AABB box = new AABB(x, y, z, x + 1.0, y + 1.0, z + 1.0);
                    if (box.intersects(search)) {
                        result.add(box);
                    }
                }
            }
        }
        return result;
    }
}
