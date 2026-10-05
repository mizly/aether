package dev.aether.ui.orbit;

import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

// the blocks around the saved garden spot, copied while it was loaded and kept on disk, so the menu can rebuild the
// spot in full however far away you are. each column keeps its surface and the layers just under it
final class SpotSnapshot implements SceneClone.Source {
    static final int RADIUS = 40;
    private static final int MAGIC = 0x41455350;
    private static final int FORMAT = 1;
    // enough below the surface for cliffs, and for columns the menu clips down to knee height around the ring
    private static final int DEPTH = 10;

    final int ox, oy, oz;
    final long takenAt;
    private final int size = RADIUS * 2 + 1;
    private final int[] tops;
    private final int[] lows;
    private final int[][] states;

    private SpotSnapshot(int ox, int oy, int oz, long takenAt, int[] tops, int[] lows, int[][] states) {
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.takenAt = takenAt;
        this.tops = tops;
        this.lows = lows;
        this.states = states;
    }

    // copies the area around the spot; null when any of it isn't loaded on this client
    static SpotSnapshot capture(ClientLevel level, int ox, int oy, int oz) {
        if (!level.hasChunksAt(ox - RADIUS, oz - RADIUS, ox + RADIUS, oz + RADIUS)) return null;
        WorldColumns columns = new WorldColumns(level);
        int size = RADIUS * 2 + 1;
        int[] tops = new int[size * size];
        int[] lows = new int[size * size];
        int[][] states = new int[size * size][];
        for (int dz = -RADIUS; dz <= RADIUS; dz++) {
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                int i = (dz + RADIUS) * size + dx + RADIUS;
                int top = columns.top(ox + dx, oz + dz);
                tops[i] = top;
                if (top == Integer.MIN_VALUE) continue;
                int low = Math.min(top, oy + 2) - DEPTH;
                lows[i] = low;
                int[] column = new int[top - low + 1];
                for (int y = low; y <= top; y++) column[y - low] = Block.getId(columns.state(ox + dx, y, oz + dz));
                states[i] = column;
            }
        }
        return new SpotSnapshot(ox, oy, oz, System.currentTimeMillis(), tops, lows, states);
    }

    boolean centredOn(int x, int y, int z) {
        return x == ox && y == oy && z == oz;
    }

    @Override
    public int top(int x, int z) {
        int i = index(x, z);
        return i < 0 ? Integer.MIN_VALUE : tops[i];
    }

    @Override
    public BlockState state(int x, int y, int z) {
        int i = index(x, z);
        if (i < 0) return Blocks.STONE.defaultBlockState();
        if (tops[i] == Integer.MIN_VALUE || y > tops[i]) return Blocks.AIR.defaultBlockState();
        if (y < lows[i]) return Blocks.STONE.defaultBlockState();
        return Block.stateById(states[i][y - lows[i]]);
    }

    // the saved copy has no biome to ask, so tinted blocks take their plain colour
    @Override
    public int tint(BlockTintSource tint, BlockState state, BlockPos pos) {
        return tint.color(state);
    }

    private int index(int x, int z) {
        int dx = x - ox, dz = z - oz;
        if (Math.abs(dx) > RADIUS || Math.abs(dz) > RADIUS) return -1;
        return (dz + RADIUS) * size + dx + RADIUS;
    }

    // -- disk -------------------------------------------------------------------------------------------------

    void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(temp)))) {
            out.writeInt(MAGIC);
            out.writeInt(FORMAT);
            out.writeInt(Block.BLOCK_STATE_REGISTRY.size());
            out.writeInt(ox);
            out.writeInt(oy);
            out.writeInt(oz);
            out.writeLong(takenAt);
            for (int i = 0; i < tops.length; i++) {
                out.writeInt(tops[i]);
                if (tops[i] == Integer.MIN_VALUE) continue;
                out.writeInt(lows[i]);
                for (int id : states[i]) out.writeInt(id);
            }
        }
        Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    // null when there is no file, it is damaged, or it came from a game with different block ids
    static SpotSnapshot load(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != MAGIC || in.readInt() != FORMAT) return null;
            if (in.readInt() != Block.BLOCK_STATE_REGISTRY.size()) return null;
            int ox = in.readInt(), oy = in.readInt(), oz = in.readInt();
            long takenAt = in.readLong();
            int size = RADIUS * 2 + 1;
            int[] tops = new int[size * size];
            int[] lows = new int[size * size];
            int[][] states = new int[size * size][];
            for (int i = 0; i < tops.length; i++) {
                tops[i] = in.readInt();
                if (tops[i] == Integer.MIN_VALUE) continue;
                lows[i] = in.readInt();
                int count = tops[i] - lows[i] + 1;
                if (count <= 0 || count > 512) return null;
                states[i] = new int[count];
                for (int k = 0; k < count; k++) states[i][k] = in.readInt();
            }
            return new SpotSnapshot(ox, oy, oz, takenAt, tops, lows, states);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
