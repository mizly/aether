package dev.aether.ui.orbit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

// a copy of the blocks around the scene anchor, meshed once from the game's own block models when the menu opens:
// the surface of every column, cliffs down to the lowest neighbour, water and lava on top. read-only on the level
final class SceneClone {
    static final int STRIDE = 24;

    // vertex data relative to origin (x, y, z float, u, v float, rgba bytes), opaque/cutout and translucent apart
    record Mesh(int originX, int originY, int originZ, ByteBuffer solid, int solidCount, ByteBuffer water, int waterCount,
                int radius) implements AutoCloseable {
        @Override
        public void close() {
            if (solid != null) MemoryUtil.memFree(solid);
            if (water != null) MemoryUtil.memFree(water);
        }
    }

    // how tall the clone may stand at a column: near the ring nothing pokes above the player's knees so the panels
    // stay in view, near the camera nothing rises above the floor so the lens never sits inside a block
    interface Cap {
        int maxY(int dx, int dz);
    }

    private final ClientLevel level;
    private final WorldColumns columns;
    private final BlockStateModelSet models;
    private final BlockColors colors;
    private final List<BlockStateModelPart> parts = new ArrayList<>();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private Buffer solid = new Buffer(1 << 16);
    private Buffer water = new Buffer(1 << 12);

    private SceneClone(ClientLevel level) {
        this.level = level;
        this.columns = new WorldColumns(level);
        Minecraft client = Minecraft.getInstance();
        this.models = client.getModelManager().getBlockStateModelSet();
        this.colors = client.getBlockColors();
    }

    static Mesh build(ClientLevel level, int ox, int oy, int oz, int radius, Cap cap) {
        return new SceneClone(level).mesh(ox, oy, oz, radius, cap);
    }

