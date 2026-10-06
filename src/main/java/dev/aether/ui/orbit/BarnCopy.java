package dev.aether.ui.orbit;

import dev.aether.util.GardenPlots;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// your garden's Barn as blocks: copied out of the chunks the server has sent once the whole Barn plot is in view,
// kept on disk per account, and stood in the menu's farm in place of its own barn. reads blocks only
final class BarnCopy {
    static final int MAX_SIDE = 32;
    static final int MAX_HEIGHT = 28;
    private static final int BARN_PLOT = 0;
    private static final long REFRESH_MS = 5 * 60_000L;
    // what counts as the building: columns standing this far over the plot's floor
    private static final int TALL = 3;

    // the footprint is width along x by depth along z; layer 0 is the plot's floor, the building stands on it
    record Copy(int width, int height, int depth, BlockState[] blocks) {
        BlockState at(int x, int y, int z) {
            return blocks[(y * depth + z) * width + x];
        }
    }

    private static UUID owner;
    private static Copy copy;
    private static long recorded;

    private BarnCopy() {
    }

    static Copy current(Minecraft client) {
        load(client);
        return copy;
    }

    // a fresh copy when the Barn plot is loaded and the last copy is old; true when it took one
    static boolean refresh(Minecraft client) {
        if (client.level == null || client.player == null) return false;
        load(client);
        long now = System.currentTimeMillis();
        if (now - recorded < REFRESH_MS) return false;
        Copy fresh = scan(client.level);
        if (fresh == null) return false;
        copy = fresh;
        recorded = now;
        save(client, fresh);
        return true;
    }