    private Mesh mesh(int ox, int oy, int oz, int radius, Cap cap) {
        int size = radius * 2 + 1;
        int[] tops = new int[size * size];
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int top = columns.top(ox + dx, oz + dz);
                int limit = oy + cap.maxY(dx, dz);
                tops[(dz + radius) * size + dx + radius] = top == Integer.MIN_VALUE ? top : Math.min(top, limit);
            }
        }
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int dist2 = dx * dx + dz * dz;
                if (dist2 > radius * radius) continue;
                int top = tops[(dz + radius) * size + dx + radius];
                if (top == Integer.MIN_VALUE) continue;
                int low = top;
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int nx = dx + d[0], nz = dz + d[1];
                    if (Math.abs(nx) > radius || Math.abs(nz) > radius) continue;
                    int n = tops[(nz + radius) * size + nx + radius];
                    if (n != Integer.MIN_VALUE) low = Math.min(low, n + 1);
                }
                low = Math.max(low, top - 8);
                for (int y = top; y >= low; y--) {
                    BlockState state = columns.state(ox + dx, y, oz + dz);
                    if (WorldColumns.seeThrough(state)) continue;
                    block(state, ox + dx, y, oz + dz, ox, oy, oz, tops, size, radius);
                }
            }
        }
        return new Mesh(ox, oy, oz, solid.finish(), solid.count, water.finish(), water.count, radius);
    }

    // a neighbour hides a face only when it is drawn too: under the column's clipped top and solid
    private boolean hides(int x, int y, int z, int ox, int oz, int[] tops, int size, int radius) {
        int dx = x - ox, dz = z - oz;
        if (Math.abs(dx) > radius || Math.abs(dz) > radius) return true;
        int top = tops[(dz + radius) * size + dx + radius];
        if (top == Integer.MIN_VALUE || y > top) return false;
        BlockState state = columns.state(x, y, z);
        return state.isSolidRender() && !WorldColumns.seeThrough(state);
    }

    private void block(BlockState state, int x, int y, int z, int ox, int oy, int oz, int[] tops, int size, int radius) {
        if (!state.getFluidState().isEmpty()) fluid(state, x, y, z, ox, oy, oz, tops, size, radius);
        if (state.is(Blocks.WATER) || state.is(Blocks.LAVA)) return;
        parts.clear();
        models.get(state).collectParts(RandomSource.create(Mth.getSeed(x, y, z)), parts);
        float fx = x - ox, fy = y - oy, fz = z - oz;
        pos.set(x, y, z);
        for (BlockStateModelPart part : parts) {
            for (Direction dir : Direction.values()) {
                if (hides(x + dir.getStepX(), y + dir.getStepY(), z + dir.getStepZ(), ox, oz, tops, size, radius)) continue;
                for (BakedQuad quad : part.getQuads(dir)) quad(state, quad, fx, fy, fz);
            }
            for (BakedQuad quad : part.getQuads(null)) quad(state, quad, fx, fy, fz);
        }
    }

    private void quad(BlockState state, BakedQuad quad, float fx, float fy, float fz) {
        BakedQuad.MaterialInfo info = quad.materialInfo();
        int rgb = 0xFFFFFF;
        if (info.isTinted()) {
            BlockTintSource tint = colors.getTintSource(state, info.tintIndex());
            if (tint != null) {
                try {
                    rgb = tint.colorInWorld(state, level, pos) & 0xFFFFFF;
                } catch (RuntimeException ignored) {
                }
            }
        }
        float shade = info.shade() ? shade(quad.direction()) : 1f;
        Buffer out = info.layer() == ChunkSectionLayer.TRANSLUCENT ? water : solid;
        int color = rgba(rgb, shade, 255);
        int[] order = {0, 1, 2, 0, 2, 3};
        for (int i : order) {
            Vector3fc p = quad.position(i);
            long uv = quad.packedUV(i);
            out.vertex(fx + p.x(), fy + p.y(), fz + p.z(), UVPair.unpackU(uv), UVPair.unpackV(uv), color);
        }
    }

    // a flat sheet at the fluid's surface with the still texture, only where nothing of it lies on top
    private void fluid(BlockState state, int x, int y, int z, int ox, int oy, int oz, int[] tops, int size, int radius) {
        BlockState above = columns.state(x, y + 1, z);
        if (!above.getFluidState().isEmpty()) return;
        boolean lava = state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA);
        TextureAtlasSprite sprite;
        try {
            sprite = models.getParticleMaterial(lava ? Blocks.LAVA.defaultBlockState() : Blocks.WATER.defaultBlockState()).sprite();
        } catch (RuntimeException e) {
            return;
        }
        float h = y - oy + 0.875f;
        float x0 = x - ox, z0 = z - oz;
        int color = lava ? rgba(0xFFFFFF, 1f, 255) : rgba(0x3F76E4, 1f, 190);
        Buffer out = lava ? solid : water;
        float u0 = sprite.getU0(), u1 = sprite.getU1(), v0 = sprite.getV0(), v1 = sprite.getV1();
        out.vertex(x0, h, z0, u0, v0, color);
        out.vertex(x0 + 1, h, z0, u1, v0, color);
        out.vertex(x0 + 1, h, z0 + 1, u1, v1, color);
        out.vertex(x0, h, z0, u0, v0, color);
        out.vertex(x0 + 1, h, z0 + 1, u1, v1, color);
        out.vertex(x0, h, z0 + 1, u0, v1, color);
    }

    private static float shade(Direction dir) {
        return switch (dir) {
            case UP -> 1f;
            case DOWN -> 0.5f;
            case NORTH, SOUTH -> 0.8f;
            case EAST, WEST -> 0.6f;
        };
    }

    static int rgba(int rgb, float shade, int alpha) {
        int r = Math.min(255, Math.round(((rgb >> 16) & 255) * shade));
        int g = Math.min(255, Math.round(((rgb >> 8) & 255) * shade));
        int b = Math.min(255, Math.round((rgb & 255) * shade));
        return r | g << 8 | b << 16 | alpha << 24;
    }

    // a growable direct buffer of vertices
    static final class Buffer {
        private ByteBuffer data;
        int count;

        Buffer(int vertices) {
            data = MemoryUtil.memAlloc(vertices * STRIDE);
        }

        void vertex(float x, float y, float z, float u, float v, int rgba) {
            if (data.remaining() < STRIDE) {
                int at = data.position();
                data = MemoryUtil.memRealloc(data, data.capacity() * 2);
                data.limit(data.capacity()).position(at);
            }
            data.putFloat(x).putFloat(y).putFloat(z).putFloat(u).putFloat(v).putInt(rgba);
            count++;
        }

        ByteBuffer finish() {
            data.flip();
            return data;
        }

        void reset() {
            data.clear();
            count = 0;
        }

        void free() {
            MemoryUtil.memFree(data);
        }
    }
}