    private static Copy scan(ClientLevel level) {
        GardenPlots.Bounds plot = GardenPlots.boundsForPlot(BARN_PLOT);
        if (plot == null) return null;
        // only part of the plot may be in from where you stand; columns not in yet stay unknown, and the copy waits
        // until everything under the building has arrived
        WorldColumns columns = new WorldColumns(level);
        int w = plot.maxX() - plot.minX(), d = plot.maxZ() - plot.minZ();
        int[] tops = new int[w * d];
        Map<Integer, Integer> counts = new HashMap<>();
        int known = 0;
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                int wx = plot.minX() + x, wz = plot.minZ() + z;
                // an empty column in a loaded chunk is the server not having sent its blocks yet
                int top = level.hasChunk(wx >> 4, wz >> 4) ? columns.top(wx, wz) : Integer.MIN_VALUE;
                tops[z * w + x] = top;
                if (top == Integer.MIN_VALUE) continue;
                counts.merge(top, 1, Integer::sum);
                known++;
            }
        }
        if (known < w * d / 4) return null;
        int ground = counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        // the building's middle, weighted by height so the barn outweighs lamp posts and fences round the plot
        double sx = 0, sz = 0, weight = 0;
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                int top = tops[z * w + x];
                if (top == Integer.MIN_VALUE || top - ground < TALL) continue;
                int rise = top - ground;
                sx += (double) x * rise;
                sz += (double) z * rise;
                weight += rise;
            }
        }
        if (weight == 0) return null;
        int cx = (int) Math.round(sx / weight), cz = (int) Math.round(sz / weight);
        int x0 = Math.max(0, cx - MAX_SIDE / 2), z0 = Math.max(0, cz - MAX_SIDE / 2);
        int x1 = Math.min(w - 1, x0 + MAX_SIDE - 1), z1 = Math.min(d - 1, z0 + MAX_SIDE - 1);
        // shrink the window to what actually stands in it, with a block of floor round the edge
        int bx0 = x1, bz0 = z1, bx1 = x0, bz1 = z0, peak = ground;
        boolean found = false;
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                int top = tops[z * w + x];
                if (top == Integer.MIN_VALUE || top - ground < TALL) continue;
                found = true;
                bx0 = Math.min(bx0, x);
                bz0 = Math.min(bz0, z);
                bx1 = Math.max(bx1, x);
                bz1 = Math.max(bz1, z);
                peak = Math.max(peak, top);
            }
        }
        // two buildings far apart can pull the middle onto open ground, leaving nothing in the window to copy
        if (!found) return null;
        bx0 = Math.max(x0, bx0 - 1);
        bz0 = Math.max(z0, bz0 - 1);
        bx1 = Math.min(x1, bx1 + 1);
        bz1 = Math.min(z1, bz1 + 1);
        // anything still unknown in or right round the building means part of it hasn't arrived
        for (int z = Math.max(0, bz0 - 2); z <= Math.min(d - 1, bz1 + 2); z++) {
            for (int x = Math.max(0, bx0 - 2); x <= Math.min(w - 1, bx1 + 2); x++) {
                if (tops[z * w + x] == Integer.MIN_VALUE) return null;
            }
        }
        int width = bx1 - bx0 + 1, depth = bz1 - bz0 + 1, height = Math.min(MAX_HEIGHT, peak - ground + 1);
        BlockState[] blocks = new BlockState[width * height * depth];
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < depth; z++) {
                for (int x = 0; x < width; x++) {
                    blocks[(y * depth + z) * width + x] = columns.state(plot.minX() + bx0 + x, ground + y, plot.minZ() + bz0 + z);
                }
            }
        }
        return new Copy(width, height, depth, blocks);
    }

    // copies belong to whoever is playing, so switching accounts swaps them
    private static void load(Minecraft client) {
        if (client.player == null) return;
        UUID id = client.player.getUUID();
        if (id.equals(owner)) return;
        owner = id;
        copy = null;
        recorded = 0L;
        Path file = GardenMemory.dir(id).resolve("barn.nbt");
        if (!Files.isRegularFile(file)) return;
        try {
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            int width = tag.getIntOr("width", 0), height = tag.getIntOr("height", 0), depth = tag.getIntOr("depth", 0);
            ListTag palette = tag.getListOrEmpty("palette");
            int[] data = tag.getIntArray("data").orElse(new int[0]);
            if (width <= 0 || height <= 0 || depth <= 0 || width > MAX_SIDE || depth > MAX_SIDE || height > MAX_HEIGHT
                    || data.length != width * height * depth) return;
            BlockState[] states = new BlockState[palette.size()];
            for (int i = 0; i < states.length; i++) {
                states[i] = NbtUtils.readBlockState(BuiltInRegistries.BLOCK, palette.getCompoundOrEmpty(i));
            }
            BlockState[] blocks = new BlockState[data.length];
            for (int i = 0; i < data.length; i++) {
                blocks[i] = data[i] >= 0 && data[i] < states.length ? states[data[i]] : Blocks.AIR.defaultBlockState();
            }
            copy = new Copy(width, height, depth, blocks);
            recorded = Files.getLastModifiedTime(file).toMillis();
        } catch (Exception e) {
            System.err.println("[Aether] could not read the saved barn: " + e.getMessage());
        }
    }

    private static void save(Minecraft client, Copy copy) {
        List<BlockState> palette = new ArrayList<>();
        Map<BlockState, Integer> index = new HashMap<>();
        int[] data = new int[copy.blocks().length];
        for (int i = 0; i < data.length; i++) {
            data[i] = index.computeIfAbsent(copy.blocks()[i], s -> {
                palette.add(s);
                return palette.size() - 1;
            });
        }
        ListTag states = new ListTag();
        for (BlockState s : palette) states.add(NbtUtils.writeBlockState(s));
        CompoundTag tag = new CompoundTag();
        tag.putInt("width", copy.width());
        tag.putInt("height", copy.height());
        tag.putInt("depth", copy.depth());
        tag.put("palette", states);
        tag.putIntArray("data", data);
        try {
            Path dir = GardenMemory.dir(client.player.getUUID());
            Files.createDirectories(dir);
            NbtIo.writeCompressed(tag, dir.resolve("barn.nbt"));
        } catch (Exception e) {
            System.err.println("[Aether] could not save the barn: " + e.getMessage());
        }
    }
}
